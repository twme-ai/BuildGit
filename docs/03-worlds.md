# Build worlds: one repo per world (planned 2026-10-21; phases 1 and 2 implemented, phase 3 in progress)

Requirements are in [00-requirements.md](00-requirements.md), the underlying design in [01-design.md](01-design.md). This document plans letting players create their own "build worlds": each build world is a separate repo with its own genesis, history, drafts and members, and can be private or public.

## 1. Concepts and terms

| What players see | Underneath | Description |
|---|---|---|
| **Build world** | repo | A separate world and all of its history. Its name is "owner/name", e.g. `alice/castle`; for your own world just type `castle` |
| **Server world** | the server's repo | The server's original overworld (with the Nether and the End). It behaves as it does now, and is also a build world that can have members and visibility. Its name defaults to `level-name` from `server.properties` (`world` by default) and can be changed with `player-worlds.server-world-name` |
| Draft, save, publish | branch, commit, merge | Same as now; each build world has its own |

(The Chinese UI says 建築世界 rather than 我的世界, which is Minecraft's Chinese name.)

**The most important convenience: commands act on the world you stand in.** Existing commands such as `save`, `try`, `log`, `publish`, `restore` and `pull` take no extra argument and act on the build world the player is in (being in one of its drafts counts). Players only need to learn a few more things: creating, entering and leaving, finding worlds, inviting people.

## 2. How players use it

### 2.1 Commands

| To do | Command | Description |
|---|---|---|
| Create | `/bg new [name]` | Opens the create dialog (§3); without a name it numbers automatically (`world-1`, `world-2`, …), which can be renamed later. Names may only use lowercase letters, digits, `_` and `-` (1–32 characters), because the name is the world folder's name (§6.1); other languages can go in the display name. You are teleported in once it is created |
| Enter | `/bg open <name>` | Your own as `castle`, someone else's as `alice/castle`, the server world by its name (`world` by default). Returns you to where you left last time; the first time, to the spawn point |
| Find worlds | `/bg worlds` | Menu: "Mine" (own and invited), "Public" (most recently saved first), "Recently visited" |
| Copy someone's public work | `/bg fork <owner/name> [new name]` | Copies it as your own private world, with its history |
| Manage | `/bg world settings｜members｜visibility｜rename｜transfer｜delete｜undelete` | Run inside your own world; all of them can also be clicked from `/bg menu` |

- The menu is the main entry point: `/bg menu` gets a "Build worlds" button, and creating, entering, inviting and settings can all be clicked, with no commands to remember.
- Inviting: `/bg world members add <player> [builder|manager]`, builder by default; in the menu you can click an online player's head.
- The bossbar in a build world shows `alice/castle · draft d1` or `Visiting alice/castle · /bg fork to copy`.
- Single-player integration (design §6.7) works as usual: `/bg pull` in someone's world takes an area of that world.

### 2.2 What happens automatically on entering and leaving

- **Game mode**: members and visitors each switch to the mode set for this world (members creative and visitors spectator by default; owners can change it, §3); leaving restores the original mode.
- **Inventory**: shared across all worlds by default, not swapped on entering or leaving.
  - **Inventory isolation** (a per-world setting the owner turns on, separately for members and visitors): entering stores the original inventory, ender chest, experience and status effects, and swaps in the player's copy for **this world**; leaving swaps back. Each world has its own copy, so nothing is carried out of a creative build world.
  - **Starting kit** (that role's inventory isolation must be on first): the owner sets what members and visitors each start with (including armor and offhand). How: `/bg world settings kit-member` (or `kit-visitor`, also in the menu) opens a large chest; the top three rows are the inventory, the fourth row the hotbar, and the first five slots of the fifth row the helmet, chestplate, leggings, boots and offhand. Put items in and close it to save. Items put in are taken from yourself (and taking them out really gives them to you), so nothing is duplicated. Only one person can edit a starting kit at a time; when someone else has it open, you are told who. When it cannot be saved (e.g. a full disk), the items are given back, and what does not fit in your inventory drops at your feet. Add `clear` to empty it.
  - **Reset every time** (`kit-member-reset`/`kit-visitor-reset`, on by default): when on, the starting kit is put on every time you come in; when off, only the first time, and after that you get the inventory you last left in this world.
  - **Give again after death** (`kit-member-respawn`/`kit-visitor-respawn`, on by default): when you die in this world and respawn in this world, the inventory is replaced with the starting kit (the ender chest and experience are untouched). What dropped on the ground on death stays there.
  - Admins can force isolation in every build world with `inventory-isolation: always` (recommended for survival servers), or prevent owners from turning it on with `never` (default `owner`: owners decide). Whether new worlds start with it on is decided by `defaults.inventory-member` and `defaults.inventory-visitor`.
- **Going offline and coming back**: a player who goes offline in a build world is checked again against the current permissions when they come back. If they have been removed, or the world became private or was deleted, they are sent back to the server world. Positions are recorded with the existing `players.yml` mechanism (design §4.4).
- **Portals**: Nether and End portals do not work in build worlds (otherwise people would be sent to the server's Nether).

## 3. Settings at creation

Every item in the create dialog has a default, so just pressing "Create" works:

1. **World type**: Overworld, Nether, End. Affects the sky, lighting, ceiling, whether water evaporates and whether beds explode.
   - End-type worlds have no ender dragon: the dragon belongs only to the server's original End.
2. **Terrain**:
   - **Normal**: the seed can be given (empty is random). The overworld type can also pick "normal/large biomes/amplified".
   - **Superflat (customizable)**: pick a preset layer setup, or write layers yourself (the same format as vanilla superflat "preset codes", e.g. `minecraft:bedrock,2*minecraft:dirt,minecraft:grass_block;minecraft:plains`; layers stack up from the bottom of the world). The presets have air underneath and the ground at y=64: overworld is bedrock, 2 dirt, grass (y=61–64); Nether is bedrock, 2 netherrack (y=62–64); End is 3 end stone (y=62–64). Structures can be turned on or off.
   - **Void**: nothing at all, a single biome. At creation, one block is placed under the spawn point, stone by default, another block can be picked; only this one block, only at creation.
   - **Template**: create from a template world prepared by an admin (the same as forking the template, §5.3).
3. **Rule presets**:
   - **Build** (default): always noon, always clear, peaceful, no mob spawning, random tick speed 0 (crops do not grow, ice does not melt, leaves do not decay), no fire spread, no mob griefing. Builds are not messed up by natural changes, and BuildGit does not have to record lots of natural changes.
   - **Vanilla**: everything as in vanilla.

**Cannot be changed after creation**: world type, terrain, seed, structures. These decide the "genesis", and changing them would make the genesis and the history disagree.

**Can be changed at any time after creation** (`/bg world settings` or the menu, owners and managers):

| Setting | Default | Description |
|---|---|---|
| Name | automatic number | The name used in commands, which is also the world folder's name; renaming renames the world folder too (§6.1) |
| Display name, description, icon | same as the name, empty, grass block | Shown in the list; the display name can be in any language |
| Visibility | private | Private/public (§4) |
| Allow copies | on when public | Whether visitors can fork and `pull` to single-player |
| World border | server setting (1024×1024 by default) | Size and center, no larger than the admin's limit; also limits where visitors can go |
| Time | always noon | Fixed at a time, or passing normally |
| Weather | always clear | Always clear/rain/thunder, or changing normally |
| Difficulty and mob spawning | peaceful, none | |
| Natural changes | all off | Random tick speed, fire spread, mob griefing |
| Members' game mode | creative | Creative/survival/adventure |
| Visitors' game mode | spectator | Spectator/adventure (§4.1) |
| Inventory isolation | off | When on, this world has its own inventory (§2.2) |
| Starting kit | empty | One each for members and visitors; inventory isolation must be on first |
| Spawn point | where it was at creation | Where people appear when teleported in; `/bg world settings spawn` sets it to where you stand |
| Respawn here | on | People who die in this world (including its drafts) respawn at the spawn point of the same world; their own bed or respawn anchor in this world works as in vanilla. When off, they go back to the server world as in vanilla |

Worth considering but not done for now: datapacks (different datapacks per world would affect how consistent the genesis is), custom dimension types, worlds with several connected dimensions (one build world with both an overworld and a Nether).

## 4. Visibility and roles

| Role | Can do | Matching existing permission set (design §8) |
|---|---|---|
| Owner | everything, plus delete, transfer, change visibility | all of manager + managing this world |
| Manager | invite or remove builders, change settings, publish, restore | manager |
| Builder | build, save, open drafts, take to single-player | builder |
| Visitor | come in to look around; view history (`log`), differences, "who changed this block"; when allowed, fork and submit (§5.4) | viewer (but cannot enter drafts; viewing old versions is off by default, owners can allow it) |

- **Private**: only members can see it and come in; it is not in the public list.
- **Public**: everyone is a visitor, can come in to look and can fork; only members can change it.
- Each world's members are recorded in its own `repo.yml`, so admins do not need to set up a permissions plugin. Server permissions only govern "can create build worlds" (`buildgit.world.create`) and "can manage all build worlds" (`buildgit.world.admin`).
- When a member is removed or a world becomes private, people inside who no longer have permission are sent back to the server world.
- **Viewing old versions** creates a view world, and with many visitors the server would keep opening new worlds, so by default only members can view; owners can turn on "visitors can view old versions", and view worlds still count towards `max-loaded`.
- **Admins** (`buildgit.world.admin`) can enter any world and change any world's settings, and also:
  - **Unlist**: `/bg world unlist <world>` forces it private and locks it, so the owner cannot make it public again without an admin's `relist`. No reporting or name filtering.
  - **Transfer and delete**: `transfer` and `delete` on any world, e.g. when the owner is banned or has not been online for a long time. No automatic cleanup.
- With LuckPerms installed, the player's world and role become contexts (`buildgit:world`, `buildgit:role`, design §8.1), so admins can make other plugins' permissions depend on the role, e.g. all builders can use WorldEdit. Visitors are still always blocked from WorldEdit and Axiom (§4.1), regardless of contexts.

### 4.1 Visitors: game mode and how far they can go

The owner picks the visitors' game mode:
- **Spectator** (default): can fly around, pass through walls and see what is in chests, but cannot change anything.
- **Adventure**: look around on foot, good for puzzle, parkour and tour builds; can come with a starting kit (e.g. a map, an elytra). Adventure mode can still press buttons, take things from chests, interact with entities and drop things, so BuildGit blocks every change visitors make to the world the same way building is blocked (taking chest contents, breaking item frames and armor stands, trampling farmland, lighting fires and so on); visitors can see but not change. Whether buttons, levers, doors and pressure plates are allowed is one setting (`visitor-interact`, off by default). When allowed, these changes stay in the world, so it is only recommended for builds where the author wants people to press things (e.g. doors, mechanisms). (Implemented; details in phase 3 of §7)

Limiting exploration:

1. **No new terrain**: the game rule `spectators_generate_chunks` is false in build worlds (both 1.21.11 and 26.x have this rule), so spectators walking into ungenerated areas only see emptiness, and the server does not generate terrain.
2. **Cannot leave the area**: spectators pass through the world border, so BuildGit checks visitors' positions every second, and pulls anyone outside this world's border back inside, with one notice. (Implemented)
3. **WorldEdit and Axiom blocked**: spectators can still run WorldEdit commands and use Axiom, so in build worlds anyone who is not a builder is blocked from both (BuildGit already hooks into both for recording, and the refusal goes in the same place).

Adventure-mode visitors are stopped by the world border, but walking still generates new terrain, so the default border needs to be small enough (the border at creation, §3); point 1 only works for spectators.

Verified (Paper 26.3, Fabric 1.21.11 and 26.3): spectators really are not stopped by the world border; each build world has its own game rules and border (Fantasy worlds too).

### 4.2 The server world can be set up too

The server world works with the existing permission nodes (`buildgit.role.*`), and can also have members and visibility:

- **Public** (default, same as now): everyone follows the existing permission nodes.
- **Private**: people without permission who are not members cannot be here. Since the server world is usually the spawn, a lobby world (`worlds.lobby`) must be set before it can be made private; people without permission are sent there when they join or are removed. Without a lobby, making it private is refused.
  - The lobby can be a normal world without version control (created by the admin; BuildGit only sends people there), or a build world (e.g. `admin/lobby`, so the lobby can also be saved and restored). A build world lobby must be public; people entering are visitors under its settings, so lobbies usually set visitors to adventure mode (§4.1).
  - The lobby always stays loaded (below).

### 4.3 Worlds that stay loaded

`worlds.keep-loaded` lists worlds that are never unloaded when idle and are loaded at server start, e.g. the lobby or popular public builds:

```yaml
worlds:
  keep-loaded: ["admin/lobby", "alice/castle"]   # build worlds as "owner/name"; drafts too, e.g. "alice/castle:d1"
```

- Matched by id after renames and transfers, so the setting does not need changing; `/bg world settings` shows "Kept loaded".
- Worlds kept loaded do not count towards `max-loaded`, but listing too many uses memory; the manual must say so.

## 5. Administration

### 5.1 Admin settings (`player-worlds:` added to `config.yml`)

| Setting | Default | Description |
|---|---|---|
| `enabled` | `false` | Whether players can create build worlds |
| `max-per-player` | `3` | How many each player can own (not counting invited ones) |
| `border`/`max-border` | `1024`/`4096` | New worlds' border size, and the largest players can set (split into two keys during implementation; `border` keeps the phase 2 format) |
| `allowed-types`/`allowed-terrain` | all | E.g. only superflat and void |
| `max-loaded` | `20` | How many build worlds can be loaded at once, counting drafts and view worlds (not ones kept loaded); when full, players are asked to try later. No per-player disk quota; the border limit already limits size |
| `inventory-isolation` | `owner` | Inventory isolation (§2.2): `owner` owners decide, `always` forced in every build world (recommended for survival servers), `never` no isolation. Replaces the old `separate-inventories` and `allow-inventory-isolation` (converted when read) |
| `defaults.*` | | New worlds' initial settings (rule preset, visibility, members' and visitors' modes, visitor interaction, inventory isolation, fork, submissions, respawn); owners can change them after creation |
| `trash-days` | `7` | How many days deleted worlds are kept |
| `paths.*` | see §6.1 | Where the repository, objects, genesis world and generation settings go |
| `templates` | empty | Build worlds that can be templates (e.g. `admin/spawn-template`) |
| `server-world-name` | empty | The server world's name in commands, lists and LuckPerms contexts; when empty, `level-name` from `server.properties` (`world` by default). Players cannot give their own build worlds this name |

Empty build worlds are unloaded with the same idle unloading as draft worlds (design §4.4).

### 5.2 Deleting and the trash

- `/bg world delete`: after the owner confirms, people inside are sent back to the server world and the world is unloaded; the world folder, repository and genesis world (each at the location set in §6.1) are **renamed in place** to `<name>.deleted-<time>`, and the index records them. Renaming in place moves no files, so it is just as fast when a folder is on another disk.
- Kept for 7 days (`trash-days`): meanwhile the owner can get it back with `/bg world undelete` (renamed back), giving a new name if the name is taken. Checked once a day; only expired ones are really deleted.
- When objects are in the shared location (the default), deleting a build world does not delete objects; reclaiming them later is a separate "delete objects no repo uses" step. When objects are inside the repository, they are deleted along with it.

### 5.3 Forks and templates

- A fork creates a new private build world: the genesis world is copied, main points at the source main's latest save, and settings are copied (except name and members).
  - When both sides have the same object location (the default shared one), objects need not be copied; when they differ (e.g. objects inside each repository), the objects the source uses are copied with hard links. Objects are never modified, so this is safe; on the same disk it takes almost no space, and only across disks is anything really copied.
- A template is just a build world chosen by an admin; creating from a template forks it, but without showing "Forked from".
- A forked world records the source's id and the save at fork time, for submissions (§5.4).

### 5.4 Submissions (like pull requests)

When a visitor has finished changes in their own fork, they ask the original author to merge them back:

1. **Submit**: in the forked world, `/bg submit [message]` sends the current main (or a given draft) to the source world. The source world gets a branch `submit-<player>-<number>` pointing at the submitted save; objects are shared or copied with hard links (§5.3), so nothing is re-sent. The source's owner and managers are notified (if offline, on their next login).
2. **Update**: keep changing in the fork and `/bg submit` again to update the same submission (the branch moves forward), without opening a new one.
3. **Review**: the source's owner or managers see the message and differences in the `/bg world submissions` list (also in the menu); they can go in to look like any draft (copied lazily; the world is only made when going in).
4. **Merge or reject**: merging is the existing `publish` flow (conflicts are handled with the existing three comparison worlds); rejecting comes with a reason, the branch is deleted and the submitter is notified.
- Submitting is only possible when the source "allows copies"; owners can also turn off "accept submissions".
- Each person can have at most one open submission per world, to avoid spam.
- Cross-version: the source and fork are on the same server, and objects carry data versions; the fork has inherited the source's `legacy` (§6.4), so pointing a branch there just works.

## 6. Underneath

### 6.1 Storage locations (customizable)

The server world's repository stays in `buildgit/` (and its genesis world can still be put elsewhere with `init.genesis-dir`), unaffected by this section. Each build world's data is split into four parts, each placed with a path template:

```yaml
player-worlds:
  paths:
    # Repository: refs, branches.yml, data-version, repo.yml (members, visibility, changeable settings), worktree, merge
    repo: "{buildgit}/worlds/{id}"
    # Objects: by default one location shared by all build worlds (forks need no copying)
    objects: "{buildgit}/objects"
    # Genesis world
    genesis: "{repo}/genesis"
```

Generation settings (type, terrain, seed, superflat layers) are recorded in the repository's `repo.yml`, with no separate location: when the world folder is missing, the world is rebuilt from them, so they go with the repository (changed during implementation; originally planned for the world folder).

**Placeholders**:

| Placeholder | Meaning |
|---|---|
| `{server}` | The server root directory |
| `{buildgit}` | The server's `buildgit/` |
| `{world-folder}` | This build world's world folder (named by `world-name` below, located where Minecraft puts it, e.g. `bw_alice_castle/` on Paper 1.21.11, `world/dimensions/minecraft/bw_alice_castle/` on 26.x) |
| `{id}` | The build world's id (a short random string made at creation, never changing) |
| `{owner}` | The owner's UUID (unchanged by transfers; the owner at creation) |
| `{repo}` | The location `repo` above works out to (usable in `objects` and `genesis`) |

No name placeholders (build world name, player name) are offered: paths would stop matching after renames.

**Keeping the whole build world in its world folder** (zipping one folder takes the world, history, genesis and settings along, like `<save>/buildgit/` in Fabric single-player):

```yaml
    repo: "{world-folder}/buildgit"
    objects: "{repo}/objects"
```

Then forks copy objects with hard links (§5.3); deleting or resetting this world with other tools deletes its history too.

**Rules**:
- **Settings only affect worlds created later.** At creation only `{id}` and `{owner}` are filled in; other placeholders are recorded as they are in the index `buildgit/worlds.yml`. When settings change later, old worlds are found via the index, so data does not "disappear".
- **`{server}` and `{buildgit}` are also worked out at each run**: after moving the whole server folder elsewhere (new machine, new path) everything is still found.
- **`{world-folder}` is worked out at each run**: when Paper upgrades from 1.21.11 to 26.x, world folders move from the server root to `world/dimensions/minecraft/`; data inside the world folder moves along, and BuildGit always looks at the folder's current location, so it is still found after the upgrade.
- **No collisions**: `repo` and `genesis` must differ per world (the template must contain `{id}` or `{world-folder}`, or contain `{repo}`), otherwise creation is refused with an explanation on the console. `objects` can be shared.
- The index `buildgit/worlds.yml` has a fixed location: it only records "which build worlds exist, where and whose", so the list and unloaded worlds can be looked up.
- Moving existing worlds is for later (e.g. `/bg world move`); for now, stop the server, move by hand and edit the index.

**Other locations**:
- **World folder names follow the name the player gave**, in a format set by the admin:

  ```yaml
  player-worlds:
    world-name: "bw_{owner-name}_{name}"         # build world
    draft-world-name: "{world}-{draft}"          # draft
    merge-world-name: "{world}-{draft}"          # conflict resolution ({draft} is merge-…)
    view-world-name: "bgview_{world}_{commit}"   # viewing old versions
  ```

  Draft, resolution and view templates can also use `{world}` (the world folder worked out above), `{draft}` and `{commit}` (the first 10 characters of the version id); drafts and resolution must have `{draft}`, views must have `{commit}`, and all must have `{world}` or `{id}` (so different worlds do not clash). A draft records its folder in the repository's `branches.yml` (`<draft>.folder`) at creation, so old drafts are still found after the template changes; drafts made by older versions without this record are `<world folder>-<draft>`. View worlds named from the template are recorded in `views.yml` and deleted the next time it opens. On renames and transfers, recorded drafts are renamed to the current template. The server world's drafts and views are not affected (`bg_<draft>`, `bgview_<commit>`).

  Placeholders: `{name}` (the build world's name), `{owner-name}` (the owner's player name, lowercase), `{owner}` (the owner's UUID), `{id}`. For example, alice's `castle` is `bw_alice_castle/` in the server root on Paper 1.21.11, and `world/dimensions/minecraft/bw_alice_castle/` on 26.x (the world's key is that name too).
  - The template must contain `{name}`; **a prefix is optional**, e.g. `world-name: "{name}"` makes alice's `castle` folder `castle/`.
  - When the template has none of `{owner-name}`, `{owner}` and `{id}`, names are unique across the whole server: first come, first served, so bob cannot also use `castle` (commands still write `alice/castle`).
  - Any clash in the worked-out name refuses creation or renaming, telling the player why:
    - another world, draft or folder already has this name (including ones in the trash that have not expired);
    - the server world's name (`level-name` and its `_nether`, `_the_end`);
    - when worlds live in the server root (Paper 1.21.11), folders and files already there, such as `plugins`, `logs`, `config`, `mods`, `libraries`, `cache`, `versions`, `crash-reports`, `buildgit`;
    - BuildGit's prefixes (`bg_`, `bgview_`).
  - Draft folders are `<world name>-<branch>`, and creating worlds and drafts check each other, e.g. when `castle` already has a draft `castle-d1`, no world named `castle-d1` can be created.
  - **Folders change on renames and transfers**: people inside are sent back to the server world, this world and its drafts are unloaded, the folders renamed (draft folders too), the index updated, then it loads again and people can `/bg open` back in. On the same disk a rename only renames folders, so it is fast.
  - **The owner changed their player name with Mojang**: the folder is renamed to the new name before the world next loads (when nobody is inside).
  - Inside, build worlds are still identified by `{id}`: the index, players' last positions, per-world inventories and backup lists all record ids, so none of them break after a rename. Path templates (`repo`, `genesis`… above) still offer no name placeholders; use `{world-folder}` to follow the world folder.
  - After a rename, both LuckPerms' own `world` context and BuildGit's `buildgit:world` context change to the new name (design §8.1); the owner is told when renaming.
- View and resolution world folders are kept separately as before, and can always be rebuilt from versions.
- Player data: `buildgit/players/<uuid>.yml` (last position in each build world; the game mode to restore when leaving; the stored original inventory when inventory isolation is on); each world's own inventory is in `buildgit/players/<uuid>/<world id>.dat`.
- A build world has only one dimension: in its tree it is always called `minecraft:overworld` (also for Nether and End types), so forks, templates and single-player integration never need to convert dimension paths; the actual dimension type is recorded in the generation settings in `repo.yml`.

### 6.2 Code

- **The biggest change**: now there is one `Workspace` per server, finding the overworld by dimension id. This becomes one `Workspace` per repo, with a registry mapping "world → which dimension of which repo"; trackers, commands, the bossbar, sync and backups first find which repo the player or world belongs to. The server repo is the first one in the registry and behaves as before.
- `Repository` can be given an object directory (`objects` in §6.1).
- **No genesis copy needed**: a new build world starts with an empty genesis, and chunks are added to the genesis world the first time they are generated (the mechanism in design §2.3, which is how new overworld chunks already work), so creation is instant.
- The block under a void world's spawn is the first save at creation (authored by the owner, message "Create"), not a direct world change: the genesis is a snapshot at generation time, and placing a block directly would make the world disagree with "genesis + overrides".
- Draft worlds are created with the generator, seed and dimension type from `repo.yml`, the same as the build world (design §2.3 requires drafts to use the same generator as main).
- Creating worlds: Paper uses `WorldCreator` (generator, seed, environment), Fabric uses Fantasy; superflat and void use vanilla's flat generator settings on both (void is flat with no layers).

### 6.3 Offsite backups

- Each build world writes its own snapshots: the remote gets an extra level `<prefix>/worlds/<id>/snapshots/` and `files/`; objects are shared in `<prefix>/objects/`, so forks send nothing extra.
- `repo.yml` and the generation settings are backed up with the snapshot. Restoring rebuilds the world from the generation settings (generator, seed, dimension type); the genesis world and history are restored as now, back to the location recorded in the index.
- `/bg backup fetch` and `restore` get a parameter for which build world; the default is the server world.

### 6.4 Cross-version history

- Each build world has its own `data-version`; when the server upgrades, reads of all build worlds are upgraded as usual (design §4.5).
- **A fork must inherit the source's `legacy`**: old repositories contain objects without a data version, whose version is decided by the repo's `legacy`. When forking an old server world, whether objects are shared or hard-linked, the new repo must use the source's `legacy`, or the same object would be read as different versions. Newly created worlds are not affected (new objects all carry versions).
- **Settings are stored in our own format**: game rule names (changed to `snake_case` from 1.21.11), superflat layer settings and biome ids may be renamed or change format between versions, so `repo.yml` stores BuildGit's own keys (e.g. `time: noon`, `layers: [{block, count}]`), which each version's code converts to that version's form. Biome and block ids are still upgraded with DataFixer when read.
- **Starting kits and isolated inventories** are stored as vanilla item NBT with the data version, and upgraded with DataFixer when read (item ids and component formats change often from 1.20.5 on); they are backed up together like `players.yml`.
- After upgrading Minecraft, places in a build world that **have never been generated** are generated by the new version's generator. Places already generated are in the genesis world and are not affected; superflat and void never change anyway.
- Forks, templates and taking to single-player can all cross versions: genesis chunks and objects carry data versions and are upgraded by the existing rules; a world made by a newer version cannot be forked onto an older server (cannot happen on the same server, but restores and single-player must check).

## 7. Phases

1. **Several repos on one server (underneath)** (implemented): the registry, one `Workspace` per repo, a shared object directory. An admin command creates a new build world, and saving, opening drafts, publishing, viewing and restoring in it all work; the server world's behavior and tests are completely unchanged.
   - Acceptance: with the server world and two build worlds at once, saves and publishes in each do not affect the others; all come back after a restart; a build world made on 1.21.11 works after moving to 26.x.
   - What was implemented:
     - Commands `new <name>`, `open <world>`, `home`, `worlds`, all needing `buildgit.world.admin` (in the admin permission set). No members, roles or visibility yet: whoever can get in can act with their own `buildgit.*` permissions.
     - Terrain follows the server overworld's generation settings (same type and seed); the create menu with type and terrain choices is phase 2.
     - The genesis world starts empty, and chunks are recorded the first time they are generated.
     - Draft worlds are called `<world folder>-<draft>`, view worlds `bgview_<world folder>_<save>`.
     - When the world folder is missing (e.g. only the repository was backed up), it is rebuilt from the genesis world and versions the next time it opens.
     - Unloaded after `worlds.unload-idle-seconds` with nobody inside, no drafts, no views and no unsaved changes; loaded automatically when someone rejoins into it.
     - World folder backups do not include build worlds' drafts (they can be rebuilt from versions). Offsite backups covered build worlds in phase 3.
2. **Player build worlds**: `new` (create menu, three types, three terrains, rule presets), `open`, `home`, `worlds`, members and roles, private and public, switching game mode on entering and leaving (with optional separate inventories), spectator limits for visitors, portals off.
   - Implemented: creating (`/bg new`).
     - Creating uses the vanilla dialog (available from 1.21.6): name, seed, superflat layers and the block under void spawn are text fields; type, terrain, rules (and template, when there are templates) are options. Pressing "Create" makes the client run `/bg new <name> type … seed … confirm` (an empty field means an option name followed directly by the next option name, treated as not given), so permissions and checks are the same as typing the command. The dialog cannot change terrain options by type, so large biomes or amplified with Nether or End are refused by the command. Options written in the command (`/bg new castle type nether terrain flat layers bedrock,3*netherrack;minecraft:crimson_forest`) are filled into the dialog first. Option names and values are two separate words (no colons or equals signs: the client's single-word arguments do not accept colons, and typing one stops completion), and option names cannot be world names. Tab completion first lists option names (`type`, `terrain`…, without ones already written), and only lists possible values once an option name is typed (`block` lists blocks, `layers` lists examples and completes blocks and biomes).
     - Regular players need `player-worlds.enabled: true` plus `buildgit.world.create`, at most `player-worlds.max-per-player` each (3 by default); `buildgit.world.admin` has no limit.
     - Fabric uses vanilla world presets and superflat settings; on Paper, normal terrain uses `WorldCreator`, and superflat and void use BuildGit's own generator (Paper only lets the overworld type use vanilla superflat). Superflat generates no structures; templates came in phase 3 (see below).
     - Build rules: game rules are set on every load (Fabric worlds do not remember them), time stops at noon (a paused clock on 26.x; Nether types have no clock), peaceful. All build worlds turn off spectator chunk generation and Nether portals. End types have no ender dragon.
     - The spawn point is decided when someone first goes in: a standable spot in the (0, 0) column (searched from the bottom up for Nether types); void places a block at (0, 63, 0) and saves it as the first save "Create".
   - Implemented: members, roles, visibility.
     - `/bg world members [list|add <player> [builder|manager]|remove <player>]`, `/bg world visibility public|private`, run inside that world. The player must be online to be invited (the name is recorded in `repo.yml`, and removal looks up by name).
     - In build worlds, the nodes included in permission sets (`buildgit.role.*`) are decided by the role, not the nodes the server gave; `buildgit.world.admin` always can. Server administration like `init`, `reload` and `backup` still checks nodes. The command list is resent on changing worlds and when the role changes.
     - Visitors: look-only commands such as `status`, `log`, `diff` and `inspect` work; they cannot enter drafts, view old versions or change blocks (the same set of hooks as view worlds).
     - Everyone can use `open`, `worlds` and `home`; private worlds do not exist for non-members. When a member is removed or the world goes private, people inside (including drafts and view worlds) who no longer qualify are sent back to the server world; this is also checked on login.
     - LuckPerms contexts `buildgit:world` (always present) and `buildgit:role` (in build worlds, inclusive downward).
     - The menu version of member management and the `/bg worlds` menu came in phase 3 (see below).
   - Implemented: handling on entering and leaving.
     - Game mode: in build worlds, members are creative and visitors spectator (admins count as members); the original mode is recorded the first time they come from the server world and switched back when they return there. A role change switches immediately. The mode and the `home` position are recorded in `buildgit/players/<uuid>.yml` (as names, independent of version), so they are restored after restarts, crashes and being removed while offline. Changing "the mode on entering", inventory isolation and starting kits waited for the phase 3 settings.
     - World border: set on every load to a square centered on (0, 0) with side `player-worlds.border` (1024 by default); each world has its own border, not affecting the server world. Owners can change it later (phase 3 settings).
     - Visitors outside the border are pulled one block inside it every second, with one notice.
     - WorldEdit and Axiom: WorldEdit edits by people who cannot change the world (visitors, people in view worlds) are dropped entirely (replaced with an empty extent); Axiom cancels `AxiomModifyWorldEvent` on Paper, and drops world-changing packets on Fabric.
     - Portals: Nether portals rely on the game rule; in addition, no entity can use any portal in build worlds (including drafts and view worlds): Paper cancels `PlayerPortalEvent` and `EntityPortalEvent`, Fabric blocks it in `Entity.setAsInsidePortal`.
3. **Administration**: the settings menu, renaming, transferring, deleting and the trash, limits, forks and templates, submissions, admin unlisting, server world members and private mode (with the lobby), keeping loaded, offsite backups covering all build worlds.
   - Implemented: world settings (`/bg world settings`).
     - Settings: time, weather, difficulty, mob spawning, natural changes, world border (size and center), spawn point, members' and visitors' game modes, whether visitors can use mechanisms (`visitor-interact`). Without arguments it opens the settings menu, one slot per setting, clicking moves to the next value; they can also be set directly by command (`/bg world settings time 13000`). Owners and managers can change them, and changes apply at once to this world's loaded worlds (including drafts and view worlds).
     - Stored under `settings:` in `repo.yml`, in BuildGit's own format (time as ticks or `cycle`, weather and game modes by name, difficulty as `peaceful` or `server`), converted to game rules and world state by each version's code on every load. Worlds made in phase 2 have no `settings:` and follow the rule preset from creation (build/vanilla).
     - Rule presets only decide these settings' initial values: build is always noon and clear, peaceful, no mob spawning, no natural changes; vanilla is time passing normally, the server's difficulty, mob spawning, natural changes.
     - Weather: in 26.x vanilla, weather is one copy for the whole server, so Fabric build worlds used to rain along with the server world; now each world BuildGit opens has its own (Paper has always been per world, and so were Fabric 1.21.11's Fantasy worlds).
     - Border: `player-worlds.border` is the default, `player-worlds.max-border` (4096 by default) the limit.
     - Adventure-mode visitors: right-clicking blocks, trampling, entity interaction and attacks are blocked the same way building is, so chests cannot be opened either (stricter than §4.1's original "can see but not take", but no need to handle each container type). With `visitor-interact` on, buttons, levers, doors, trapdoors, fence gates and pressure plates are allowed; the changes they cause are recorded as made by that visitor, like any change.
   - Implemented: inventory isolation and starting kits (§2.2).
     - `/bg world settings inventory-member|inventory-visitor on|off`, `kit-member`/`kit-visitor [clear]` (the edit chest), `kit-member-reset`/`kit-visitor-reset`, `kit-member-respawn`/`kit-visitor-respawn`; `player-worlds.separate-inventories`, `allow-inventory-isolation`.
     - Older versions had a single `settings.inventory` (members and visitors together): members' inventories were always kept, visitors' reset every time, nothing given after death; reading old settings keeps that behavior (`inventory-member` and `inventory-visitor` take `inventory`, `kit-member-reset` off, `kit-*-respawn` off). Saving still writes `settings.inventory` (on when either role is on) for older versions to read.
     - Leaving a world with isolation on always stores what you are wearing; entering decides by "reset every time" whether to put on your last one or the starting kit (resetting also deletes the last one).
     - Giving again after death: recorded when the platform decides the respawn location, and the starting kit is put on the next tick once respawned, while still wearing this world's inventory.
     - On changing worlds (the same place game modes are switched) it checks which copy is being worn: `inventory` in `players/<uuid>.yml` records which build world's inventory it is (none means the original). To swap, it first writes what is worn to a file, records the new one, then puts on the target's; if the file cannot be written, nothing is swapped. Moving directly between worlds with isolation on also stores one and puts on the other. When settings change (turned on or off), people in the world are swapped at once.
     - What is stored: the inventory (including armor and offhand), ender chest, experience, status effects. Items are stored as NBT in vanilla `ItemStack` form with the data version, and upgraded on read with DataFixer (`ITEM_STACK`), e.g. 1.20.4's `tag.Damage` becomes the `minecraft:damage` component (tested). Status effects use our own format (effect id, duration, level); effects removed in newer versions are skipped.
     - Starting kits only store the inventory; when taking a starting kit, the ender chest, experience and effects are empty.
     - A role change inside the world (e.g. a visitor made a member) does not swap inventories; the new role applies the next time they come in.
   - Implemented: renaming, transferring, deleting and the trash, unlisting (§4, §5.2, §6.1).
     - `/bg world rename <new name>`, `transfer <player> confirm` (the player must be online), `delete confirm`, `undelete [name]`; admins `unlist`/`relist [world]`. Only the owner (and admins) can rename, transfer and delete.
     - Renaming, transferring: the name and worked-out folders are checked first; only then are people inside (including drafts and view worlds) sent straight back to the server world, all of this world's worlds unloaded, the repository closed, the world folder and draft folders renamed, and it opens again with the new registration. On a transfer the original owner stays as a manager; the receiver is limited by `max-per-player` too (except admins). When the folder name does not change (the template has no `{owner-name}`), folders are left alone and the rest proceeds as usual.
     - Deleting: the world folder, draft folders (which may have unsaved changes, so they are not deleted directly), repository and genesis world are renamed in place to `<name>.deleted-<time>`, only the outermost ones (a repository inside the world folder goes along); shared objects are untouched. The trash is recorded in `buildgit/trash.yml` (the original registration and the paths before and after renaming). Name and folder clash checks include the trash, so undeleting cannot clash and no new name is needed; undeleting is refused when something is already at the original location.
     - `player-worlds.trash-days` (7 by default): checked at startup and once a day, expired ones really deleted.
     - Unlisting is stored as `unlisted: true` in `repo.yml`: forced private, and the owner making it public is refused.
     - Players' per-world inventory files record the world id, so they still match after renames and transfers.
     - Not done yet: renaming folders after the owner changes their name with Mojang.
   - Implemented: the list and members menus, display name, description and icon, limits (§2.1, §3, §5.1).
     - `/bg worlds [mine|public|recent] [page]`: players get a menu whose top three rows are worlds (icon, display name, name, description, owner and your role, whether loaded and how many players, last save); click to go in; pages when full. Tabs: mine (own and invited, by name), public (by the time of main's last save, newest first), recently visited (at most 27). The bottom row has create, the trash, and this world's settings. The console still lists all build worlds. The server world is not in the list; going back to the server world is left to the server's own teleport commands (e.g. `/spawn`); BuildGit provides none.
     - Recently visited is recorded as `recent` in `players/<uuid>.yml` (world ids, so still matching after renames and transfers; deleted or inaccessible ones are not listed).
     - `/bg menu` gets two slots at the right end of the divider: the build world list, and this world's settings (owners and managers, inside a build world).
     - Three more settings: `display-name`, `description` (all the following words, keeping case; no text clears it; at most 32/120 characters, no `§` or control characters), `icon` (an item id; none uses the item in hand). Stored at the top level of `repo.yml`. Text written by players is always shown as plain text, never as MiniMessage. Menus cannot take typing, so clicking the display name or description opens a dialog with a text field; saving it empty clears it.
     - Icons across versions: item ids get renamed (e.g. 1.20.3's `grass` → `short_grass`), so they are upgraded on read with DataFixer (`ITEM_NAME`) based on `repo.yml`'s `data-version` (tested with `minecraft:grass` written at 3465); when this version has no such item (e.g. a downgrade), a grass block is shown.
     - The members menu (`/bg world members` without arguments, also in the settings menu): the top right is visibility, which the owner clicks to toggle; the middle two rows are members, click to open that person's menu (make builder, manager, or remove); the next row is online players who are not members, click to invite as builder. Managers only see and change builders. Members' roles can be changed while they are offline. `/bg world members list` is still the chat list. Heads have no skins.
     - `player-worlds.allowed-types`, `allowed-terrain`: regular players' create dialog only lists allowed ones, starting from the first allowed one when the default is not allowed; picking a disallowed one by command is refused. Admins are not limited.
     - `player-worlds.max-loaded` (20 by default): opening another build world's world (main, draft, view) is refused when the loaded worlds of all build worlds together reach the limit; the server world and its drafts do not count. Keeping worlds loaded came later in phase 3.
   - Implemented: forks and templates (§2.1, §3, §5.3, §6.4).
     - `/bg fork <owner/name> [new name]`: members always can; visitors need the owner to have "allow copies" on (setting `allow-fork`, on by default; private worlds have no visitors); admins always can. Limited by `enabled` and `max-per-player` (except admins). Without a new name, the source's name is used, adding `_2`, `_3`… on a clash (not a hyphen: the folder of `castle-2` would clash with `castle`'s draft).
     - The new world is private with no members; `repo.yml` copies the source's (generation settings, rules, border, spawn point, display name, icon…), starting kits are copied too, and it also records `fork.source` (the source's id) and `fork.commit` (the source main's save at the time) for submissions. Only main's latest save is taken: the source's unsaved changes and drafts are not.
     - Copying runs on the source genesis world's writer thread, in order with chunks still being added to the genesis world, without the main thread waiting; until it finishes the source cannot be renamed, transferred or deleted, and the new world's name and folder are reserved. On failure, what was copied is deleted.
     - The genesis world is copied (not hard-linked: the genesis world's region files still get chunks added later); with the same object location nothing is copied, otherwise the whole object directory is copied with hard links (really copied only when unsupported). No working out of "objects actually used": for simplicity everything is linked, and hard links take almost no space.
     - Cross-version: `data-version` is copied as is, so `legacy` follows the source; genesis chunks and objects keep their original data versions and are upgraded on read. A source on the same server is never newer than the server.
     - The world folder is synthesized from the genesis world + main's save the first time it loads (the same as after the world folder is deleted).
     - Templates: `player-worlds.templates` lists `owner/name`, or admins use `/bg world template on|off [world]` in game (recorded as `template: true` in `repo.yml`; older versions have no such key and read as not a template; forks do not carry it). The create dialog gets a "Template" option (default "No template"); picking a template follows it, ignoring type, terrain and rules; the command is `/bg new [name] template <owner/name> confirm`. Creating from a template is a fork (recorded as `fork.template: true`, so the list does not show "Forked from"), not limited by `allowed-types` or `allowed-terrain`, and the template itself may be private; the name is numbered automatically as in normal creation.
     - Forked worlds have an extra line "Forked from owner/name" in the list (not shown once the source is deleted). Visitors' bossbar shows `/bg fork to copy` when copies are allowed.
   - Implemented: submissions (§5.4).
     - The branch is called `submit-<player>-<number>` (draft names cannot contain `/`); players cannot name their own drafts starting with `submit-`. Submission data (the fork's id, the submitter, message, time) is recorded under `submit` of that branch in the source's `branches.yml`.
     - `/bg submit [message]`: in a forked world (not one created from a template), owners and managers can use it; it sends the last save of the current branch (main or a draft). When there are unsaved changes, it asks first: "Save first, then submit" (`--save`) or "Only send the last save" (`--last`). Messages are at most 120 characters, shown as plain text.
     - The source must allow copies (when the submitter is a visitor) and have "accept submissions" on (setting `accept-submissions`, on by default). Submitting again from the same fork updates the same branch (refused when it is the same as last time or the source already has these saves); refused when the same person already has an open submission to the same world from another fork. While someone at the source is looking at the submission (its world open, unsaved changes, resolving), it cannot be updated, and the submitter is asked to send it later.
     - When object locations differ, the fork's objects are hard-linked across; chunks generated after the fork are added to the source's genesis world (existing ones untouched), otherwise changes there would look like additions at the source. All of this runs in the background, and the branch is only created afterwards.
     - The source's owner and managers are notified. Notices for offline people are recorded as `notices` in `players/<uuid>.yml` and sent on their next login (rejection and merge notices too).
     - `/bg world submissions` (also in the settings menu): the menu lists open submissions (submitter, time, which fork, message); click to go in and look, like a draft; `/bg publish` inside to merge (conflicts go through the existing resolution flow). `list` is the chat list, and `reject <branch> [reason]` rejects: the branch is deleted and the submitter notified.
     - After it is fully merged into main (when publishing or conflict resolution finishes; also checked when viewing the list), the submitter gets "Merged" and the branch is deleted automatically; with only part merged (`--selection`) the submission stays.
   - Implemented: server world members and private mode, the lobby (§4.2).
     - In the server world (overworld, Nether, End and their drafts and views): `/bg world`, `/bg world members …`, `/bg world visibility …`; other settings, renaming and so on only exist for build worlds. Admins count as owners (can change visibility, invite managers), and managers on the member list can invite and remove builders. Stored in `buildgit/server-world.yml`.
     - Members' roles are **added on top of** the permission nodes the server gave (e.g. someone with no nodes can save as a builder), never taking away existing ones; game modes and inventories are untouched.
     - When private, people without `buildgit.role.viewer` or higher who are not members cannot be in the server world: on login, removal from members, the world going private, respawning after death, and being teleported in (checked every second), they are sent to the lobby.
     - The lobby `worlds.lobby`: "owner/name" is a build world, which must be public (cannot be made private or unlisted) and always stays loaded; a name or dimension id is a normal world (loaded by a multiworld plugin first, not the server world itself), and people are sent to its spawn. When unset or not found, making it private is refused; if the lobby disappears later, players stay where they are and the console warns once.
   - Implemented: keeping loaded (§4.3).
     - `worlds.keep-loaded` lists "owner/name" or "owner/name:draft" (server world drafts as "world name:draft"). After startup, after `/bg reload`, and every 30 seconds after that, they are matched and unloaded ones loaded; they are not unloaded when idle and do not count towards `max-loaded` (nor are they limited by it when loading).
     - Matched world ids are recorded in `buildgit/keep-loaded.yml` (id → the name written in the setting): after renames and transfers they are found by id and reloaded within 30 seconds.
     - The settings menu's title description gets an extra line "Kept loaded: main".
   - Implemented: offsite backups covering all build worlds (§6.3).
     - Each run of a history backup job also goes through all open build worlds (skipping ones being renamed, deleted and so on, until next time); those with changes each write a snapshot to `<prefix>/worlds/<id>/snapshots/`, and genesis world files go to `<prefix>/worlds/<id>/files/`. Objects and the record of "which saves were uploaded" are shared by the whole job (`<prefix>/objects/`), so forks send nothing extra. One build world failing does not affect the others; errors are reported at the end.
     - Build world snapshots also carry the registration (owner, name, folder, locations), `repo.yml` (type, terrain, seed, rules and other generation settings), `players.yml` and starting kits; no `level.dat`, since the world folder is rebuilt from the generation settings and genesis world. `players.yml` changes all the time, so it is only included when something else changed.
     - The remote `<prefix>/worlds.yml` records id → "owner/name", to find worlds by name on a new host; deleted worlds stay in it and can still be restored by id.
     - `/bg backup list <job> world=<world>`, `/bg backup fetch <branch> [new name] world=<world>`, `/bg backup restore <job> [snapshot] world=<world>` (console only). Without `world=`, the server world.
     - Restoring a build world needs no restart: it downloads to `…-restore` next to the repository, then unloads that world (people inside are sent away), renames the original world folder, draft folders, repository and an external genesis world to `….before-restore-<time>`, swaps in the downloaded ones and opens it again, rebuilding the world folder from the genesis world and saves. When this server does not have the world, it is put back with the snapshot's registration (refused when the name or folder is taken; when it is in the trash, undelete it first). A repository inside the world folder cannot be restored on its own.
     - No `/bg init` needed first on a new host: without a repository (or when it cannot be opened), `/bg backup status`, `list` and `restore` (server world) still work; everything else still needs init first.
     - Restoring the server world no longer touches build worlds: objects are downloaded straight into the shared object directory (only adding), and what in the repository folder does not belong to the server world (`worlds/`, `worlds.yml`, the trash, player data, other backup jobs' records and so on) is moved into the new repository.
     - Cross-version: snapshots record the Minecraft data version at write time and the repo's `data-version` (including `legacy`); ones written by a newer version are refused; genesis chunks and objects are upgraded by the existing rules.
   - Implemented: `pull`/`push` for build worlds (§2.1).
     - `/bg pull` in a build world (or its draft) takes an area of that branch of that world; the pull record also records `world: <id>` (records still live in `buildgit/sync/<player>/`, with one area name per player).
     - Permissions follow the role in that world: builders and above can `pull` and `push`; visitors can only `pull`, when "allow copies" is on, and cannot `push`; `buildgit.world.admin` can do both.
     - `/bg push` sends it back to the world it was taken from, saved there as a draft, regardless of where the player is now; the role is checked again on sending back, and it is refused when the world is deleted or being renamed or restored. Areas taken from the server world still need the server world and `buildgit.push` to send back.
     - For people who cannot use operator blocks, block entities they send back (e.g. signs) have click events cleared: sign click commands run with operator permissions. Ones already there when taken (e.g. made by an admin) are untouched.
     - The notice on connecting only lists areas that can be sent back now; after sending back to a build world, the button changes to "Go to that world" when the player is not there.
     - Cross-version: area files record the data version at write time and are upgraded with DataFixer by the existing rules on sending back; biomes are not carried (same as the server world).
   - Implemented: uploading single-player worlds as new build worlds.
     - `/bg new <name> from <save>` (`from` goes last, and the save name may contain spaces): the player needs the BuildGit client, and the server needs `player-worlds.upload.connection` (a connection in `s3.yml`); only the overworld type, void terrain when not given, other options as `/bg new`. The client first asks in chat whether to upload, and only starts after **[Upload]** is clicked.
     - The transfer does not go over the game connection: the server asks the client to pack the save's overworld (`region`, `entities`; 26.1's `dimensions/minecraft/overworld` is recognized too; uncompressed), and the client reports the size and SHA-256; the server issues a presigned PUT URL valid for 15 minutes and bound to the size (a random `uploads/<UUID>/<id>`, host `public-endpoint`), the client uploads directly, and the server downloads (with a limit, 512 MB by default), checks and deletes it.
     - Uploaded content is not trusted: unpacking only takes region and `.mcc` files with matching names, with a total limit; each chunk is read (with limits on size, depth and node count), upgraded to the current version, and checked for position and generation status, skipping and counting rejected ones. For people who cannot use operator blocks, uploaded operator blocks become air (the palette entry becomes an air not used yet, without duplicates) and their block entities are removed, other block entities have click events cleared, and command block minecarts (with passengers) are removed; entities listed in `ignored-entities` are dropped.
     - The checked chunks become the genesis world directly, and the first save is an empty "Create", with no history.
     - Cross-version: saves from a version newer than the server are refused entirely; older ones (from 1.13) are upgraded with DataFixer before being written into the genesis world.
     - Only the Fabric client; Paper servers can receive it too (same channel).
   - Implemented: importing a world already on the server as a build world (admins).
     - `/bg new <name> import <world folder> [owner <player>]`: the folder is relative to the server directory (cannot go outside it); it reads `region` when there is one, otherwise `dimensions/minecraft/overworld`.
     - When the world is still loaded it is saved first (the same method as saving, waiting for it to reach the region files), then `region` and `entities` are copied to a staging directory, and it goes through the same checks and upgrades as uploaded single-player worlds (operator blocks kept), creating it back on the main thread.
     - `owner` sets the owner (an online player); with `owner` the console can run it too, and nobody is teleported. A player running the command is teleported in once it is created.
   - Implemented: removing unused objects (`/bg disk clean`, see 02-admin). All repositories sharing the object directory are used as roots together (including the trash, forks in progress and what was left before restores), and only objects not written or used for more than a day are deleted; an object's modification time is updated when it already exists on write, or when it is confirmed to exist for use.
     - Cross-version: only the structure of commits and trees is read, block contents are not interpreted, so objects of old versions are marked as usual.
4. **Later**: favorites.

## 8. Decisions

| Item | Decision |
|---|---|
| Public | Everyone can come in to look and fork; only members can change it |
| Visitors | Spectator by default, owners can change to adventure; no new terrain, cannot leave the world border, WorldEdit/Axiom and changes to the world blocked |
| Server world | Also a build world, can have members and be private (private needs a lobby first) |
| Creation settings | Type (overworld/Nether/End), terrain (normal/customizable superflat/void with one block underfoot at creation/template), rule preset (build/vanilla) |
| Deleting | Renamed in place and kept for 7 days |
| Administration | Admins can unlist (force private), transfer and delete any world; no reporting, name filtering or automatic cleanup |
| Viewing old versions | Members only by default; owners can open it to visitors |
| Lobby | A normal world or a public build world; `worlds.keep-loaded` lists worlds that stay loaded |
| Submissions | Done in phase 3: `/bg submit` in a forked world, the source gets a `submit-` branch, merged after review with the existing publish flow |
| World folder names | Follow the name the player gave, format `world-name` configurable (default `bw_{owner-name}_{name}`, prefix optional); folders change on renames and transfers, identified internally by id |
| Inventories | Shared across all worlds by default; owners can turn on inventory isolation for their own worlds, and only then set starting kits for members and visitors; admins can force or forbid isolation |
| Storage locations | Repository, objects and genesis world each set with a path template; by default the repository is in `buildgit/worlds/<id>` with shared objects, and generation settings in the repository's `repo.yml`; `{world-folder}/buildgit` puts the whole build world inside its world folder |
