# BuildGit requirements (draft of 2026-10-14)

## 1. Goal and positioning

A multiplayer collaboration and version control tool for **creative-mode builders**.

How it differs from WorldGit: WorldGit diffs whole world saves, which picks up lots of changes not made by players (ticks, mobs, natural growth and so on), and its workflow is heavy. BuildGit follows CoreProtect's approach and **tracks only changes caused by players**, so a version's content is exactly "what the players built".

BuildGit is a new project. It may refer to or copy WorldGit's code (object storage, NBT, merging), but does not share a library with it.

## 2. Platforms and versions

| Platform | Versions |
|---|---|
| Paper plugin | 26.3, 26.2, 1.21.11 |
| Fabric mod | 26.3, 26.2, 1.21.11; both dedicated servers and single-player (single-player saves have their own repository; a selection can be taken to a single-player world, changed there and sent back to the server, see design §6.7) |

## 3. Change tracking

### 3.1 Direct sources

- Vanilla: placing, breaking, interacting with blocks (buckets, signs, container contents, item frames and so on).
- WorldEdit/FAWE.
- Axiom.
- Commands run by players: `/fill`, `/clone`, `/setblock` and so on.

### 3.2 Knock-on changes: causal tracking

Later changes caused by a player's action are also attributed to that player:

- Neighbor shape updates: stairs, fences, walls, glass panes, redstone wire and so on.
- Scheduled ticks: water flow, lava, falling sand/gravel, redstone.
- Entities spawned by players, and what those entities change later.

How: positions touched by a player are marked; neighbor updates, scheduled ticks and entity spawns caused from marked positions keep marking along the causal chain.

Changes not caused by players are not tracked, e.g. natural growth, mob behavior, weather, unmarked redstone clocks.

**Where it ends**: tracking continues until the changes stop. For states that keep repeating, such as redstone clocks, a commit stores only the current state. There is also a safety limit, e.g. at most 256 blocks from the source, configurable in YAML.

**Scope**: the whole branch world is tracked, with no area to mark out in advance; actions by players in every game mode are tracked.

### 3.3 How it is recorded

- Player actions are only used to mark "which positions or entities were touched", and who the author is.
- On commit, the **current final state** of these places is stored: block states, block entities (containers, signs), entities.
- Restoring does not rely on replaying events, so missing the intermediate steps of some event does not make a restore wrong.

## 4. Version control model

The full git model: commits, history, diffs, reverts, branches, merges, conflict handling.

| Item | Requirement |
|---|---|
| Commit scope | Takes all marked changes in that branch world since the last commit; each change keeps its author |
| Branches | **Each branch is a separate world**. Players teleport in and out themselves, and several people can work on different branches at once without disturbing each other |
| Creating branch worlds | **Lazy copy**: a branch world starts empty, and a chunk is produced from the parent branch's committed state only when it is first read |
| Permissions | OP only by default. Committing, branching, merging and restoring each have a permission node: assigned with plugins such as LuckPerms on Paper, with the Fabric Permissions API on Fabric |
| Merging | Merges a branch's changes back into the target branch's world |
| Conflicts | When both sides changed the same block, **two read-only comparison worlds** (mine, theirs) and **one resolution world** are created, all with the same coordinates, with commands to teleport quickly between them at the same position; the player resolves by hand in the resolution world, then commits |
| Attribution | Look up who changed a block or area, and in which commit |
| Remotes | No repository sync between servers; only "take a selection to a single-player world, change it there and send it back as a draft" (design §6.7). The storage format leaves room to add push/pull later |
| Backups | The repository and world folders can be backed up incrementally to S3-compatible object storage or a local folder, with each destination's own schedule (cron, server idle) and retention count; when the host fails, all branches and history are restored on a new host, and world folders can go back to a point in time (design §2.5–§2.7) |
| Build worlds (phase 1: created by admins) | Players can create their own build worlds, each a separate repo, which can be private or public, invite members, and fork others' public work ([03-worlds.md](03-worlds.md)) |

## 4.1 Experience (for builders)

| Item | Requirement |
|---|---|
| Three-step flow | `try <name>` opens a draft and teleports you in; `publish` previews first, then after confirming saves, merges and teleports you back; `back` goes back without merging |
| Autosave | When there are changes and everyone has stopped for a while (5 minutes by default), save automatically; manual saves are milestones, and history shows only milestones by default |
| Selections | `publish`, `restore` and conflict `take` can all act on just the WorldEdit/Axiom selection |
| Looking into the past | `view <version>` opens a read-only world with an old version, which can be copied back with WorldEdit |
| Draft indicator | While in a draft, the bossbar always shows the draft name and the number of unsaved blocks |
| Inspect tool | Hold it and click a block to show who changed it, in which save, and when |
| Chest menus | Browse history and drafts in chest menus, and preview, teleport or restore with one click; no client mod needed |
| Wording | The UI says "draft/save/publish/milestone"; English commands also keep the git names for experienced users |

## 5. Development conventions

- The code is written directly by Claude; this project **does not use Codex for editing**.
- Documents are in English (originally Traditional Chinese); settings files use YAML; messages use MiniMessage.
- Commits use the twme-ai identity and are signed.

## 6. Next steps

The requirements are all settled. The design document then needs to decide:

- the storage format and object model;
- the hooks for causal tracking on Paper (NMS) and Fabric (mixins);
- the chunk loading mechanism for lazily copied worlds;
- the merge algorithm;
- the list of commands and permission nodes.
