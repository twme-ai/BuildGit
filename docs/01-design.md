# BuildGit design (draft of 2026-10-14)

Requirements are in [00-requirements.md](00-requirements.md). This document explains how it works, and ends with the risks to verify with prototypes first. Build worlds created by players (one repo per world) are in [03-worlds.md](03-worlds.md).

## 1. Core idea: genesis world + overlay

BuildGit only records blocks touched by players, so a version is not "a snapshot of the whole world" but:

```
a version's world state = genesis world + that version's overlay
```

| Term | Meaning |
|---|---|
| **Genesis world** | At `init`, a copy of the overworld's region files is made and frozen, never changing afterwards. All branches share this one copy |
| **Overlay** | "Which blocks differ from the genesis world, and what they are now". Only contains blocks touched by players |
| **Working world** | The actual Minecraft world of each branch, where players build |
| **Dirty mark** | A block or entity in a working world "touched by a player since the last commit", with its author |

What this design gives:
- A commit only looks at marked blocks, regardless of world size.
- Unmarked changes such as natural growth and mob behavior never get into versions.
- Branch worlds can be created lazily: chunks nobody has visited just need to be worked out from "genesis world + overlay" (§4).

### 1.1 What a block holds

A block's content is one indivisible unit:
- the block state (`minecraft:oak_stairs[facing=north,half=bottom,...]`, properties sorted by key);
- block entity NBT (container contents, sign text and so on), hashed after normalization.

Two blocks are "the same" when both of these are the same. Merges, diffs and attribution all work per block.

Entities are keyed by UUID, with normalized NBT as content. Genesis entities removed by players are recorded as tombstones.

Biomes use the same storage unit as vanilla, a 4×4×4 "biome cell" (64 per section), holding a biome ID. Merges and diffs work per biome cell, with no author lookup (`inspect`).

### 1.2 Normalization

Reuses WorldGit's proven rules (WorldGit `docs/02-data-model.md` §3):
- sort NBT keys;
- drop Paper/Bukkit-only fields;
- drop transient entity fields: motion, fall distance, fire, age and so on;
- drop transient block entity fields: furnace burn time, spawner countdown and so on.

This code can be copied from WorldGit and trimmed, but no library is shared.

## 2. Storage format

### 2.1 Directory

```
<server root>/buildgit/             # Fabric single-player: <save>/buildgit/
├─ config.yml
├─ data-version                     # Minecraft data versions that have written this repo (§4.5)
├─ objects/                         # content-addressed objects (SHA-256, zstd-compressed)
├─ refs/branches/<name>             # branch → commit hash
├─ branches.yml                     # branch → working world name, where it was created from
├─ genesis/<dimension>/{region,entities}/   # the frozen genesis world (can be put elsewhere with init.genesis-dir; the location is recorded in genesis-dir)
├─ worktree/<world>/marks.journal   # each working world's marks (§3.6)
└─ merge/<id>/                      # merges in progress (§5.3)
```

One repo per server (or per single-player save). Versioned worlds share one repo, told apart by the first level of the tree (the dimension id).

### 2.2 Objects

```
commit
 ├─ parents: [..]                   # merge commits have two
 ├─ author, committer, time, message
 ├─ tree   → root of the overlay
 └─ changes → changeset             # what this commit changed, and who changed each

tree (overlay)
 └─ dimension minecraft:overworld
     └─ region r.0.0
         └─ chunk c.3.7
             ├─ section s.4 → overlay-section blob
             └─ entities    → overlay-entities blob

overlay-section blob: the blocks and biome cells in this section that differ from the genesis world
 ├─ palette: string table of block contents (block state + block entity hash)
 ├─ sorted list of (block index 0–4095, palette index)
 └─ sorted list of (biome cell index 0–63, biome ID) (version 3; without biomes, still written as version 2, so the hash is unchanged)

overlay-entities blob: the tracked entities in this chunk (UUID → NBT or tombstone)

changeset: the list of changes in this commit relative to its parent
 ├─ per block: position, old content hash, new content hash, author index
 └─ per biome cell: position of the minimum corner, author index (version 2)
```

- **The tree only stores paths with overrides**: regions, chunks and sections with no overrides do not appear in the tree.
- **Content-addressed**: unchanged subtrees reuse the parent commit's hashes, so a commit only adds the changed sections, the trees above them, the changeset and the commit.
- **Changesets serve author lookups**: `log`, `inspect` and `diff <commit>` read the changeset directly, without comparing two trees. Merges compare trees.
- **Room for remotes**: like git, objects are self-describing and immutable, so adding push/pull later only needs transferring objects and refs.

### 2.3 Newly generated chunks

Chunks the overworld generates after `init` are not in the genesis world. Their "genesis content" is defined as "the output of the world generator with the same seed and version". When a branch world loads, they are generated the same way, then the overlay is applied (§4.2).

This assumes world generation is deterministic (same output for the same seed, version and datapacks). To be verified by a prototype (§10 risk 4).

**Paper implementation (the fallback was adopted)**: does not rely on generation being deterministic. When the overworld generates a new chunk (`ChunkLoadEvent#isNewChunk`, before any player touched it), a snapshot is taken on the main thread and written into `genesis/`'s region files by a dedicated thread; chunks already in the genesis world are never overwritten. Before a save reads the genesis world, it waits for these writes to finish.

Chunks generated first in a draft world are added to the genesis world the same way: saves compare against the genesis world, and chunks missing from the genesis count as air. So drafts must use the same generator as the overworld (Paper superflat worlds also carry the layer settings, §4.1), or wrong terrain would get into the genesis shared by all branches.

### 2.4 Which worlds are versioned

Only worlds where `/bg init` has been run (`genesis/<dimension>/` exists). Each world is initialized separately: init in the overworld does not also track the Nether and the End; worlds created by multiworld plugins such as Multiverse are the same, just init in that world. Worlds without init are not affected at all (nothing recorded, saved, or made into draft worlds).

- Multiworld plugins often load worlds after BuildGit, or during the game: a world joins the main branch when it loads, and open drafts get that world added too.
- When a multiworld plugin wants to unload a versioned world, it is only allowed when that dimension's main world and every branch world have no players, nothing is saving or publishing, and the genesis world is not being copied; otherwise Paper blocks `WorldUnloadEvent` (Fabric cannot block it). After unloading it is not tracked, and unsaved marks stay until it loads again. Versions are committed first and then written to worlds, so when any initialized dimension world of a branch is not loaded, publishing, reverting, restoring and resolution are refused outright, so that part does not end up only in versions and not in the world (decision #43).
- Draft world names: overworld `bg_<branch>`, Nether and End `bg_<branch>_nether` and `bg_<branch>_the_end`, other worlds `bg_<branch>__<the world's dimension path>` (e.g. `bg_d1__creative`).

### 2.5 Offsite backups (S3)

The repository is uploaded incrementally to S3-compatible object storage (AWS S3, MinIO, Backblaze B2 and so on), so when the whole server host fails, all branches and history can be restored on a new host, with the overworld rebuilt from versions. This is not sync between servers: one backup location belongs to one server.

**Scope**: backs up "saved builds", not the whole server.

| Backed up | Not backed up |
|---|---|
| All save objects (including autosaves on drafts) | Marks not saved yet (losing at most one autosave interval) |
| All branch refs, `branches.yml`, `data-version` | Draft world folders (rebuilt from saves, §4.2) |
| The genesis world's region and entities files | Merges in progress (`merge/`), pull records (`sync/`) |
| Versioned worlds' `level.dat` and, on 26.x, `world_gen_settings.dat` (seed, generator) | Player data, untracked natural changes, other plugins' data |

**Remote layout** (`<prefix>` comes from settings, so one bucket can hold several servers):

```
<prefix>/objects/ab/cdef…          # the same files as locally, uploaded as is; immutable
<prefix>/files/<SHA-256>           # genesis world files, level.dat and so on, named by content hash; immutable
<prefix>/snapshots/<UTC time>.yml  # one backup: refs, branches.yml, data-version, file list
```

A snapshot records: the commit each branch points at, the contents of `branches.yml` and `data-version`, the BuildGit and Minecraft versions, and for each versioned dimension its world folder (relative path), seed, `genesis world relative path → file hash` and `level.dat etc. → file hash`.

level.dat is found by walking up from the dimension's save directory; from 26.x the seed is also stored in `data/minecraft/world_gen_settings.dat` (in the world folder on vanilla, in each dimension's directory on Paper), which is backed up too when found.

**Settings**: the history backup in this section is one kind of "backup job" (`what: history`), whose destination, schedule and retention are set by the job (§2.7); the S3 connection is in a separate `s3.yml` (it has keys, while `config.yml` is rewritten on plugin updates with an `.old` copy left, so they are kept apart). BuildGit only writes the template when the file does not exist, and never changes it afterwards.

**One backup run** (on the backup background thread, on the job's schedule, or `/bg backup now`):
1. **Record refs**: read all branches' refs. A ref is always updated after its objects are written, so these commits' objects are all on disk.
2. **Genesis world files**: on the thread that writes the genesis world (in order with adding new chunks and `genesis upgrade`, §4.5), check each file's size and modification time; changed files are first copied to a temporary file and hashed, and only uploaded when the remote lacks that hash. Files already sent that changed later are resent at most every `genesis-hours` (a job setting, 24 by default, 0 for every time), reusing the last hash in between (the local record is not changed, so it still counts as changed when the time comes); new files are sent every time. With lazily copied large worlds, region files keep changing during exploration, and resending them all every time costs too much traffic (decision #66). The price is that a snapshot's genesis world may lack recently added chunks: lazily copied dimensions read missing chunks from the world folder backup on restore anyway, and fully copied dimensions only lack newly generated chunks, which the generator produces again. Region files are written in place (new chunks, format rewrites), so copying on the writer thread avoids getting half-written content. Step 1 comes first, so this genesis world can only be newer than the refs, and every chunk the refs need is in it.
3. **Objects**: walk from commits not uploaded yet (parents, tree, changeset). The tree walk only goes into subtrees that differ from the parent commit, so the cost is proportional to the changes in that period, without scanning all of `objects/`. Uploaded commits are recorded locally in `buildgit/backup/commits`.
4. **Write a snapshot only when something changed**: only when there are new commits, refs moved or were deleted, `branches.yml`/`data-version` changed, or genesis world files changed, are objects and `level.dat` uploaded and a snapshot written; otherwise nothing happens. `level.dat` changes all the time (time, weather), so it does not count as a change and is only included when writing a snapshot.
5. **Write the snapshot last**. On failure midway no snapshot is written and the next run starts again; objects and files are immutable, so nothing uploaded is wasted.

- When the local upload record (`buildgit/backup/`: uploaded commits, genesis files' "size time hash", the previous snapshot) is lost, it walks from the start, first listing the objects already on the remote, and only sends the missing ones.
- Uploaded commits are only recorded once the snapshot is written, so before uploading objects it leaves a marker `backup/uploading`: when an upload is interrupted halfway (most common on the first full upload), the next run also first lists the objects already on the remote and does not resend them.
- Each request retries after 2 and 8 seconds on connection failures, 5xx or 429, up to three times; only then does the backup count as failed. From the third failure in a row, online players with `buildgit.backup` are warned every time.
- There are many small objects (one per section), so 8 are uploaded at once.
- Remote objects are never deleted (nor are local objects reclaimed). Snapshots keep the newest `keep`, after which `files/` no snapshot uses are deleted.

**Restoring** (`/bg backup restore [snapshot]`, console only; the newest when none is given):
1. **Download**: while the server keeps running, the backup background thread (never at the same time as a backup) downloads the snapshot to `buildgit-restore/`.
   - Objects: walk down from the snapshot's refs (parents, tree, changeset), downloading only what is used, one level at a time in parallel, each decompressed and its hash verified.
   - Genesis world files have their SHA-256 verified. When `init.genesis-dir` points elsewhere, the genesis world is downloaded to `<folder>-restore` next to it (on the same disk, so swapping in is just a rename).
   - The local upload record is written too (uploaded commits, genesis file hashes, the previous snapshot), so the first backup after restoring resends nothing.
2. **Synthesize main's worlds first**: for each dimension, the region and entities files are synthesized from "genesis world + main's latest save" into `buildgit-restore/worlds/`, with the same code as draft worlds (§4.2). This is done during the download, while the server is still running and the DataFixer for upgrading data and the world information are available, so no synthesizing reads need hooking into main's worlds.
3. **Write `restore.yml`**: the folders and files to swap (target → source), the last entry being the repository itself. Only once written does the download count as finished, and the admin is told to restart; to cancel, delete `buildgit-restore/`.
4. **Swap in**: at the next startup, before worlds load (Paper's `onLoad`, Fabric's `SERVER_STARTING`), `restore.yml` is processed in order: an existing target is renamed to `<name>.before-restore-<time>`, then the source is moved there.
   - Each dimension's `region/` and `entities/` in main's worlds are replaced with the synthesized ones, and `poi/` is renamed (rebuilt from blocks as chunks load).
   - Draft and view world folders starting with `bg_` and `bgview_` are renamed, and rebuilt later from the restored versions.
   - `buildgit/` is replaced with `buildgit-restore/`.
   - Always renamed, never deleted; the admin deletes them after checking everything is fine.
   - On failure midway (e.g. a full disk), startup stops rather than running the server in a half state; after fixing it and restarting, entries already swapped (source gone) are skipped and it continues where it stopped.
- Chunks not even in the genesis world are generated by the generator as usual, so the seed must be the same: with the same Minecraft version, the snapshot's `level.dat` and `world_gen_settings.dat` are swapped in. Datapacks and mods must be reinstalled the same by the admin.

**Fetching one branch** (`/bg backup fetch <branch> [new name]`): e.g. a draft deleted by mistake. No restart, no other branches touched.
- From the newest snapshot that has this branch, walk down as above, downloading only objects not present locally (everything under an object already present locally is complete too, since objects are always written after the objects they reference).
- When fetching is interrupted halfway, objects downloaded first may still be missing things beneath them, so a marker `backup/fetching` is left before fetching; when the marker is seen next time, even objects already present locally are checked downward.
- The branch is created on the main thread: its source and world follow `branches.yml` in the snapshot, using main when the source no longer exists. When a branch with the name exists, it is refused and a new name must be given.

**Across versions**:
- Objects and genesis chunks carry data versions and are uploaded as is; restored onto a newer server, they are upgraded on read as usual (§4.5).
- The snapshot also records the server's data version at write time (`minecraft-data-version`). When restoring onto a server of a different version, main's world location follows the current server (1.21.11's `world/region` is `world/dimensions/minecraft/overworld/region` on 26.x), and level.dat is not swapped (world folder structures differ between versions, and swapping it could trigger an old world's upgrade flow); when the seed differs from the backup, the admin is told to set `level-seed` before the new world is generated.
- The snapshot carries `data-version`: when the server is older than `newest`, restoring is refused (the same rule as opening the repo), checked before downloading.
- Genesis files rewritten by `genesis upgrade` get new hashes and are re-uploaded on the next backup (one large upload, which the command warns about). Old snapshots still point at the old files, so restoring to a time before the rewrite gets the old format.

**Lazily copied dimensions** (§4.3): the genesis world lacks chunks that were never loaded, which exist only in main's world save, so such a dimension cannot be rebuilt from the backup alone. Backups mark it "incomplete" in the status and warn on the console; on restore the world folder of this dimension is not swapped, and the admin must use a world backup from the same time; the genesis world is restored as usual and pointed back at main's world save.

**Connection**:
- SigV4 signing is implemented ourselves with Java's built-in HttpClient, without the AWS SDK (which would add tens of MB to the jar). Only PUT, GET, HEAD, DELETE and ListObjectsV2 are needed.
- Path-style is supported (needed by MinIO and others).
- Keys go in `s3.yml`.

**Commands** (permission `buildgit.backup`):
- `/bg backup status`: last success time, commits not uploaded yet, number of remote snapshots, incomplete dimensions.
- `/bg backup now`: back up now.
- `/bg backup list`: list remote snapshots.
- `/bg backup fetch <branch> [new name]`: get one branch back from the backup.
- `/bg backup restore [snapshot]`: download and prepare a restore, swapped in on restart (console only).

Acceptance (phase 1): the signature matches the examples in AWS's docs, and actual uploads, downloads, lists and deletes work against an S3-compatible service that verifies signatures; wrong keys are refused. On a Fabric 1.21.11 test server the first full backup runs, nothing is backed up without changes, and after changing and saving one block only 7 objects are uploaded; walking every commit's whole tree from the refs, the remote has all of them with the same content. Snapshots keep only the configured number, and unused files are deleted along with them. On Paper 26.3 the world folder and `level.dat` locations are correct.

Acceptance (phase 2): on both Fabric 1.21.11 and Paper 26.3, save and back up one block, then save another and place one more without saving; after restoring and restarting only the backed-up one remains, history and snapshots are the same, and the following backup finds no changes; Paper swaps in at `onLoad`, and 26.x's `world_gen_settings.dat` is swapped back too. A 1.21.11 snapshot restored onto Fabric 26.3: the block is in the new version's world location, history is complete, and it warns about the different seed and the unloaded dimension.

### 2.6 World folder backups

§2.5 only backs up history; main's worlds are rebuilt from "genesis world + latest save", so player data, `data/` (map art, scoreboards, raids, world border; game rules on 26.x), unsaved changes and unrecorded natural changes are missing, and lazily copied worlds are incomplete. This section backs up the world folders themselves as well, with a different strategy from history:

| | History (§2.5) | World folders |
|---|---|---|
| When (typical setup) | checked every hour, backed up only when there are new saves | once a day (e.g. 04:00), or by hand |
| Size per run | small | large (changed region files, player data…) |
| Layout | objects and files named by hash | **follows the world folder's layout** |

Schedule, retention and destination are set by each backup job (§2.7); this section only covers how world folders are backed up. Jobs also have:

```yaml
    announce: true         # tell the chat when it starts and ends
    exclude: []            # extra paths to exclude (relative to the server root, * wildcards allowed)
    exclude-worlds: []     # worlds not to back up: world folders, or build worlds as "owner/name" (history jobs have it too, but can only exclude build worlds)
    storage: auto          # auto, folders, versioned (S3 only), hashed; see below
```

**Storage** (`storage`): `auto` by default, picking the most space-saving way for the destination: `folders` (hard links) for local; `versioned` for S3 when the bucket has versioning on, `hashed` when not. It can also be set directly.

- **`folders`**: one folder per snapshot, with the same relative paths as on the server.

  ```
  <location>/<UTC time>/world/level.dat
  <location>/<UTC time>/world/region/r.0.0.mca
  <location>/<UTC time>/world_nether/DIM-1/region/…   # the Nether on Paper 1.21.11
  <location>/<UTC time>/buildgit/worktree/main/…      # main's unsaved marks
  <location>/snapshots/<UTC time>.yml                 # the manifest, written last; only snapshots with a manifest are complete
  ```

  Every snapshot is a plain folder, usable directly with a file manager, S3's web console or any sync tool, without BuildGit. Unchanged files are not re-uploaded: local copies use hard links (no space), S3 uses server-side copies (CopyObject, no re-sending, but the space is about "world size × count"; object storage is cheap, so this is accepted).
- **`versioned`** (S3 only; the bucket must have versioning on, which BuildGit checks and reports): the remote holds just one `<location>/current/…`, each run uploads only changed files, and S3 keeps old contents as old versions. The version id returned by each upload is recorded, and the manifest records "which version of each file this snapshot uses"; restoring fetches those versions per the manifest. When deleting old snapshots, BuildGit itself deletes the versions used only by the deleted snapshots (by version id), without setting bucket lifecycle rules (which cover the whole bucket and are dangerous when the bucket has other uses).
  - Pros: only changed files are stored again, and unchanged files need no requests at all.
  - Cons: an old point in time has to be fetched file by file and version by version per the manifest, so in practice it needs BuildGit; the service must support versioning (AWS, MinIO and Wasabi do; Backblaze B2 needs testing; Cloudflare R2 does not for now).
- **`hashed`**: like history's `files/`, each file is named by the SHA-256 of its content (`<location>/files/<hash>`), and the manifest records "path → hash". The same content is stored once, and unchanged files need no requests at all; after old snapshots are deleted, files no snapshot uses are deleted.
  - Cons: old snapshots cannot be browsed as folders directly; BuildGit reassembles them per the manifest (which is what a restore does).

**Which worlds are backed up**: the folders of all worlds on the server, including players' build worlds ([03-worlds.md](03-worlds.md)), except draft, view and resolution worlds, which can be rebuilt from versions. A build world's folder name changes after a rename, so the manifest also records which build world id each folder belongs to, and a restore puts it back under its current name per the index.
- **Only worlds players have been in are processed**: only worlds someone has entered since this job last ran are checked and have their changed files uploaded; for the others, the previous snapshot is reused as a whole (hard links, server-side copies, or nothing at all with `versioned`). Worlds no player has ever entered are not backed up, and the manifest records that they were skipped. Each world's last time with players is recorded in `buildgit/backup/visits.yml`, and each job compares it with its own last run.
- `session.lock` is always excluded; plus the `exclude` paths.
- Worlds listed in `exclude-worlds` are not backed up at all and are not in the manifest's `worlds`. On restore: separate world folders not in the manifest are untouched anyway; ones inside a world being swapped in (e.g. Fabric build worlds inside `world/dimensions/`) are first moved into the world being swapped in, so they stay as they are (decision #67).
- main's mark journal (`buildgit/worktree/<main>/`) is included too, so after a restore "unsaved changes" are still marked.

**One backup run** (on the backup background thread; all jobs run one after another, never at once):
1. Tell the chat "The server is backing up" (`announce`).
2. Main thread: pause autosave of all worlds and flush all worlds to disk (the same as `save-off`, `save-all flush`).
3. Background: in the worlds to process, files whose size or modification time differs from the previous snapshot are first copied to a local staging area (local copies are fast, so the pause is short). Size and modification time are checked before and after copying, and the file is copied again if they changed (the server can still write files while saving is paused, e.g. when unloading chunks). `….before-restore-…` left renamed by restores is not backed up. Region files (`.mca`, `.mcc`) are only noted, not copied: on a large world's first backup the whole world counts as changed, and copying it all first would need another world's worth of space and pause saving for hours (decision #66).
4. Main thread: resume autosave.
5. Background: changed files are written from the staging area to the destination (8 at once for S3); unchanged files are reused per the storage mode. Only now are region files copied to staging one by one (retried when size or modification time differ before and after, up to 5 times; when one keeps changing it is uploaded anyway with a warning and its time not recorded, so it is sent again next time), uploaded and deleted, with at most 8 in staging at once. So region files in a snapshot may be later than player data; files deleted midway are not listed.
6. Write the manifest `snapshots/<time>.yml`: time, Minecraft data version, the save main pointed at then, each file's "path size SHA-256" (plus version id for `versioned`), and skipped worlds. A local copy is kept (one per job), used next time to tell which files are unchanged.
7. Delete old snapshots beyond `keep` (folders and manifests); snapshot folders without a manifest (failed midway) are cleaned up too.
8. Announce "Backup finished".

**Restoring** (`/bg backup restore <job> [time]`, console only): downloaded (or copied locally) to a place next to the current one first, as in §2.5, and swapped in at the next startup before worlds load; the original world folders are always kept renamed. Before downloading, the file sizes in the manifest are added up, and if the disk cannot hold it (with another 1 GB to spare), it is refused with how much is needed.
- The world folders are replaced entirely with the snapshot's; the repository's branch pointers (`refs/`, `branches.yml`, `data-version`), main's unsaved marks (`worktree/`) and `players.yml` are also replaced with the same point in time, and draft and view worlds are rebuilt from versions. The repository's objects stay local (only adding); when the save a snapshot points at is not present locally, it is fetched from the first history job, and refused when there is none.
- When this server has no repository yet (e.g. a new host), restore history first, restart, then restore the world folders.
- Worlds no player ever entered, which therefore have no backup, are not swapped; a reminder is given after downloading.
- For later: history could be newer than the world. The manifest records the main save the world matched at the time, so after startup the later saves could be written into the world (the same writes as publish and restore), giving "player data back to that day, builds back to the latest save".

**Across versions**: the manifest records the Minecraft data version, and restoring is refused when it is newer than the server. When older, the old-format folders are put back as they are and Minecraft's usual upgrade flow handles them (e.g. 1.21.11's `world/DIM-1` is moved to `world/dimensions/` on 26.x), with a reminder after downloading; BuildGit finds main's world locations as usual after worlds load. Cross-version world folder restores have not been tested yet.

Acceptance: on both Fabric 1.21.11 and Paper 26.3, convert phase 1's `s3.yml` into jobs, back up several times to S3 (hashed), S3 with versioning (versioned; keeping 2 deletes the versions used only by old snapshots) and local (folders; unchanged files are hard links, only 2 kept), uploading only changed files; one idle backup triggers 1 minute after players leave; place a block, back up, place another, and after restoring only the backed-up one remains.

### 2.7 Backup jobs: a strategy per destination

Backups can go to several places at once, each deciding what to back up, how often and how many to keep. For example history to S3 every hour, world folders to S3 daily keeping 7, and every 6 hours to another local disk keeping 4.

**`s3.yml`**: only connections, possibly several, by name.

```yaml
connections:
  default:
    endpoint: https://s3.ap-northeast-1.amazonaws.com
    region: ap-northeast-1
    bucket: my-backups
    path-style: false
    access-key: ...
    secret-key: ...
  cold:                  # another bucket or another provider
    ...
```

**`backup.yml`**: backup jobs, with no keys, so it is safe to show others.

```yaml
jobs:
  history-s3:
    what: history              # history (§2.5) or world (world folders, §2.6)
    target: s3:default         # s3:<connection name>, or local:<folder> (relative to the server root, or absolute)
    path: buildgit             # the place inside the destination (the S3 prefix)
    schedule: "0 * * * *"      # cron: minute hour day month weekday; supports *, numbers, ranges a-b, lists a,b, steps */n
    keep: 48
  world-s3:
    what: world
    target: s3:default
    path: buildgit-world
    schedule: "0 4 * * *"
    idle-minutes: 10           # also back up once after the server has had no players for 10 minutes
    keep: 14
    storage: auto
  # world-local:               # example: another local disk
  #   what: world
  #   target: local:/mnt/backup/minecraft
  #   schedule: "0 */6 * * *"
  #   keep: 4
timezone: ""                   # the time zone for cron; empty uses the server's
```

- **Triggers**: `schedule` (cron) and `idle-minutes` (idle) can be set alone or together; at least one is needed.
  - **Idle backups**: the timer starts when the last player leaves, is cancelled if someone joins meanwhile, and runs after `idle-minutes` minutes. It only triggers if a player has been online since this job last ran, so an empty server does not keep backing up all day, and with players coming and going, each "someone played, then it emptied" period is backed up only once. No players are around then, so no chat notice is sent.
  - Idle backups count towards `keep` too; when players log in several times a day, old snapshots are pushed out sooner, so if needed, make a separate job triggered only by idle, each with its own retention.
  - No backup on shutdown (the user's decision): it would slow down shutdown, possibly for a long time on the first full backup.
- Each job can be paused with `enabled: false`. Jobs whose destination is not set up (e.g. `s3.yml` is still the template) do not run; `/bg backup status` shows "Not set up", without spamming the console. The two jobs in the template are on by default, so backups start once S3 is filled in: history every hour; world folders daily at 04:00 plus after the server has been idle for 10 minutes, keeping 14.
- All jobs run one after another on one background thread. When two world folder jobs are due at once, each pauses saving once (simple first; merge them into one pause later if needed).
- Each job has its own local record `buildgit/backup/<job>/` (uploaded commits, file hashes, the previous manifest), so different destinations do not affect each other.
- **Local destinations**: history is written as files with §2.5's layout (`objects/`, `files/`, `snapshots/`); world folder `folders` reuse unchanged files with hard links, so each snapshot looks complete but only takes the space of the changes. On the same disk this only protects against mistakes and corruption, not disk failure; the manual must say so clearly.
- **Compatibility**: when `backup.yml` does not exist and `s3.yml` is in phase 1's format (no `connections:`), the connection is taken as `default`, and its `prefix`, `interval-minutes` and `keep` make a history job `history-s3` (`path` unchanged, so the remote backups carry on; the interval becomes the matching cron), plus the default world folder job; the local record `buildgit/backup/` moves to `buildgit/backup/history-s3/`. The console says the old keys in `s3.yml` can be removed.

**Commands** (permission `buildgit.backup`):
- `/bg backup status`: each job's destination, schedule, last success time, last error.
- `/bg backup now [job]`, `/bg backup list [job]`, `/bg backup test [job]`: without a name, all jobs. `test` lists, checks versioning (S3), writes a small file, reads it back and compares, then deletes it; `list` and `test` use their own thread, not queued behind uploads.
- `/bg backup restore <job> [snapshot]` (console only), `/bg backup fetch <branch> [new name]` (fetched from the first history job).

## 3. Change tracking

### 3.1 Cause

The core of tracking is "whom the code currently running is on behalf of":

```
Cause { author: player UUID, origin: starting position, kind: direct | knock-on }
```

The tracker keeps "the current cause" in a thread-local variable. While a cause exists:
- written blocks are marked, with the cause's player as author;
- newly scheduled ticks and block events carry the cause;
- newly spawned entities become tracked entities.

Changes that happen without a cause are never marked.

### 3.2 Direct actions: setting the cause

| Source | When the cause is set |
|---|---|
| Vanilla interaction | While handling a player's packets: placing, breaking, using items, interacting with blocks or entities, editing signs, clicking in containers and so on |
| Commands | While a command run by a player executes (`/fill`, `/clone`, `/setblock`, `/summon` and so on) |
| WorldEdit/FAWE | See §3.8. FAWE runs asynchronously and bypasses vanilla writes, so hooks at the write layer alone are not enough |
| Axiom | See §3.9 |
| DebugStickPro | See §3.9.1 |
| Other plugins | The public API (§3.10) |

### 3.3 Knock-on changes: passed along the causal chain

| Knock-on source | How it is passed along |
|---|---|
| Neighbor shape updates (stairs, fences, walls, glass panes, redstone wire) | Happen synchronously while the cause exists, so they are marked naturally |
| Neighbor change notifications (`neighborChanged`) | Same; the queued neighbor updater since 1.19 also finishes within the same call |
| Scheduled ticks (water flow, lava, sand, redstone components, observers) | The cause is recorded when scheduled, in a side table keyed by `(position, type)`; when the tick runs it is taken out and restored as the current cause |
| Block events (pistons, note blocks, chests opening and closing) | Same as scheduled ticks |
| Entities | Entities spawned while a cause exists become tracked and remember the cause. When they tick, their type decides whether the cause is restored (§3.4) |
| Explosions | When the exploding entity is tracked, the explosion runs with its cause |

### 3.4 How far entities pass changes on

A tracked entity's **own state** is always saved on commit, e.g. armor stands, item frames, display entities, minecarts, mobs spawned by players.

Whether its side effects on blocks pass the cause on depends on its type (adjustable in `config.yml`):

| Passed on by default | Not passed on by default |
|---|---|
| Falling blocks (sand, anvils), lit TNT, projectiles shot by players, minecarts, boats | Regular mobs (sheep eating grass, endermen moving blocks, villagers farming) — otherwise long-running mob behavior would keep leaking into versions |

### 3.5 Where it ends and limits

- **Stopping naturally**: once knock-on changes stop, there are no more ticks carrying the cause, and tracking ends by itself.
- **Repeating states**: a redstone clock keeps changing the same blocks; they are already marked, so they are only marked again, and a commit stores the current state.
- **Distance limit**: the cause carries its starting position; knock-on changes farther than `tracking.max-distance` (256 blocks by default) from it are not passed on.
- **Count limit**: when one cause marks more than `tracking.max-blocks-per-cause` (1 million by default) blocks, passing on stops and the player is told. This is a safety net against endless spreading. (Not implemented on Paper yet; for now the spreading time window and distance limit bound it.)

### 3.6 Keeping the marks

- Each working world has a mark table: chunk → section → a 4096-bit bitset, plus each block's author index (not allocated when the whole section has one author), and the UUIDs and authors of tracked entities.
- **Persistence**: changed sections are written as snapshots into the mark journal, flushed every second (on an async thread). After a restart they are replayed in order; a half-written tail is ignored. A commit first switches to a new journal file and only deletes the old one once the commit succeeds; on failure the marks are put back.
- **Known risk on crashes**: the last second of marks may be lost, and those blocks miss the next commit. `status` says "last shutdown was not clean", and `/bg mark <selection>` lets you mark by hand.

### 3.7 Platform hooks

**Shared layer**: the logic for both platforms (recording, saving, branch worlds, commands, UI) lives in `server/`, operating on worlds with vanilla classes (Mojang names); platforms only implement the `Platform` interface (scheduling, creating and unloading worlds, permissions, messages, bossbars, chest menus) and the hooks below. Command wrapping (`CommandHook`) and knock-on attribution (replacing `neighborUpdater`) are shared by both platforms.

**Fabric 26.3/26.2/1.21.11** (implemented; mixins with `defaultRequire = 1`, so if a hook cannot apply the server does not start, rather than silently missing records):
- Cause (MixinExtras `@WrapMethod`, setting the cause for the duration): `destroyBlock`/`useItemOn`/`useItem` in `ServerPlayerGameMode`; container clicks, container buttons (lecterns) and sign edits in `ServerGamePacketListenerImpl`; `Player.attack`, `Player.interactOn` (plus `ArmorStand.interactAt` on 1.21.11).
- Writes: `LevelChunk.setBlockState` (a change only when it returns non-null) is the single entry point, marking when there is a cause; block entity content changes also hook `BlockEntity.setChanged`. So there is no need to list events one by one like on Paper, and no extra neighbors are marked.
- Propagating entities and natural changes: `FallingBlockEntity.tick`, `Projectile.onHit` and `ServerExplosion.explode` set the cause from the author the entity remembers; pistons' `moveBlocks` use the nearest author nearby; `ServerLevel.tickChunk` (random ticks, snowfall, lightning), `FireBlock.tick` and explosions with no author are natural changes, and writes during them join the suppression set.
- Entities: `ServerLevel.addFreshEntity` decides the author; `onTrackingStart`/`onTrackingEnd` in `ServerLevel$EntityCallbacks` correspond to loading and removal (unloading does not count as removal). TNT removes itself before exploding, so a propagating entity's author is only forgotten the tick after it is removed.
- Inspect mode and read-only views: `handleBlockBreakAction` (left click) and `useItemOn` (right click, also blocking the offhand) are intercepted at the start; blocked left clicks send back the original block.
- Branch worlds: Fantasy persistent worlds with dimension `buildgit:<name>` (characters outside `[a-z0-9_-]` in the name are written as `.u<hex>.`), stored in `<save>/dimensions/buildgit/`. Synthesizing reads hook the start of `RegionFileStorage.getRegionFile`: when a region file is first opened and the file does not exist, it is synthesized first; folders are registered before the world is created. On 26.3, `getRegionFile` has an extra parameter and the cached value became `Optional<RegionFile>`, so `@Local(argsOnly = true)` takes only the `ChunkPos` and only checks whether the cache has the key, shared across versions.
- Other: before `Commands.performCommand`, check that all commands are wrapped; `PlayerList.remove` (going offline), `ServerPlayer.triggerDimensionChangeTriggers` (changing worlds); new chunks use Fabric API's `ServerChunkEvents.CHUNK_GENERATE`.
- Repository: on dedicated servers, `buildgit/` in the server directory (same as Paper); in single-player, inside the save folder, one per save. Settings are in `config/buildgit/`.
- The versions differ only in a few APIs (the bossbar constructor, chunk coordinate packing, chest screen click types, Fantasy's Level/World naming, `interactOn` parameters), each in `dev.twme.buildgit.fabric.mc`; 26.2 is not obfuscated and runs directly with Mojang names, while 1.21.11 is remapped to intermediary by Loom. 26.3 and 26.2 use the same version-specific classes (`fabric/mc26_3` compiles `fabric/mc26_2`'s sources directly).

**Paper 26.3/26.2/1.21.11** (implemented; no mixins, so events plus NMS wrapping instead):
- Direct actions: Bukkit/Paper events (all `MONITOR`, read-only), covering CoreProtect's event list plus what Spyglass adds:
  - placing (including multi-block), breaking, buckets, signs, ignition, bone meal, trees grown by players, portals, harvesting, lecterns, cauldrons;
  - interaction: right clicks mark the clicked block, its neighbors and the placement face; container clicks/drags mark by the container's holder (including both halves of double chests, and entity containers).
  - The write layer has no single hook. Knock-on changes to neighbors are judged by the updater below; when an extra marked block's content equals the parent version, the commit produces no change. But if an extra marked block was previously changed by non-players (console commands, natural changes), those changes get included too, so marks should be as precise as possible.
- Commands: every executable node of the server's Brigadier command tree is wrapped by reflection (vanilla, Bukkit and Paper plugin commands share one tree). While a player runs one, the cause is set, and the whole range from coordinate arguments (`from/to`, `begin/end/destination`, `pos`) is marked, extended by one block — `/fill strict` and `/clone strict` send no neighbor updates.
  - Before each command runs, the root's children are compared one by one, and new nodes (registered later by other plugins, datapack reloads) are wrapped.
  - Executors for `/function` and `/return` are recognized by type by the engine and are not wrapped; the commands inside functions are each recorded.
- Knock-on: each `Level`'s `neighborUpdater` (`CollectingNeighborUpdater`) is replaced by reflection with a subclass, which decides the author on shape updates and neighbor notifications:
  1. the current thread has a cause → that author;
  2. otherwise **spreading**: the block or one of its 6 neighbors was marked within `cascade-window` (200 ticks by default), and it is within `max-distance` of the origin → the most recent mark's author.
  - **Only blocks that really changed are marked**: a "source" block that just changed is marked directly; a notified "candidate" block has its state recorded at the time, and once the outermost update finishes (nested updates are queued) it is compared, and only marked if it changed. Otherwise blocks that merely got notified would become spreading sources, and nearby natural changes would be recorded all the way along. Tested: next to a stone placed by a player, grass spreading for 8 seconds at high random tick speed still recorded only 1 block.
  - So event handlers only mark the target block itself; neighbors' shape changes are judged by the updater. Only when a world has no updater hooked does it fall back to marking the 6 neighbors too.
  - This replaces the original "scheduled tick side table" design: delayed changes like water flow, redstone and sand all touch freshly marked neighbors, so `blockTicks` needs no wrapping. The fast path is one volatile read, so with no marking activity it costs almost nothing.
  - **Excluding natural changes**: growth, spreading, freezing, melting, leaf decay, burning, farmland wetting and drying, untracked entities and explosions join the suppression set in the same tick, so spreading does not count them.
  - Pistons, fluid flow (`BlockFromToEvent`), sponges and explosions are additionally recorded through events.
- Entities: `EntitySpawnEvent` decides the author from the cause, recent marks at the spawn position, or the chunk FAWE wrote entities into; projectiles go to the shooter. "Propagating entities" such as falling blocks and TNT remember the author, and block changes when they land or explode go to them.
  - Unloading and changing dimensions: tracked entities are noted when unloaded; tracked entities not in the world at commit time are deferred until they load again, rather than being taken as removed.

### 3.8 WorldEdit/FAWE

CoreProtect's FAWE recording has two problems (confirmed by reading the source):
- it records block by block with an extent wrapper, reading the old block and writing to a database queue for each, so large edits get noticeably slower;
- by default FAWE only allows extents from the `allowed-plugins` list and skips the others, so many FAWE edits are not recorded at all. Spyglass also reads and writes block by block.

BuildGit's approach: record only **positions**, never read the world.
- **FAWE queue mode** (default): on `EditSessionEvent` (`BEFORE_CHANGE`), a batch processor `FaweRecorder` is added to the edit's queue (the same hook FAWE's own history uses). Each time FAWE finishes a chunk's change set, the processor scans each section's `char[]`, turns the non-zero blocks into a bitset, and **marks the whole section at once**; block entities, entity removals and new entities are marked too.
- **FAWE non-queue mode and vanilla WorldEdit**: a `MarkingExtent` wrapper marks block by block (positions only), and at runtime the package name is added to FAWE's `allowed-plugins` (in memory only).
- Author: when the editor is a player, that player; with no player (other plugins calling WorldEdit through its API), the current thread's cause (`attribute` in §3.10).
- **Fabric**: when the WorldEdit mod is installed, the same `EditSessionEvent` and `MarkingExtent` are hooked (Fabric has no FAWE), and selections are read from WorldEdit too. How a WorldEdit world converts back to a vanilla world differs by version (`FabricAdapter.adapt` on 7.4.2/Minecraft 1.21.11, through core-mc's `FabricAdapter.get().toNativeWorld` from 7.4.5), in each version's own `WorldEditWorlds`. It depends on WorldEdit only at compile time and does not bundle it.
- Tested (Paper 26.2, FAWE 2.15.4, `setBlocks` of 2.56 million blocks): 123–212 ms with the processor, 127–187 ms without, the difference within measurement noise; the mark count exactly matches the actual changes.

### 3.9 Axiom

- Registered through AxiomPaper's `Integration.registerCustomIntegration`: `checkSection` marks the whole section and returns "all allowed"; `canPlaceBlock`/`canBreakBlock` mark the block and its neighbors.
- Entities: AxiomPaper only touches entities through three packets, spawn, modify and delete (pasting entities from the clipboard also goes through spawn), and all three have events. Events carry only one entity, but spawning adds passengers along with it, and modifying can mount or dismount passengers, so the whole stack (the outermost vehicle and all passengers) is recorded; for modify, the stacks before and after are both recorded.
- **Players with the bypass permission** (`axiomadmin.bypass_region_checks`, not granted by default, not even to OPs): AxiomPaper asks no integration about them and offers no public hook, so AxiomPaper's internals are read (decision #51):
  - Block buffers (brushes, pastes, fills and so on): AxiomPaper's operation queue is replaced with a BuildGit subclass, which wraps these players' block buffer operations when they are added, marking the sections in the buffer once before the first run and once after completion. The queue runs on the main thread and executes at the moment of adding when empty, so wrapping can only happen on add, not by inspecting the queue later; an operation may span several ticks waiting for chunks to load, so marking again after completion means nothing is missed if a save happens midway.
  - Single block placement: the `axiom:set_block` channel is also listened to, reading the position table at the start of the packet (VarInt count, each entry a BlockPos and block id), marking the block and its neighbors.
  - The biome brush cannot be recorded.
  - Depends on AxiomPaper's internal structure, with nothing to configure: when replacing the queue fails, or edit contents later cannot be understood, it turns itself off with a console warning, without affecting Axiom's own edits. When BuildGit is disabled the original queue is put back.
  - Such players get a notice when they turn Axiom on: when recording works, that biomes are not recorded; when turned off, that block edits may not be recorded.
  - Tested (Paper 26.2, AxiomPaper 6.0.1): a 1200-block buffer run through AxiomPaper's own queue and one single-block placement packet were both recorded.
- **Fabric**: the server runs the Axiom mod itself, which has no integration API, so mixins hook its server packets (applied only when Axiom is installed, decision #54). All players are recorded this way, with no bypass distinction:
  - While world-changing packets are handled (block buffers, single block placement, spawning/modifying/deleting entities, region fixes, block ticks), the player is set as the cause: changes through `setBlockState`, newly added entities and knock-on changes all go to them.
  - Block buffers write straight into sections without `setBlockState`: the blocks written in the buffer are marked before writing (synchronously, so no save happens before writing finishes).
  - Biome buffers: one block marked per biome cell.
  - Single block placement: each block and its neighbors.
  - Modifying and deleting entities: the whole stack (outermost vehicle and all passengers), once before and once after.
  - Depends on Axiom's internal classes: checked at startup, and turned off with a console warning when they do not match, without affecting Axiom itself.
  - Only positions are recorded, and contents are read from the world at save time, so this is unrelated to cross-version history.
  - Tested (Fabric 26.2, 26.3, 1.21.11, Axiom 6.1.3): with a fake player on the server directly handling Axiom's block buffer, single block placement and entity deletion packets, all were recorded under that player and could be saved in a draft.

### 3.9.1 The debug stick and DebugStickPro

- **The vanilla debug stick**: right-click state changes go through the usual "use item" flow (on Paper the interaction event marks the clicked block; on Fabric the cause is set during `useItemOn`), so they are recorded; left click only picks a property and does not change the block.
- **DebugStickPro** (Paper, `integrations.debugstickpro`): classic and copy modes change blocks with `setBlockData(…, false)`, sending no neighbor updates, and also cancel the interaction events they trigger, so the usual hooks do not see them; instead its `ClassicModeChangingBlockEvent` and `CopyModePasteBlockDataEvent` (MONITOR, not cancelled) mark that block. Compile-time signatures are in `paper/debugstickpro-api` (Apache-2.0, not bundled).
- **Freeze mode**: DebugStickPro temporarily replaces the real block with a barrier and sends players fake block packets, swapping it back on unfreeze (including when the player goes offline or the plugin is disabled, which have no events). At save time `FreezeBlockManager.isFreezeBlock` is asked, and frozen blocks stay in the mark table (`Tracker.held`) to be saved once swapped back, so barriers never get into versions.

### 3.9.2 Biomes

Biomes can only be changed by commands or tools (`/fillbiome`, WorldEdit/FAWE's `//setbiome`, Axiom's biome brush), with no natural changes, so they are not tracked separately:

- **At save time**, each section with marks compares all 64 biome cells (working world, the parent version's overlay, the genesis world), writing only the differing ones into the overlay; ones changed back to the genesis are removed. The author is that of the first marked block in the section.
- **Every source marks the section it is in**: `/fillbiome` is marked through the command hook's `from`/`to` range (§3.2); WorldEdit's `setBiome` records that block; FAWE's batch processor records one block in sections with biome writes; Axiom's biome brush asks `canPlaceBlock` for every block on Paper, and on Fabric the biome buffer hook records one block per cell (§3.9). Biomes changed by other plugins through APIs cannot be recorded; use `/bg mark` to mark them.
- **Writing back to the world** (publish, revert, restore, new chunks without a genesis): the biome container is written directly, then biome update packets are sent to players who can see it; unknown biomes (from removed datapacks) are skipped. When synthesizing branch worlds the section's `biomes` palette is rewritten, and chunks with no block changes keep their lighting and heightmaps.
- **Merging**: a three-way merge per biome cell. When both sides changed it to different values, the target's content is kept and the count listed in the publish preview, but it does not count as a conflict and does not go into conflict resolution (decision #46). For partial publishes and area restores, biome cells overlapping the area count.
- **Display**: `diff` and the publish preview outline changed biome cells with 4×4×4 cyan boxes, and ones changed on both sides in purple.
- **Across versions**: biome IDs in the overlay follow the data version at write time, and are upgraded on read with DataFixer (`References.BIOME`) (§4.5).

### 3.10 Public API

The `api/` module (`dev.twme.buildgit.api`, depending only on the Bukkit API) provides `BuildGitApi` through `ServicesManager`:

```java
var recorder = BuildGitApi.get().recorder();
recorder.recordBlock(Actor.of(player), block);              // one block
recorder.recordBox(actor, world, x1, y1, z1, x2, y2, z2);   // a box
recorder.recordSection(actor, world, cx, sy, cz, bits);     // a whole section's bitset, the fastest
recorder.recordEntity(actor, entity);
try (var scope = recorder.attribute(actor)) {               // knock-on changes, commands and WorldEdit edits during this go to actor
  block.setType(Material.STONE);
}
recorder.pendingAuthor(block);                               // the author not saved yet
```

- All methods only record positions and never read the world, so they can be called from any thread (except `attribute`: main thread only).
- Vanilla commands dispatched while a command is running are queued until the outer command ends; in that case dispatch them on the next tick.

### 3.11 Known limitations

- Axiom players with the bypass permission: the biome brush is not recorded; when AxiomPaper changes and its internals no longer match, block edits are not recorded either (§3.9); such players get a notice when they turn Axiom on.
- Large structures placed by the world generator, such as with `/place feature`, are only recorded within the neighbor updates they trigger, possibly incompletely; use `/bg mark` to mark them.
- Entity removals not caused by players (e.g. an armor stand blown up by a creeper) are not recorded, the same as natural block changes; the version keeps it while the world no longer has it. Entities touched by players, or removed during a player cause (commands), are recorded.
- When the server crashes, marks after the last flush (1 second by default) may be lost (§3.6).
- Biomes: when both sides changed the same biome cell, the target's content is kept (§3.9.2); `/bg pull` and `/bg push` do not carry biomes (§6.7); `inspect` does not show biome authors.
- The Fabric version has no public API (§3.10 depends on Bukkit), and no DebugStickPro yet.
- Fabric permissions go through fabric-permissions-api: without a permissions mod, permission level 2.

## 4. Branch worlds

### 4.1 Creating

`/bg try [name]` (before decision #63 there was also a non-teleporting `branch create`):
1. Write the ref (pointing at the current branch's latest commit).
2. Create empty working world folders for the worlds the draft belongs to:
   - copying the seed, generator settings and datapack list from `level.dat`;
   - **copying no region files**.
3. The world loads only when the first player enters:
   - Paper uses `WorldCreator` (`copy` of the source world; superflat worlds also carry the layer settings, which `copy` does not);
   - Fabric uses a runtime world library (e.g. Fantasy), which also works in single-player.

**A draft belongs to one world only** (decision #50): a draft opened by `try` belongs to the world the player is in (the dimension; the Nether, the End and multiworld plugin worlds are each separate), recorded as `dimension` in `branches.yml`. Created from the console, it uses the source draft's world, or the default world when the source is main.
- A draft only opens this world; completion for `try`/`go`, the `/bg` menu and `branch list` only list drafts of the player's world (`branch list --all` lists all). `go` by name to a draft in another world still works.
- Reverting and restoring only touch the draft's own world: the draft's history also has saves of main's other worlds, and reverting or restoring it whole would change the other worlds' content too, which would then be carried into main on publish.
- Drafts of different worlds cannot merge with each other; main contains all worlds and can merge with any draft. Conflict resolution worlds only open the draft's world.
- main is still all versioned worlds. Drafts created before this setting have no `dimension` and still cover all worlds.

### 4.2 Lazy copy: synthesizing reads

When a working world reads a chunk that is not in its own region files, it **synthesizes** the chunk:

```
read chunk (x, z):
  in its own region file → read as usual
  otherwise:
    in the genesis world → read the genesis chunk NBT, apply the branch's latest commit's overrides for this chunk, hand it to the server
    not in the genesis world either → let the generator produce it; once generated, apply the overrides
```

The first time a synthesized chunk is saved, it is written into the working world's own region file and is an ordinary chunk from then on.

The rule: **a chunk not yet read always equals the branch's latest commit's state for that chunk**. Merges and restores only need to handle chunks already read; the rest naturally have the new state when read later.

Hooks:
- Fabric: a mixin on chunk storage reads (`ChunkStorage.read`/the IO worker).
- Paper: wrap the chunk system's region storage reads by reflection. This is the biggest uncertainty (§10 risk 2). The fallback is "synthesize whole regions in advance": before a player gets near a region, write the whole region's 32×32 chunks into a region file; on teleport, finish synthesizing first, then teleport.

**The Paper 26.2 implementation (verified)**: "synthesize whole regions" was adopted, but the timing is **the moment the server first opens that region file**, with no need to predict player positions:
- Every time `RegionFileStorage` gets a region file, it first checks `regionCache` (`Long2ObjectLinkedOpenHashMap`) while holding the storage lock. `ChunkDataController` and the read methods are final, so instead this field is replaced with a subclass by reflection at `WorldInitEvent`: on a cache miss where the file also does not exist, the whole region is synthesized first (genesis region + overrides from the branch's latest commit), then handed back for Paper to open as usual. One each for blocks (`region/`) and entities (`entities/`).
- Synthesized chunks have `isLightOn` and `Heightmaps` removed so Paper recomputes lighting and heightmaps on load; changed sections have their light arrays removed, and block entities get their coordinates filled back in. Chunks without overrides are copied byte for byte.
- Written to `.buildgit-tmp/` first, then moved atomically, so the server never reads a half-written file.
- Chunks not in the genesis world, only in overrides: the generator produces them, and at `ChunkLoadEvent.isNewChunk()` the overrides are applied with writes that trigger no updates (system writes, not marked).
- Synthesizing only happens when the region file does not exist, so later saves and restarts of the branch world are just like an ordinary world.

### 4.3 The main branch

`main`'s working world is the server's original world, with no synthesizing needed. On `init` (the world the player is in; the default world from the console):
1. Save only this world (the same as one autosave), and start recording immediately. The main thread only hands chunks to the IO thread without waiting for the writes (Paper; decision #48); waiting for the writes happens on the copy thread, and only then does copying start;
2. The first world also creates the repository and the first commit (with an empty overlay);
3. A background thread copies region and entities chunk by chunk into `genesis/<dimension>/`, without pausing the server, and other worlds are not affected.

Why the background copy is correct: after the save, every chunk on disk is as it was at init until the server writes it again, and the server only writes chunks that were loaded. So chunks already loaded at init, and chunks that load during copying, jump the queue and are copied first (before they are changed and saved back), and the rest are copied in order; new chunks generated during copying are written as snapshots. Chunks already in the genesis world are never overwritten.

Before copying finishes: this world's marks stay in the mark table without being saved (the genesis world cannot be compared yet), and no drafts, view worlds or `pull` are opened for it. `genesis/<dimension>/copying` means copying is not finished; if the server stops midway, the next startup finishes it (existing chunks skipped; chunks changed and saved back during that time take their state then as the genesis, with a warning logged).

This is the only full copy in each world's whole lifetime.

**Where the genesis world lives** (`init.genesis-dir`; decision #53): `buildgit/genesis` by default, can be set to another disk. The location in use is recorded in `buildgit/genesis-dir` (the configured string; relative paths are relative to the server directory, so it stays valid when the whole server directory moves). At startup, if the configured location does not exist but the previously recorded one does, the data has not moved yet, so the old location keeps being used with a warning; otherwise every versioned world would look uninitialized. The location is only decided at startup; `/bg reload` does not change it.

**Lazy copy** (`init.lazy-copy-above-mb`, 512 MB by default; decisions #52, #62): when the world save (region and entities) is bigger than the setting, init does not copy the whole world; after the save is written it copies only the loaded chunks, and from then on each chunk is copied the first time it loads (before it is changed and saved back); new chunks are added as snapshots as usual. `genesis/<dimension>/lazy` records main's world save directory (relative to the server directory). When reading the genesis world (`AnvilSource`, `RegionSynthesizer`), chunks missing from the genesis world are read from main's world save instead: these chunks have not loaded since init, the server has not written them, so they are still as they were at init. When a chunk's blocks are already in the genesis world, its entities also follow the genesis world (none means no entities), no longer reading entities from main's world save.

Correctness rests entirely on "the server only writes loaded chunks, and all loading goes through BuildGit", so opening this world without BuildGit running (plugin removed, single-player, `--forceUpgrade`), changing the save with offline tools, or restoring the world folder and `buildgit/` separately all silently change the genesis of never-loaded chunks, undetectably. Upgrading Minecraft is fine: the new version copies the original bytes before loading an old chunk, and reads upgrade it with DataFixer as usual. Because the cost is only worth it for large worlds, it is off by default (for small worlds), and admins decide by world size.

### 4.4 Entering and leaving branches

`/bg go <branch>` teleports the player to the **same coordinates and dimension** in that branch's working world, with inventory and game mode unchanged.

When a player goes offline in a draft or resolution world (or the server shuts down), BuildGit records the branch, dimension and position in `buildgit/players.yml`; on their next login that draft is opened again and they are sent back to the same place. The draft world may have been unloaded as idle or closed with the server, so the server cannot find it by itself, and BuildGit handles it. When the draft has been deleted, the player stays where the server put them. Going offline in main clears the record.

Paper world names are in §2.4, created with `WorldCreator.copy(main world)` (same seed and generator). The new world's spawn point reuses main's directly and is marked initialized, skipping the server's spawn search: the search synchronously loads (synthesizes) chunks around the spawn on the main thread, and opening a draft once stalled for about 3 seconds because of it (decision #48). Fabric creates worlds with Fantasy, which never searches. Each branch's source and creation time are recorded in `buildgit/branches.yml`. Each branch has its own mark table and mark journal (`worktree/<branch>/`); the tracker finds the table by world and maps the branch world back to main's dimension id, so overlay paths are the same on every branch.

**Idle unloading** (decision #34): draft and resolution branches' worlds load only when needed. After none of a branch's worlds have had players for longer than `worlds.unload-idle-seconds` (60 seconds by default, at least 30, -1 disables):
1. The worlds are saved and unloaded (`Bukkit.unloadWorld(world, true)`), with region files left on disk;
2. The workspace is closed, and unsaved marks are written to the mark journal;
3. It opens again the next time `go`, `try`, publish, resolution and so on need it: the region files exist so nothing is synthesized again, and marks are restored from the journal.

Branches that are saving or publishing (holding the save lock) are not unloaded; the timer restarts each time a branch opens, so a world is not unloaded while a player is still teleporting in. View worlds are deleted as soon as the last player leaves (§6.5).

### 4.5 Cross-version history

After the server upgrades Minecraft, old saves and the genesis world are still in the old format (renamed blocks, changed NBT structures of block entities and entities). BuildGit does the same as Minecraft loading an old world: **upgrade with vanilla's DataFixer on read, never rewriting the objects themselves**.

- **Recording the version**: overlay objects (sections, entities) in encoding version 2 store an extra data version (`DataVersion`), and biome IDs in sections are upgraded by this version too. Version 1 objects have no version and follow `legacy` in `buildgit/data-version` (written with the version at the time when a pre-upgrade repo is first opened). Genesis world chunks already carry `DataVersion`.
- **Upgrading on read**: block states (cached by "version + state"), block entities and entities with a data version older than the server, and genesis chunks, are upgraded before use. When synthesizing branch worlds, chunks with overrides are upgraded first and then have overrides applied; chunks without overrides are copied as is and upgraded by the server itself when it loads them.
- **Chunks are upgraded the same way as the server does**: block chunk upgrades pass in the content of vanilla's `ChunkMap.getChunkDataFixContextTag` (dimension, terrain generator; on Paper the dimension is the world's dimension type, plus `level_identifier`), so pre-1.18 overworld chunks get the new height the same way as when the server loads them itself. When the world is not loaded, only the dimension is passed.
- **Rewriting the genesis world** (`/bg genesis upgrade`, decision #55): chunks in the genesis world saved by older versions are upgraded and written back, saving the upgrade on every later read. The content does not change, so overlays and hashes stay untouched, and old saves can still be viewed and restored.
  - Runs region file by region file on the genesis world's writer thread (in order with new chunks being added to the genesis world and lazy copying), without pausing the server; refused while the genesis world is still being copied (init).
  - Each file is written fully in a staging directory and then swapped in at once: after a crash it is either the old file or the new one. Readers that still have the old file open at the swap read old content, which is upgraded on read as usual, so the two are equivalent. Files with no old chunks are left alone.
  - Chunks saved before 1.13 (whose old structure data needs separate conversion by the server) are kept as they are.
  - Lazily copied dimensions: chunks loaded for the first time later are still copied in as they are on disk, possibly in an old version; run it again when needed.
  - Acceptance: Fabric 26.2 (a genesis world made on 1.21.11, 898 chunks) and Paper 26.3 (a genesis world made on 26.2, with entities and a world made by a multiworld plugin, 1758 chunks total) are both rewritten to the current version; drafts opened before and after the rewrite have the same blocks, and history and status are unchanged; running it again finds nothing to rewrite.
- **Hashes are not affected**: old objects are not rewritten, so old saves keep their hashes; blocks with the same content after upgrading are written in the new form in new saves, and comparisons use the upgraded content.
- **No downgrades**: `newest` in `data-version` records the newest version that has written this repo. When the server is older, opening the repo is refused (an older version cannot understand newer blocks and NBT), and a newer server is suggested.
- **How block states are written**: 26.3 (from data version 5006) renamed the block state fields `Name`/`Properties` to `id`/`properties`, and default states in region file palettes store only the block ID (wrapped as `{"": ID}` when mixed). Internally BuildGit always uses `name[property=value,…]` (listing all properties); reading and writing region file palettes is left to the server, converting with vanilla's `BlockState.CODEC` (`Upgrader.paletteState`/`paletteEntry`), so whatever version the server is, that version's form is read and written. When upgrading old block states, the DataFixer input picks field names by the version at write time, and the output is read with `BlockState.CODEC` too.
- Acceptance: a repo made on 1.21.11 (with block entities, entities and unpublished drafts) moved to 26.2 has `view`, `go`, `diff`, `publish` and `restore` all working, with chest contents kept. 1.21.11 and 26.2 repos moved to 26.3 (Paper and Fabric) have non-default block states (stair facing, top/bottom half, chest facing) and chest contents correct in draft worlds, and can be published and viewed.

## 5. Commits, merges, restores

### 5.1 Commits

`/bg save [message]` (the same path as autosave):
1. Lock the branch, so only one commit or merge runs at a time.
2. Atomically take the current mark table and put a new empty one in its place. Players keep building during the commit, and new marks go into the next commit.
3. For each marked chunk, read the current content of the marked blocks:
   - chunk loaded: read on the thread that owns the chunk;
   - not loaded: read the region file directly.
4. Compare with the parent commit's overlay and work out the changeset:
   - blocks whose content equals the parent version are dropped;
   - blocks whose content equals the genesis world are removed from the overlay (back to "same as genesis").
5. Write the objects and update the ref.

The mark table only grows and is only cleared by a commit, so when several people build at once, everyone's changes go into the same commit, each block keeping its own author.

### 5.2 Three-way merge

`/bg merge <source branch>`: merge the source branch into the branch you are on.

```
base = common ancestor; ours = target branch's latest commit; theirs = source branch's latest commit
short-circuit level by level on tree hashes, only expanding sections changed on both sides:
  for each block:
    ours == theirs      → ours
    ours == base        → theirs
    theirs == base      → ours
    otherwise           → conflict
entities: the same comparison by UUID
```

- **Precondition**: the target branch must have no uncommitted marks; otherwise a commit is required first (or `--auto-commit`).
- **No conflicts**:
  1. create the merge commit;
  2. write the changes into chunks of the target working world that are loaded or have been saved, with writes that trigger no updates; chunks never read need nothing (§4.2);
  3. tracking is off during the writes, so system writes are not taken as player changes.
- **Applying on Paper**: diff the target branch's tree before the merge against the merge result, then load each chunk with `getChunkAtAsync` and write it (blocks whose result is "same as genesis" have their content read back from the genesis world). Writes are spread over ticks, at most 20 ms per tick, continuing next tick when unfinished, so large publishes do not stall the server; each distinct block state is parsed only once (decision #48); entities are added and removed only once the chunk's entities have loaded. The current `publish` autosaves the draft and the target first, instead of "requiring a commit first".
- **Written as is, shapes not fixed up**: block states written into the world are exactly as in the draft, without recomputing connections from the target world's neighbors (fences, walls, glass panes, stairs and so on; decision #49). States builders made deliberately with the debug stick are therefore kept; the occasional wrong connection at a seam is for players to adjust. Writes when picking a side in conflict resolution (§5.3), reverts and restores are the same, all spread over ticks.

### 5.3 Conflicts: comparison worlds

When there are conflicts, three working worlds are created, with exactly the same coordinates as the target world:

| World | Content | Editable |
|---|---|---|
| ours | the target branch's latest commit | read-only |
| theirs | the source branch's latest commit | read-only |
| resolution world | the automatic merge result; conflicting blocks start with ours' content | editable |

All three are built with §4.2's synthesizing reads, without copying files.

| Command | Effect |
|---|---|
| `/bg merge view ours\|theirs\|result` | Teleport between the three worlds at the same coordinates |
| `/bg merge conflicts` | List conflict areas: adjacent conflicting blocks grouped, with bounding boxes and coordinates |
| `/bg merge take ours\|theirs [here\|<selection>\|all]` | Quickly set conflicting blocks to one side |
| `/bg merge continue [confirm]` | Create the merge commit from the resolution world's content, taking the conflicting blocks and any extra blocks players changed in the resolution world; then write into the target world and delete the three temporary worlds. When there are untouched conflicts (not changed, nor a side picked with take), it first lists how many, and only finishes anyway with `confirm`. The resolution world's bossbar shows "X/Y conflicts left" |
| `/bg merge abort` | Give up the merge and delete the temporary worlds |

- Conflicting blocks are shown with outlines only that player can see:
  - Paper: glowing block display entities, but **sent only to that player as packets** (NMS: a `Display.BlockDisplay` built in memory and never added to the world generates the spawn and data packets, sent as one bundle). These entities do not exist on the server, so they take no entity count, are never tracked or saved, and do not depend on chunks being loaded; data field ids are produced by the game, so they do not shift between versions. Beyond 400, they are merged into bounding boxes per section;
  - Fabric: the server sends a custom payload, rendered when the client mod is installed, falling back to display entity packets otherwise.
- The state of a merge in progress is kept in `merge/<id>.yml`, so it can continue after a server restart.

**The Paper implementation** (decision #30):
- The resolution world is a temporary branch `merge-<id>` (marked `kind: merge` in `branches.yml`, not shown in the draft list): its starting point is a commit of the automatic merge result, so like a draft it is synthesized, tracked, autosaved and continues after restarts. ours/theirs are two read-only view worlds (§6.5), where players' changes are always cancelled.
- `take`: writes the conflicting blocks' content in the range (one side; read from the genesis world when it is "same as genesis") into the resolution world, recorded under the person who ran the command. `here` is 8 blocks around the player; the console can only `take ours|theirs all [<id>]`.
- `continue`: first saves the resolution branch and the target branch, then merges with `mergeTrees(ours, target latest, resolution latest)`. New saves on the target branch during resolution are fine; when they conflict with the resolution result, finishing is refused and the resolution is kept. Who changed which block during resolution (`authorsSince`) goes into the merge commit.
- Publishing a whole draft produces a merge commit with two parents; partial publishes and reverts produce single-parent commits.
- After finishing or `abort`, the temporary branch, its world and the view worlds are deleted, and players inside are sent back to the target branch.

### 5.4 Restoring

| Command | Effect |
|---|---|
| `/bg revert <commit>` | Undo one commit by applying its reverse changes with a three-way merge, producing a new commit; conflicts go through §5.3 |
| `/bg restore <commit> [selection]` | Bring the current branch (or a given area) back to that commit's state, producing a new commit. History is not rewritten |

This design has no history-rewriting `reset --hard`.

- `revert` is `mergeTrees(bad commit, current latest, the bad commit's first parent)`, committed with a `Reverts` trailer; later changes touching the same blocks are conflicts, opening §5.3's resolution (kind `REVERT`).
- `restore` replaces the overlay within the range (everything when no range is given) with the target version's content, with a `Restored-From` trailer; it cannot conflict, so it lists the impact first and asks for a confirming click.
- Both autosave the current branch first, then update the world with §5.2's write flow.

## 6. The builder layer

§4 and §5's branches, commits and merges are the underlying layer. Builders use only this layer day to day; the low-level commands stay for experienced users.

### 6.1 Terms

| UI term (en_us) | zh_tw | Underlying concept |
|---|---|---|
| draft | 草稿 | branch |
| save | 存檔 | commit |
| milestone | 里程碑 | manual commit |
| autosave | 自動存檔 | automatic commit (commit marked `auto`) |
| publish | 併回 | merge back into the source branch |
| main | 主世界 | the `main` branch |

### 6.2 The three-step flow

| Command | Effect |
|---|---|
| `/bg try <name>` | 1. Autosave the current branch so the draft has the latest progress; 2. open a draft from it; 3. teleport the player to the same place in the draft world |
| `/bg publish [--selection]` | 1. Autosave the draft; 2. preview the publish result: number of changed blocks, authors, and outlines in the source world where things will change (other players in the same draft get the same preview and outlines, so they can discuss it together; the confirm button is only for the person who ran it); when changes are in two or more areas (connected chunks, diagonals included, count as one area), each area is listed with its blocks, chunk count and extent, and a click (`/bg publish goto <number>`) teleports to that area in the draft, so small changes far away are not missed because outlines only show nearby; 3. only clicking **[Confirm publish]** in the chat message publishes; the **[Cancel]** next to it (`/bg publish cancel`) does not, and clears the preview outlines seen by the person who ran it and the players in the draft; 4. teleport back to the source world |
| `/bg back` | Go straight back to the same place in the source world without publishing |

- A draft remembers its "source branch", and `publish` merges back there.
- After publishing the draft is kept and can be changed further; the chat message comes with a **[Delete draft]** button.
- Conflicts go into §5.3's comparison world flow.

### 6.3 Autosave and milestones

- When a branch has unsaved marks and **every player in that branch's world has stopped** for `autosave.idle` (5 minutes by default), it saves automatically.
- There is also a longest interval `autosave.max-interval` (30 minutes by default), so it still saves when someone keeps building.
- `try`, `publish` and server shutdown always autosave first.
- `/bg save <message>` is a milestone.
- History shows only milestones by default, with autosaves folded under milestones, expandable.

### 6.4 Selections

Selections are taken, in order, from: the WorldEdit/FAWE selection → `/bg sel pos1|pos2`. Axiom's selection exists only on the client, which the server cannot get, so it is not supported; Axiom users can use `/bg sel`. A selection only applies in its own dimension (a selection made in the overworld does not affect the same coordinates in the Nether).

| Usage | Effect |
|---|---|
| `/bg publish --selection` | Publish only changes inside the range. Implemented as a single-parent commit on the source branch whose content is the three-way application of "the draft's changes inside the range", recording the source draft; not a merge commit. When the whole draft is published later, those blocks are already the same on both sides and do not conflict |
| `/bg restore <version> --selection` | Restore only the range to that version, producing a new save |
| `/bg merge take ours\|theirs --selection` | Decide a whole block of conflicts at once during resolution |

### 6.5 Looking into the past

`/bg view <version>`:
1. Open a read-only world with §4.2's synthesizing reads, with that version's content;
2. teleport the player to the same place;
3. unload and delete it after the last player leaves.

Things from the old version can be copied back to the working world with WorldEdit's clipboard, and pasted blocks are marked as usual.

`/bg play [start] [end] [--all]`: play back history while the player walks around watching.
1. Follow each save's first parent back along the current branch and pick the saves in range: start and end can be save ids or times (how long ago, `30m`/`12h`/`3d`, or a date `MM-dd`, `yyyy-MM-dd`, optionally followed by `/HH:mm`; an end with only a date counts to the end of that day). The start is how things looked before playback: the given save, the last save before the start time, or the beginning of the branch. Autosaves are skipped by default (their changes count towards the next milestone), `--all` plays all of them; the last save is always played; saves with no content change are skipped.
2. Each playback opens its own read-only world (not shared with `/bg view`), synthesized from the start, and the player is teleported to the same place.
3. Each step writes the difference between two versions into loaded chunks (the same writes as `restore`); stepping back or jumping to any step is the same thing. Chunks generated later are synthesized at the version currently played. Unloaded chunks are stored in the world folder and are not synthesized again, so the version each chunk shows is recorded, and the difference is applied when it loads again (on the next tick).
4. One step every 2 seconds by default, stopping at the end; the bossbar shows the step, save, time, author and message. `pause`, `resume` (from the start once finished), `next`, `prev`, `goto <step>`, `speed <seconds>` (1–60), `stop` (same as `/bg back`). Leaving the playback world ends it, and the world is deleted under the view world rules.

### 6.6 UI

- **Draft bossbar**: when not in main, always shows "Draft: <name> | <n> blocks unsaved", colored per draft; resolution and view worlds each have their own bossbar (conflict count, read-only notice).
- **Progress bossbar**: while slow actions (`init`, saving, publish preview and confirmation, starting and finishing conflict resolution, `revert`, `restore`) run, the bossbar of the player who ran it temporarily shows progress, updated every second, and goes back to the usual notice when it ends (success or failure). Stages with a known total show a percentage: `init` counts region files, reading chunks for a save counts chunks, writing into the world counts chunks and entities; stages without one (working out the merge, writing versions) only show elapsed seconds. Not shown when run from the console.
- **`/bg branch list`**: lists the players inside after each draft.
- **Before/after toggle** (`/bg diff flip`, a button next to the `diff` result): `diff` also works out the before-content of each changed block in the player's world (for unsaved changes, the current save; for `diff <save>`, the previous version; for `diff <a> <b>`, `a`), and toggling sends only this player fake block updates, leaving the world unchanged; toggling again resends the real blocks in the world. A new `diff` or `diff off` toggles back first. Over 50,000 blocks it does not toggle (too many packets); use `view` instead. Only block states are swapped, not block entity contents or entities; when the player clicks a fake block, or the chunk is resent to the player, the client naturally goes back to the real one.
- **Inspect tool**: `/bg inspect` toggles inspect mode, similar to CoreProtect's `/co i`.
  - Clicking a block shows the last author, save and time.
  - Blocks not saved yet show the author in the marks, and "unsaved".
- **Chest menus**: `/bg` without arguments opens the main menu, with common actions (status, save, history, outline differences, inspect, who is where, selection, publish/back; in resolution worlds these become finish resolution and the conflict list), the draft list and resolutions in progress. The layout balances cursor distance and grouping (5 rows): when a container opens the cursor is at the center of the window, which is the middle of the bottom row of a 5-row chest. The bottom row is split into three groups separated by glass panes: history on the left (inspect, history), this world's work in the middle (outline changes, status, save, with status under the cursor), players and areas on the right (who is where, selection). The row above is a glass divider, with context actions set in its middle right above status: back and publish in drafts, the conflict list and finish resolution in resolution worlds. Each action's position is fixed, and without permission the slot has no button. The top three rows are the draft list, starting from the top left; each draft's description lists the last edit time (the last save, autosaves included) and the three latest milestones, newest first and fading; when they do not fit, the last slot says how many more there are and lists them all.
  - One click runs the matching subcommand as the player, so permissions and behavior are exactly the same as typing the command.
  - The Fabric version uses server-side chest screens, so vanilla clients work too.

### 6.7 Taking an area to single-player and sending it back to the server

For people who want to change builds on their own computer (no lag, shaders, single-player tools). The client needs the Fabric version of BuildGit; the server can be Paper or Fabric. It works on one area at a time, not moving the whole world.

1. **`/bg pull [name]`** (on the server): autosave first, take all blocks and build entities within the range from the current branch's latest save, pack them into an area file, and send it to the client over the `buildgit:sync` channel, saved as `.minecraft/buildgit/sync/<name>.bgpack`. The server records the branch, save, dimension and range of this pull (`buildgit/sync/<player UUID>/<name>.yml`).
   - Range: the selection if there is one; otherwise, in a draft, the extent of the blocks the draft changed in this dimension since it branched off main, plus 8 blocks; in main, or with no changes, `sync.radius` blocks horizontally around the player, full height. When the draft's changed extent exceeds the limit, the player is asked to make a selection first.
   - Name: without one, the draft name (the player name in main), adding `-2`, `-3`… when this player has pulled it before, so an area half-changed on the client is never overwritten.
2. **`/bg load <name>`** (single-player): place the area at the same coordinates in the current world: blocks are overwritten as a whole, and non-player entities in the range are replaced with the ones in the area file. No `/bg init` needed first.
   - The pull-finished message comes with an **[Open in single-player]** button, running the client command `/bgc open <name>`: it leaves a marker next to the area file (area name, world folder `buildgit-<name>`, the player's position and view direction on the server), leaves the server and opens that world; when there is none, it creates one with the client's version: creative, peaceful, commands allowed, overworld a void superflat. Single-player BuildGit reads the marker when the player enters, and when the world folder matches, takes the player to the same position in the overworld (right above the area when not inside it) and runs load.
3. **On leaving the single-player world**, loaded areas are read back automatically and saved as `<name>.edited.bgpack`. Chunks whose entities have not loaded keep the entities from the pull.
4. **`/bg push <name> [draft name]`** (on the server): the server asks the client to upload the changed area, opens a draft from **the save it was originally pulled from**, marks every block in the range and every entity seen on either side for this player, and commits; a commit only keeps blocks that really differ anyway (§5.1), so the draft holds exactly the changes made in single-player. With no changes, no draft is created. Without a draft name, when the draft already exists (the same area sent back a second time), a number is added. When done, the client is told to delete the changed area file.
   - When the client connects to a server with BuildGit, it lists the names of areas changed and not yet sent back; the server picks the ones this player pulled here and attaches buttons to send them back (requires `buildgit.push`). Names are only used to match the player's own pull records, at most 64 of them.
5. After that, the usual flow: `/bg go` to look, `/bg publish` to preview and publish, conflicts per §5.3. Changes on the server after the pull are handled by the three-way merge.

**Format**: zstd-compressed binary, with name, dimension, range, data version, the save pulled from, palette + per-block indices, entity NBT. No biomes: biomes changed in single-player are not sent back, and drafts sent back do not touch biomes. Older data versions are upgraded with DataFixer (§4.5), newer ones refused. Packets are sent in chunks: the server sends 512 KB per piece, one piece per tick; the client 30 KB per piece, 4 pieces per tick.

**Uploaded content is not trusted** (malicious or modified clients):

- Only uploads the server asked for are accepted; other packets are ignored. Each player can have only one pull or push in progress at a time.
- At most 16 MB compressed, with the piece count fixed in the first piece, giving up at once when the declared total exceeds the limit; discarded when not complete within 2 minutes; cleared on disconnect.
- The declared size is checked before decompressing, with a limit worked out from the range at pull time (4 bytes per block + 32 MB), so even a small file cannot make the server allocate lots of memory; the block count is bounded by the decompressed size; at most 10,000 entities.
- The range and dimension must match the pull record; only blocks inside the range are compared, so nothing outside it can be touched.
- Block states must parse, and block entity NBT must be readable by a parser with depth and node count limits; without permission to use operator blocks (`canUseGameMasterBlocks`), command blocks and the like and command block minecarts are not accepted. Rejected blocks stay as they were at pull time, and the player is told how many.
- Entities: only build entities, positioned inside the range, and they cannot use UUIDs that already exist outside the range (otherwise entities outside could be moved in).
- Data errors are only reported to the player, with no stack traces, so they cannot be used to flood the logs.
- The volume limit for pulls is set by `sync.max-blocks` (12 million blocks by default, enough for a 5-chunk radius around the player, full height). `buildgit.pull` and `buildgit.push` are OP-only by default.

## 7. Queries and display (low-level commands)

| Command | Effect |
|---|---|
| `/bg help [admin]` | Lists only the commands you can use; the most common section for where you are goes first (conflicts while resolving, build worlds in a build world); admin commands are in `help admin` |
| `/bg status` | Current branch, uncommitted blocks and entities (per author), whether the last shutdown was clean |
| `/bg log [branch] [page] [--all] [--author <player>]` | Commit history, 8 lines per page with page buttons, save ids clickable (clicking runs `diff <commit>`); by default only milestones and publishes are listed, consecutive autosaves folded into one line, `--all` expands them |
| `/bg log --graph [page] [--all] [--author <player>]` | A branch history graph of all drafts (like `git log --graph`), 8 lines per page (the collapsed chat window shows only 10 lines), with previous/next page buttons: branch lines drawn with box-drawing characters, aligned only in the monospace `minecraft:uniform` font; save ids clickable; autosaves skipped by default, with child saves connected straight to the nearest visible ancestor |
| `/bg diff [<commit> [<commit>]] [--cells]`, `/bg diff off` | Show difference outlines in the world; without arguments, the uncommitted changes. With one commit, also lists its author, message and how many blocks each player changed; `--cells` lists every block. When changes are in two or more areas, they are listed per area as with publish, and a click (`/bg diff goto <number>`) goes there. The result message ends with a **[Close]** button (`/bg diff off`) that clears the outlines; outlines also disappear after 60 seconds or on changing worlds |
| `/bg branch list\|delete` | Branch management (open drafts with `try`); `list` only lists drafts of the player's world (`--all` lists all), with the latest save id, time and players inside; `delete` is refused when there are unsaved changes or saves not fully published (the draft's latest save is not in the source's history), `--force` forces it; always refused while a draft uses it as its source |
| `/bg merge <branch>` | Merge any branch into the branch you are on (low level) |
| `/bg genesis upgrade` | Admins: after upgrading Minecraft, rewrite chunks in the genesis world saved by older versions in the current format (§4.5); runs in the background with progress in a bossbar |
| `/bg disk` | Admins: disk space used by BuildGit, split into history (shared by all branches, cannot be split per branch), genesis world copies, mark journals and the like, each draft's world folders, and view worlds; worked out on a background thread. `/bg disk clean` reclaims objects no repository uses that have not been written for more than a day |
| `/bg sel pos1\|pos2\|show\|clear` | A selection without WorldEdit |
| `/bg mark <x1 y1 z1 x2 y2 z2>\|sel` | Mark an area by hand (for changes that could not be recorded); `sel` uses the current selection. When typing coordinates, Tab fills in the selection's coordinates in order |

Commands are registered with Paper's Brigadier API (`LifecycleEvents.COMMANDS`): subcommands are shown by permission, and arguments have completion; actions that need confirming (publish, restore) put MiniMessage click buttons in the chat message, while the console is told the full command to run.

Difference colors reuse WorldGit's palette: added green, removed red, changed yellow, conflict purple. When there are too many, they become area bounding boxes.

## 8. Permissions

By default everything is OP only (Paper: `default: op`; Fabric: permission level 2). Which nodes include which is decided by `PermissionSets`, the same on both platforms (Fabric's permission mods do not know Paper's `children`), so `plugin.yml` only declares nodes, without `children`. For the admin-facing explanation and what each permission set is for, see the [admin manual §4](02-admin.md#4-permissions).

| Permission set | Includes |
|---|---|
| `buildgit.role.viewer` | menu, status, log, graph, show, diff, blame, inspect, who, sel, branch.list, go, back, view |
| `buildgit.role.builder` | viewer, try, save, pull, push |
| `buildgit.role.manager` | builder, publish, commit, merge, merge.resolve, revert, restore, mark, branch.create, branch.delete |
| `buildgit.admin` | manager, init, reload, disk |

All other nodes are `buildgit.<command>` (the three `branch` subcommands are `branch.list`/`branch.create`/`branch.delete`, and conflict resolution is `merge.resolve`); one node opens one command, and nodes do not include each other (decision #47). Exception: people with `publish`, `revert` or `merge` can also resolve conflicts, since those actions cause the resolution; single-player `load` and `help` need no permission.

Inclusion is decided in code, so setting a single node inside a permission set to false does not take it away; to give one thing less, give nodes one by one.

### 8.1 LuckPerms contexts (step one implemented)

With LuckPerms installed, BuildGit provides "where the player is in BuildGit right now" as contexts, so admins can make any plugin's permissions depend on it without knowing internal world names (`bg_d1`, `bgw_<id>` and so on). For example, WorldEdit only in drafts:

```
/lp group builder permission set worldedit.* true buildgit:location=draft
```

**Contexts provided** (all keys start with `buildgit:`):

| Key | Values | When present |
|---|---|---|
| `buildgit:location` | `main`, `draft`, `view` (viewing a past version), `resolve` (conflict resolution) | In a versioned world or its draft, view or resolution worlds |
| `buildgit:branch` | the branch name (`main` for main) | In main or a draft or resolution world |
| `buildgit:world` | the server world's name (`level-name` by default, usually `world`), or a build world's "owner/name" | Always ([03-worlds.md](03-worlds.md)) |
| `buildgit:role` | `owner`, `manager`, `builder`, `visitor` | In build worlds; based only on that world's member list, so non-members of private worlds have none |

- **Roles include the ones below**: owners carry all three of `owner`, `manager` and `builder`, managers carry `manager` and `builder`. So a permission set under `buildgit:role=builder` applies to everyone who can build.
- **Roles only look at the member list, not permission nodes**: LuckPerms asks BuildGit for contexts while checking permissions, and checking permissions again while working out contexts would loop forever. So when the server world relies only on permission nodes, there is no `buildgit:role`.
- Outside worlds BuildGit manages, none of these keys are present.
- LuckPerms' own `world` context still works as usual, but its values are internal world names, which change as drafts are created and deleted.

**Common uses** (written into the admin manual):

| Want | Setting |
|---|---|
| WorldEdit/Axiom only in drafts, not in main | `worldedit.*`, `axiom.*` under `buildgit:location=draft` |
| All builders in build worlds can use WorldEdit | `worldedit.*` under `buildgit:role=builder` |
| Flying in drafts | `essentials.fly` under `buildgit:location=draft` |
| A chat prefix showing which draft | the prefix (meta) under `buildgit:location=draft` |
| BuildGit's own commands limited by place, e.g. publishing only from one draft | `buildgit.publish` under `buildgit:branch=<name>` (when BuildGit checks permissions, LuckPerms applies the current contexts automatically) |

**How**:
- Optional dependency: Paper adds `softdepend: [LuckPerms]` to `plugin.yml`, Fabric checks whether the `luckperms` mod is loaded; without it, the related classes are never loaded. The LuckPerms API (`net.luckperms:api`) is used only at compile time and not bundled.
- A `ContextCalculator` is registered with the `ContextManager` (for `Player` on Paper, `ServerPlayer` on Fabric), providing `estimatePotentialContexts()` so LuckPerms' web editor and completion list the available keys and values (branch names, the four `location` values).
- **Threads**: LuckPerms may work out contexts on any thread, so the `Workspace`'s internal state cannot be read. The main thread compares each player's current contexts every tick and keeps a "player → current contexts" map (`ConcurrentHashMap`), and calculation only reads this map; only on a change is `signalContextUpdate(player)` called so LuckPerms drops its cache. The comparison is just a few lookups, with no need to hook world changes, branch deletions and other events, and nothing is missed. The branch list for completion is refreshed every 10 seconds.
- Setting: `integrations.luckperms-contexts: true` (on by default, only has an effect with LuckPerms installed).
- Across versions: the LuckPerms API is independent of the Minecraft version, so the code is in the shared module; Fabric works whenever LuckPerms exists for that Minecraft version (to be checked per version). The context keys and values are BuildGit's own and do not change with Minecraft versions.

**In two steps**:
1. **Implemented**: `buildgit:location`, `buildgit:branch` (`Contexts` and `LuckPermsContexts` in the `server` module). The resolution world and both view worlds are `resolve`, and `buildgit:branch` is the target branch being merged into.
2. Once build worlds are done: `buildgit:world`, `buildgit:role`. When a build world is renamed, context settings using the old name stop applying, so the owner is told when renaming and the admin manual explains it; for long-term stability the world's id could be used (`buildgit:world-id`, to add later if needed).

Acceptance: on both Paper and Fabric with LuckPerms, give `worldedit.*` under `buildgit:location=draft`: the player cannot use WorldEdit in main, can as soon as `/bg try` takes them into a draft, and cannot as soon as `/bg back` takes them back; `/lp user <player> permission checkcontexts` (or `/lp verbose`) shows the current contexts; without LuckPerms the server starts as usual.

Tested on Paper 26.3 and Fabric 26.3 (LuckPerms 5.5.88): in main `buildgit:location=main`, `buildgit:branch=main`, permission unset; after `/bg try lpt`, `draft` and `lpt`, permission in effect; after `/bg back`, back to `main` and the permission no longer in effect. Without LuckPerms, Paper 26.3 and Fabric 1.21.11 start as usual. Values for view and resolution worlds have not been tested yet. The Fabric build of LuckPerms 5.5.88 only supports Minecraft 26.1 and later; Fabric 1.21.11 needs an older LuckPerms that supports it (not tested).

## 9. Project layout

```
BuildGit/
├─ core/            # plain Java: objects, overlays, normalization, commits, merges, mark tables, Anvil reading, area files and sync packets. No Minecraft dependency
├─ api/             # public API (§3.10), depending only on the Bukkit API, Java 21
├─ paper/
│   ├─ axiom-api/   # compile-only interfaces of the AxiomPaper API (MIT, not bundled)
│   ├─ debugstickpro-api/  # compile-only interfaces of DebugStickPro (Apache-2.0, not bundled)
│   ├─ plugin/      # the Paper 26.2 plugin (paperweight userdev, Java 25), also the source shared by the Paper versions
│   ├─ plugin-1_21_11/  # the Paper 1.21.11 plugin: compiles the same source (Java 21), holding only version-specific classes
│   └─ plugin-26_3/ # the Paper 26.3 plugin: compiles the same source (Java 25)
├─ server/          # logic shared by both platforms (vanilla classes, Mojang names); not a separate module, compiled by each platform's build
├─ fabric/
│   ├─ common/      # shared Fabric code: entry points (server and client), platform implementation, mixins
│   ├─ mc26_2/      # the Fabric 26.2 mod (Java 25, no remapping needed), holding only version-specific classes
│   ├─ mc26_3/      # the Fabric 26.3 mod: shares the version-specific classes with 26.2
│   └─ mc1_21_11/   # the Fabric 1.21.11 mod (Java 21, compiled with Mojang names, remapped by Loom)
└─ docs/
```

- Messages use MiniMessage, with en_us and zh_tw.
- Settings files use YAML.
- core is tested with JUnit; platforms are accepted with automated runs on real servers.

## 10. Risks and prototype verification

In order of risk, these prototypes come first, with results recorded in the decision table before the real implementation:

| # | Risk | How to verify | Fallback |
|---|---|---|---|
| 1 | Whether replacing `neighborUpdater` by reflection works on Paper (final fields, Java 25 restrictions, whether Paper internals bypass it) | Paper 26.2 and 1.21.11: a player places water, wires redstone, places sand, and every knock-on block is marked | Pass causes through events such as `BlockPhysicsEvent`, `BlockFromToEvent` and `BlockRedstoneEvent`, accepting some misses. **26.2 result**: replacing works; all 145 blocks of a spreading water source were attributed to the player who placed it |
| 2 | Synthesizing reads on Paper (intercepting chunk system reads) | Create a branch, walk far away, and compare synthesized chunks with "genesis + overrides" block by block | Synthesize whole regions in advance (§4.2) |
| 3 | Whether runtime worlds on Fabric (Fantasy and the like) work on 26.2, 1.21.11 and in single-player | Create, load, unload and delete branch worlds | Implement runtime dimension registration ourselves |
| 4 | Determinism of world generation | Generate 1000 chunks twice with the same seed and version, comparing every block (structures included) | Write newly generated chunks into the genesis world when main generates them |
| 5 | Whether FAWE's async writes and Axiom's writes yield all positions | `//set`, `//paste`, `//undo`, and Axiom's brushes and pastes, comparing marks with actual changes | Compare the operation's bounding box before committing. **26.2 result**: FAWE `setBlocks` mark count equals change count, with no measurable extra time; Axiom still to be verified with a client |
| 6 | Performance of marking and propagation | MSPT and memory with large redstone machines and lots of flowing water | Paged bitsets, periodic cleanup of the cause side table |

## 11. Development order

1. **core**: object storage, overlays, commits, three-way merge, changesets, verified with JUnit.
2. **Prototypes**: risks 1–4.
3. **Paper 26.2**: tracking, WorldEdit/FAWE and Axiom integration, the public API, saves and history on main, branch worlds (try/go/back/publish), conflict-free merges, conflict resolution, revert/restore, branch management (done).
4. **Fabric 26.2 and 1.21.11**: share all of `server/`'s logic, with the platform only doing hooks and `Platform` (done; accepted on dedicated servers of both versions with a fake player on the server: placing, breaking, commands, chest contents, armor stands, TNT, saving, drafts, publishing, read-only views, inspect mode, chest menus, unloading and restarting. Single-player and WorldEdit/Axiom integration not done yet).
   - Taking areas to single-player and back (§6.7, done): on both versions, a fake player simulates a client with BuildGit, accepting pull, loading and packing in single-player (with another dedicated server standing in for single-player), push as a draft, publishing, plus cross-version loading and malicious uploads (huge piece counts, non-zstd, decompression bombs, unrequested uploads). The real client's screens are not confirmed yet.
5. (merged into step 3)
6. **1.21.11**: older-version support on both platforms (Paper done: the same source compiles against 1.21.11, and the internal fields used by reflection are the same in both versions; accepted on a real Java 21 server for tracking, drafts, publishing, conflict resolution, unloading, restarting and outlines).
7. **The builder layer** (§6):
   - the three-step flow and autosave were done together in steps 3 and 4;
   - this step fills in selections, view, the bossbar, the inspect tool and chest menus (Paper done; outlines, the bossbar, chest menus, `view`, `sel` and chat buttons accepted with a fake player on the server, the screens themselves not yet confirmed with a real client).
8. **Performance acceptance** (risk 6) and the user guide.

## 12. Decision log

| # | Date | Decision |
|---|---|---|
| 1 | 2026-10-14 | A version = genesis world + overlay; only blocks touched by players are stored |
| 2 | 2026-10-14 | A block's content (block state + block entity NBT) is the smallest unit of merging; entities are per UUID |
| 3 | 2026-10-14 | Each commit comes with a changeset recording each block's author, for log/blame |
| 4 | 2026-10-14 | Tracking passes a "cause" within the thread; scheduled ticks and block events carry the cause in a side table |
| 5 | 2026-10-14 | Mobs' side effects on blocks do not pass the cause on by default; falling blocks, TNT, projectiles, minecarts and boats do |
| 6 | 2026-10-14 | Knock-on limits: 256 blocks of distance, 1 million blocks per cause, adjustable in YAML |
| 7 | 2026-10-14 | ~~The Paper write layer has no single hook, so event targets are marked along with their 6 neighbors~~ Changed to marking only the target, leaving neighbors to the updater's precise judgment (#24); falling back to 6 neighbors only when no updater is hooked |
| 8 | 2026-10-14 | Branch worlds are created lazily with synthesizing reads; a chunk never read always equals the branch's latest commit |
| 9 | 2026-10-14 | The genesis world is fully copied only once, at init; chunks generated later use the world generator as their genesis |
| 10 | 2026-10-14 | Merging requires the target branch to have no uncommitted marks; tracking is off during system writes |
| 11 | 2026-10-14 | After a merge, shapes of boundary blocks are recomputed, and the changes go into the merge commit with author `merge` |
| 12 | 2026-10-14 | No history-rewriting reset; restores always produce a new commit |
| 13 | 2026-10-14 | ~~Develop Fabric 26.2 first~~ Changed to Paper 26.2 first (user's request), Fabric afterwards |
| 14 | 2026-10-14 | The builder layer: the UI says "draft/save/publish/milestone"; the low-level git commands are kept |
| 15 | 2026-10-14 | The three-step flow `try`/`publish`/`back`; publishing always previews and asks for confirmation first; drafts are kept after publishing |
| 16 | 2026-10-14 | Autosave: when every player in the branch world has stopped for 5 minutes, or 30 minutes after the last save; `try`, `publish` and shutdown always save first |
| 17 | 2026-10-14 | Partial publishes (`publish --selection`) produce a single-parent commit recording the source draft, not a merge commit |
| 18 | 2026-10-14 | `view` opens a read-only world with synthesizing reads, deleted after the last player leaves |
| 19 | 2026-10-14 | Chest menus use server-side screens, so neither platform needs a client mod |
| 20 | 2026-10-14 | Using the twme.dev domain: the Java package and Maven group are `dev.twme.buildgit` |
| 20 | 2026-10-14 | Paper knock-on tracking switches to "spreading": a neighbor update touching a recently marked block takes its author, replacing the scheduled tick side table; natural changes are excluded with a same-tick suppression set |
| 21 | 2026-10-14 | WorldEdit/FAWE only record positions without reading the world; FAWE marks whole sections with a batch processor, avoiding CoreProtect's per-block cost and the misses from `allowed-plugins` |
| 22 | 2026-10-14 | A public API (`api/` module, `ServicesManager`), so other plugins can record directly or set the author with `attribute` |
| 23 | 2026-10-14 | zstd-jni is not bundled, but downloaded from Maven Central through `libraries` in `plugin.yml` (the plugin jar went from 7.5 MB to 220 KB) |
| 24 | 2026-10-14 | Knock-on changes only mark blocks that really changed: candidate blocks are compared after the outermost update finishes |
| 25 | 2026-10-14 | Chunks newly generated after init are added to the genesis world at generation time (§2.3's fallback), not relying on deterministic generation |
| 26 | 2026-10-14 | Paper branch world synthesis hooks `RegionFileStorage.regionCache` (replaced by a subclass via reflection): the whole region is synthesized the first time its region file opens, and it is an ordinary world after that |
| 27 | 2026-10-14 | Chunks a save cannot read are always loaded (generated if needed) and then read, never taken as air; if still unreadable, the whole save fails and the marks are put back |
| 28 | 2026-10-14 | ~~Until conflict worlds (§5.3) are done, `publish` with conflicts only lists them without publishing~~ Superseded by #30 |
| 29 | 2026-10-15 | Shape fixes do not go into the merge commit, but are saved right after as a separate autosave (author `merge`): the merge commit only reflects the merge itself, and fixes can be reverted separately (replaces the second half of #11) |
| 30 | 2026-10-15 | Paper's resolution world is a temporary branch `merge-<id>`, with ours/theirs as read-only view worlds; finishing does another three-way merge, so it can finish even when the target branch got new saves during resolution |
| 31 | 2026-10-15 | Merge commits and partial publishes record each block's actual author in the draft (`authorsSince`) in the changeset, visible directly with `show`; blocks changed during resolution go to whoever changed them |
| 32 | 2026-10-15 | `/bg` is registered with Paper's Brigadier API (no longer `commands` in `plugin.yml`); confirm buttons use the Adventure click events behind MiniMessage's `<click:run_command>` |
| 33 | 2026-10-15 | Axiom selections are not supported (they exist only on the client); selections are tied to their dimension |
| 34 | 2026-10-15 | Branch worlds are saved and unloaded after having no players for a while (60 seconds by default), closing the workspace too, and loaded again when needed (user's request, to save memory) |
| 35 | 2026-10-15 | Memory limits: the overlay cache is weighed by entry count (about 400,000 entries); the genesis chunk cache keeps the packed format; open region files and the written-object list both have limits; no fsync without new marks |
| 36 | 2026-10-15 | Outlines become client-only entities sent as packets, generating packets with NMS directly, without PacketEvents (avoiding hard-coded data field ids that change between versions; BuildGit is already tied to NMS versions) |
| 37 | 2026-10-16 | Paper gets one jar per Minecraft version (`-paper-26.2`, `-paper-1.21.11`), sharing source, each compiled against that version's server. No single multi-version jar: classes compiled only against the old API could fail at runtime on newer versions because of signature changes. The jar checks the server version at startup and disables itself on a mismatch |
| 38 | 2026-10-16 | Cross-version history: objects record their data version and are upgraded on read with vanilla's DataFixer, never rewriting old objects; once a repo has been written by a newer version, older servers refuse to open it (§4.5) |
| 39 | 2026-10-16 | The Fabric version hooks vanilla's single write entry point (`LevelChunk.setBlockState`) with mixins and sets causes with `@WrapMethod`; branch worlds use Fantasy. It shares `server/`'s logic with Paper, with one jar per Minecraft version (`-fabric-26.2`, `-fabric-1.21.11`), the version pinned by `fabric.mod.json` |
| 40 | 2026-10-16 | Single-player sync works per selection, transferred over the game connection (`buildgit:sync` channel); sending back opens a draft from the save it was pulled from, published through the usual flow; uploads are untrusted, with limits on size, piece count, time, entity count and block types (§6.7) |
| 41 | 2026-10-16 | `init` only covers the world the player is in; each world (including ones created by multiworld plugins) is initialized separately, worlds loaded later join automatically and cannot be unloaded. The genesis world is copied chunk by chunk in the background without pausing the server (§2.4, §4.3) |
| 42 | 2026-10-16 | Support 26.3 (one jar each for Paper and Fabric). 26.3 changed how region file block palettes and block states are written; BuildGit does not parse the format itself but lets the server convert with vanilla's `BlockState.CODEC`, so future format changes only need compiling against the server version |
| 43 | 2026-10-16 | main's worlds can be unloaded by multiworld plugins when neither they nor their draft worlds have players (user's request); while not loaded, writes to that branch are refused, rather than BuildGit loading the world back itself (BuildGit does not know the multiworld plugin's generator and other settings) |
| 44 | 2026-10-16 | Permission sets `buildgit.role.viewer`/`builder`/`manager` (user's request, to make granting easier for admins); inclusion is decided in code, consistent between Paper and Fabric, and `plugin.yml` no longer has `children` |
| 45 | 2026-10-16 | Support DebugStickPro (user's request): classic and copy modes are marked through its events; frozen blocks are skipped at save time, so temporary barriers are never saved into versions (§3.9.1) |
| 46 | 2026-10-17 | Record biomes (the user pointed out the gap): stored as 4×4×4 biome cells in the section overlay, compared when saving sections with marks; when both sides changed one, the target is kept and it does not go into conflict resolution; pull/push do not carry them for now (§3.9.2) |
| 47 | 2026-10-17 | One separate permission node per command (user's request): a node opens one command and nodes do not include each other, with combinations left to permission sets; `buildgit.view` now only covers `/bg view`, and the commands under the old `sync` and `admin` are split into `pull`, `push`, `reload` and `disk` (§8) |
| 48 | 2026-10-17 | Less long main-thread work (the user reported stalls on init and large operations): system writes (publish, revert, restore, conflict resolution, and the shape fixes that still existed then) are spread over ticks, at most 20 ms per tick; new worlds skip the spawn search; `init`'s save does not wait for IO on the main thread; `show`, `diff` and publish preview calculations (reading change records, working out outlines) move to the background (§4.3, §4.4, §5.2) |
| 49 | 2026-10-17 | Remove shape fixes after merging (user's decision, replacing #11 and #29): recomputing connections would change states builders made deliberately with the debug stick, and publishing should keep the draft's states exactly (§5.2) |
| 50 | 2026-10-17 | A draft belongs to one world only (user's decision; the Nether and the End are separate too): only that world opens, lists only show drafts of the player's world, reverts and restores only touch it, and drafts of different worlds cannot merge with each other; main and old drafts still cover all worlds (§4.1) |
| 51 | 2026-10-17 | Record Axiom players with the bypass permission too (the user decided to keep the bypass and read AxiomPaper's internals; no separate switch, turning itself off on a mismatch): replace the operation queue to wrap block buffers, and also listen to the single block placement channel; except the biome brush (§3.9) |
| 52 | 2026-10-18 | The genesis world can use a lazy copy (`init.lazy-copy-above-mb`, off by default): a chunk is copied the first time it loads, and never-loaded ones are read from main's world save; saves space and init finishes at once, at the price of not being able to change that world outside BuildGit |
| 53 | 2026-10-18 | The genesis world can go in another folder (`init.genesis-dir`): when the fast disk is not big enough, use a large hard disk instead; still a full copy, with none of lazy copy's extra rules; when the setting changes but the data has not moved, the old location is kept |
| 54 | 2026-10-19 | The Fabric version records Axiom with mixins (the mod has no integration API): world-changing packets are wrapped to set the cause, and block buffers mark the blocks they write before writing; Axiom is referenced only at compile time and not bundled; turned off on an internal mismatch |
| 55 | 2026-10-19 | The genesis world can be rewritten in the current version's format (`/bg genesis upgrade`): content and history unchanged, saving the upgrade on every read; files are swapped one by one in the background, without stopping the server. Chunk upgrades now pass the same dimension information as the server |
| 56 | 2026-10-19 | Settings and language files follow plugin updates (user's request): settings use the new default file as a template and fill the user's values back in; language files are written to the server and compared with the bundled version written last time, replacing unchanged messages with the new version and keeping changed ones |
| 57 | 2026-10-19 | Simpler steps for taking areas to single-player (the user said it was too complicated): `/bg pull` needs no selection and no name (the draft's changed extent or the area around the player, named automatically); reconnecting to the server prompts to send back, and the client deletes the changed area file after sending. After pulling, it can be opened with one click in a dedicated single-player world (a client command creates or opens the world, and the area is placed automatically on entering) |
| 58 | 2026-10-19 | Offsite backups (§2.5): the repository is uploaded incrementally to S3-compatible storage (objects as is, the genesis world and level.dat named by content hash, one snapshot per backup); on a new host after downloading, main's worlds are rebuilt from versions with synthesizing reads, without needing the world save; lazily copied dimensions are incomplete and need world backups too. SigV4 implemented ourselves, without the AWS SDK |
| 59 | 2026-10-20 | Restoring offsite backups (§2.5): main's worlds have their whole regions synthesized during the download, and restarting only swaps folders, without hooking synthesizing reads into main's worlds (simpler, and startup does not have to wait); the originals are always kept renamed; with a different Minecraft version, the current server's world locations are used and level.dat is not swapped, with a reminder when the seed differs |
| 60 | 2026-10-20 | Strengthening offsite backups (picked by the user): no re-sending after an upload is interrupted halfway; request retries; admins warned on repeated failures; `/bg backup fetch` fetches one branch; restoring downloads only the objects used (§2.5) |
| 61 | 2026-10-21 | Backups become "backup jobs" (§2.7, user's decision): each destination (local or S3) has its own content, cron schedule or idle trigger (after no players for some minutes; no backup on shutdown) and retention; `s3.yml` only holds named connections, `backup.yml` holds jobs. World folders are backed up too (§2.6): stored in the world folder's layout so they can be used directly (hard links locally; on S3 versioned or hash-named is picked automatically by whether the bucket has versioning on, or one folder per snapshot can be set), only processing worlds players have entered, on by default, with players told when it starts and ends |
| 62 | 2026-10-25 | `init.lazy-copy-above-mb` defaults to 512 (user's decision): worlds over 512 MB use a lazy copy at init; only affects worlds initialized later, not ones already initialized; settings files that already say -1 keep copying fully |
| 63 | 2026-10-26 | Consolidate duplicate commands (user's decision): remove `commit` (same as `save`), `blame` (use `inspect`), `branch create` (use `try`); `graph` becomes `log --graph`; `show` merges into `diff <commit>`; `who` merges into `branch list`. The matching permission nodes are removed too, with no notices left for the old commands |
| 64 | 2026-10-26 | History playback `/bg play` (§6.5, user's request): players walk around watching, with the range given as save ids or times; uses the `buildgit.view` permission, no new node |
| 65 | 2026-10-26 | Before/after toggled in place (§6.6, user's choice): fake blocks sent only to the player, no extra world and no teleporting |
| 66 | 2026-10-26 | Backups and restores of large worlds (§2.5, §2.6, user's decision): world folder region files are copied and uploaded one by one after saving resumes, so the saving pause and local staging do not grow with the world; disk space is checked before restoring world folders; history resends changed genesis world files at most every `genesis-hours` (24 by default) hours |
| 67 | 2026-11-02 | Backup jobs can exclude worlds (§2.6, user's request): `exclude-worlds` takes world folders or build worlds as "owner/name"; history jobs can only exclude build worlds; when restoring world folders, excluded worlds stay as they are, rather than being renamed away along with the old world folder |
| 68 | 2026-11-03 | English by default, shown in each player's game language (user's request, since the plugin is for a worldwide audience): `language` defaults to `en_us`, used for the console and when there is no lang file; each player sees the lang file for their game language (other regions of the same language count); with `language-per-player: false` everyone uses `language`. Every message lookup carries its recipient (player or sender), with no thread-local variable, so it still matches after background work returns to the main thread. Errors for players (`Workspace.Refused`) store the lang file key and arguments and are built for whoever receives them; server logs, settings file template comments and core exception messages are always English (not in lang files: they are for admins to investigate and paste into issues) |
| 69 | 2026-11-03 | The README and the documents in `docs/` are written in English (user's request, following #68); the decision log keeps its original numbering and dates |
