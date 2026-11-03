# BuildGit Admin Manual

For the people who set up and run the server. For how players use it, see the [player guide in the README](../README.md#player-guide); for how it works inside, see the [design document](01-design.md).

## 1. What BuildGit does

BuildGit records only **changes made by players** (by hand, commands, WorldEdit/FAWE, Axiom, and the knock-on changes they cause, such as water flow, redstone and falling blocks; also biomes changed by `/fillbiome`, `//setbiome` and the like) and stores them as versions, like git. Players can:

- open a "draft" (a copy of main) to try things, and publish it back to main (the copy of each world everyone sees) when they are happy;
- look through history, and see who changed a block in which save;
- revert a save, or restore a whole world or an area to an old version.

Natural changes (crop growth, mobs, weather) never go into versions.

## 2. Installation

1. Download the jar for your server version ([download table in the README](../README.md#download)) and put it in `plugins/` (Paper) or `mods/` (Fabric). On a version mismatch the plugin disables itself.
2. Fabric also needs Fabric API.
3. To record edits from WorldEdit, FAWE, Axiom or DebugStickPro, just install them. Paper supports all of them; the Fabric version supports the WorldEdit mod (Fabric has no FAWE) and the Axiom mod, and has no DebugStickPro support yet. The vanilla debug stick is recorded on both platforms.
4. Start the server. At this point **no world is under version control yet**, and BuildGit records nothing.

## 3. Starting version control: `/bg init`

Stand in the world you want to manage and run `/bg init`.

- **Init each world separately**: init in the overworld does not cover the Nether or the End; worlds from multiworld plugins such as Multiverse are the same: go into that world and init there.
- Init copies the world as it is now into a "genesis world". **This runs in the background and does not pause the server.** Until the copy finishes, changes in this world are not saved yet and drafts cannot be opened; you are notified when it is done.
- The genesis world is about as big as that world's folder, so check your disk space first. If your fast disk is not big enough, `init.genesis-dir` can put the genesis world on another, larger and cheaper disk (see §6); it is still a full copy and the lazy copy rules below do not apply.
- **Large worlds can use a lazy copy** (`init.lazy-copy-above-mb`, 512 MB by default): when the world's save is bigger than this, init does not copy the whole world and finishes at once; each chunk is copied into the genesis world as it is the first time it loads, and chunks that have never loaded are read from the world's own save. The genesis world only contains the areas players actually visited. The price is that you must follow these rules, or the genesis of never-loaded chunks gets changed silently and cannot be recovered:
  - Do not open this world without BuildGit running: that includes starting the server with the plugin removed, opening it in single-player, and the server's `--forceUpgrade`/`--eraseCache` startup flags;
  - Do not change its save with offline tools (MCA Selector, deleting or trimming region files, swapping the world folder);
  - Do not restore or move the world folder and `buildgit/` separately (§8).

  Upgrading Minecraft works as usual (§9): the new server copies the old state of an old chunk before loading it. Pregeneration plugins (such as Chunky) work too, but every chunk they load gets copied, so the genesis world grows accordingly. Worlds already initialized are not affected by this setting.
- Worlds that are not initialized are not affected at all.

## 4. Permissions

By default only OPs can use any command (Fabric: permission level 2 without a permission mod). We recommend giving **permission sets** with a permissions plugin (such as LuckPerms):

| Permission set | For | What they can do |
|---|---|---|
| `buildgit.role.viewer` | visitors, judges | the menu; see status, history, save contents and who changed blocks (`status`, `log`, `diff`, `inspect`, `sel`, `branch list`); enter and leave drafts, view old versions read-only (`go`, `back`, `view`) |
| `buildgit.role.builder` | regular builders | everything in viewer, plus open drafts (`try`), save (`save`), take areas to single-player and send them back (`pull`, `push`). **Cannot publish drafts to main** |
| `buildgit.role.manager` | people who review and maintain main | everything in builder, plus publish (`publish`), resolve conflicts and low-level merges (`merge`), revert (`revert`), restore (`restore`), mark manually (`mark`), delete drafts (`branch delete`) |
| `buildgit.admin` | server admins | everything in manager, plus `init`, `reload`, `disk`, `genesis`, `backup`, and managing all build worlds (`buildgit.world.admin`) |

LuckPerms example:

```
/lp group builder permission set buildgit.role.builder true
/lp group staff permission set buildgit.role.manager true
```

`builder` can only save and open drafts, not publish drafts to main (`publish`); to let them publish, give `buildgit.publish`, or give `buildgit.role.manager`.

When a permission is missing, the message names the node (e.g. "needs `buildgit.branch.delete`"), so you can just add it.

A common setup: every player can create their own build worlds, and WorldEdit is only for members of a build world:

```
# Allow creating build worlds (also set player-worlds.enabled: true in config.yml)
/lp group default permission set buildgit.world.create true
# WorldEdit only works in build worlds where the player is a builder or above (visitors are always blocked anyway)
/lp group default permission set worldedit.* true buildgit:role=builder
```

Inside build worlds, BuildGit's own commands follow the player's role (§7), so you do not need to give `buildgit.role.*` for them.

**Permissions by where the player is**: with LuckPerms installed, BuildGit provides these contexts (design §8.1), so any plugin's permissions can be limited to certain places:

| Context | Values |
|---|---|
| `buildgit:location` | `main`, `draft`, `view` (viewing a past version), `resolve` (conflict resolution) |
| `buildgit:branch` | the branch name (e.g. `main`); during resolution, the branch being merged into |
| `buildgit:world` | the server world's name (usually `world`), or `owner/name` of a build world |
| `buildgit:role` | the role in a build world: `owner`, `manager`, `builder`, `visitor`; roles include the ones below, so an owner is also `manager` and `builder` |

```
# WorldEdit only in drafts, not in main
/lp group builder permission set worldedit.* true buildgit:location=draft
# Only the draft house can be published
/lp group builder permission set buildgit.publish true buildgit:branch=house
# Everyone who can build in a build world can use WorldEdit
/lp group default permission set worldedit.* true buildgit:role=builder
# See a player's current contexts
/lp user <player> info
```

Changes take effect within a tick after a player changes worlds. Outside worlds managed by BuildGit, these contexts are not set. To turn them off, set `integrations.luckperms-contexts: false` in `config.yml`.

You can also give permissions one by one: **every command has its own node, and nodes do not include each other**. For example, with only `buildgit.publish` a player can publish but not open drafts.

| Node | Grants |
|---|---|
| `buildgit.menu` | opening the menu when typing just `/bg` (each button in the menu still checks its own permission) |
| `buildgit.status` | `status`: the current branch and unsaved changes; also whether autosave notices are shown |
| `buildgit.log` | `log`: save history; `log --graph` branch history graph |
| `buildgit.diff` | `diff`: outline differences in the world; `diff <save>` also lists who changed how many blocks in that save |
| `buildgit.inspect` | `inspect`: inspect mode, click blocks to see who changed them |
| `buildgit.sel` | `sel`: a selection without WorldEdit |
| `buildgit.go` | `go`: go to a draft or main |
| `buildgit.back` | `back`: return to the source world |
| `buildgit.view` | `view`: view an old version read-only; `play`: play back history |
| `buildgit.try` | `try`: open a draft |
| `buildgit.save` | `save`: save |
| `buildgit.pull` | `pull`: take the selection to single-player |
| `buildgit.push` | `push`: send an area changed in single-player back as a draft |
| `buildgit.publish` | `publish`: publish a draft |
| `buildgit.merge` | `merge <branch>`: low-level merge of any branch |
| `buildgit.merge.resolve` | `merge view`/`conflicts`/`goto`/`take`/`continue`/`abort`/`list`: resolve conflicts. People with `publish`, `revert` or `merge` can resolve too |
| `buildgit.revert` | `revert`: undo a save |
| `buildgit.restore` | `restore`: restore a branch or an area to an old version |
| `buildgit.mark` | `mark`: mark an area manually |
| `buildgit.branch.list` | `branch list`: list drafts and who is in them |
| `buildgit.branch.delete` | `branch delete`: delete a draft and its worlds |
| `buildgit.init` | `init`: put the current world under version control (makes a genesis copy that takes about as much disk space as the world) |
| `buildgit.reload` | `reload`: reload settings and language files |
| `buildgit.disk` | `disk`: disk space used by BuildGit; `disk clean` removes unused objects |
| `buildgit.genesis` | `genesis upgrade`: rewrite the genesis world in the current version's format (§9) |
| `buildgit.backup` | `backup`: offsite backup status, back up now, list snapshots, fetch branches, restore (§8.1) |
| `buildgit.world.create` | create your own build worlds (`new`, §7.1, needs `player-worlds.enabled: true`), `fork`, `submit` |
| `buildgit.world.admin` | build worlds (§7.1): create without the limit, enter and manage anyone's build world, `world unlist|relist`, `world template` |

`open`, `home`, `worlds`, `world` and `help` need no node: which worlds a player can enter depends on the member list and visibility, and what they can do under `world` depends on their role in that world.

Notes:

- BuildGit decides which sets include which itself, the same on Paper and Fabric, so permission sets also work on Fabric.
- Because of this, **setting a single node inside a permission set to false does not take it away**. To give one thing less, give nodes one by one instead of using a set.
- Players do not see subcommands they lack permission for; `/bg help` also lists only what they can use, with the most common ones for where they are (main, a draft, a resolution world) first; `/bg help admin` lists admin commands.
- When there are conflicts, people with `publish`, `revert` or `merge` can also resolve them (`merge.resolve` is for people who only resolve conflicts).
- Building itself needs no BuildGit permission: as long as the world is under version control, every change is recorded, whoever made it.

## 5. Settings

Settings file: `plugins/BuildGit/config.yml` on Paper, `config/buildgit/config.yml` on Fabric. After changing it, run `/bg reload`; it lists which values in `config.yml`, `backup.yml` and `s3.yml` changed since the last load (for keys it only says they changed, without showing them).

- **New settings are added automatically after plugin updates**: when a new version adds settings, startup (or `/bg reload`) uses the new default file as a template and fills in your values, so the new settings appear with their descriptions. The old file is kept as `config.yml.old`; your own comments and settings that are no longer used are not kept, so look there if you need them. A settings file with a syntax error is left untouched.
- **Language files**: messages are in `lang/zh_tw.yml` and `lang/en_us.yml` in the settings folder. You can edit them directly (MiniMessage format) and run `/bg reload`. After a plugin update, messages you did not change are replaced with the new version, new messages are added, and your changed ones are kept; the old file is kept as `.old`. `lang/.bundled/` holds the bundled version written last time, used to tell which messages you changed; do not edit it.
  - If a new version changes the placeholders (e.g. `<name>`) of a message you changed, adjust it yourself to match.
  - Other languages: put `<language>.yml` in `lang/` (named with the Minecraft language code, e.g. `ja_jp.yml`); missing messages fall back to English.
- **Each player sees their own language**: players see the language set in their game, when there is a lang file for it; other regions of the same language count too, e.g. a `zh_cn` player sees `zh_tw`. Without a matching lang file, and on the console, `language` is used. To show everyone the same language, set `language-per-player` to `false`.
- **Console logs are always English**: server logs (warnings, errors, startup messages) and the comments in the settings file templates are in English, so they are easy to paste into issues or search for; replies to commands typed on the console follow `language`.

Settings you will commonly adjust:

| Setting | Default | Description |
|---|---|---|
| `language` | `en_us` | The console's language, also used for players whose game language has no lang file (`en_us`, `zh_tw`, or your own lang file). Servers upgrading from an older version keep their setting |
| `language-per-player` | `true` | `true`: each player follows their game's language; `false`: everyone uses `language` |
| `autosave.idle-seconds` | 300 | Save automatically after every player in the world has stopped for this long; 0 turns it off |
| `autosave.max-interval-seconds` | 1800 | While people keep building, save at least this often; 0 turns it off |
| `worlds.unload-idle-seconds` | 60 | Unload a draft world after it has been empty this long (see §7); -1 never unloads |
| `worlds.lobby` | empty | Lobby: when the server world is private, people without permission who are not members are sent here. A build world as "owner/name" (must be public, always stays loaded), or a normal world by name or dimension id (e.g. `lobby`, `minecraft:lobby`; create it yourself with a multiworld plugin). When empty, the server world cannot be made private |
| `worlds.keep-loaded` | `[]` | Worlds that stay loaded: loaded at startup, never unloaded when empty, not counted in `max-loaded`. Write "owner/name" or "owner/name:draft"; they still match after renames and transfers (recorded in `buildgit/keep-loaded.yml`). **Each one always takes memory**, so only list the lobby and popular builds |
| `sync.max-blocks` | 12000000 | The most blocks `/bg pull` takes at once |
| `sync.radius` | 80 | Without a selection, and outside a changed draft, how far around the player `/bg pull` takes (horizontal radius, full height) |
| `init.genesis-dir` | empty | Which folder holds the genesis world (relative to the server directory, or absolute); empty means `buildgit/genesis`. Needs a restart (see §6) |
| `init.lazy-copy-above-mb` | 512 | Init uses a lazy copy when the world's save is bigger than this (MB) (see §3); 0 always copies lazily, -1 always copies fully |
| `integrations.worldedit`/`axiom`/`debugstickpro` | true | Whether to record WorldEdit/FAWE, Axiom and DebugStickPro edits |
| `player-worlds.enabled` | `false` | Whether regular players (with `buildgit.world.create`) can create build worlds |
| `player-worlds.max-per-player` | 3 | How many build worlds each player can own |
| `player-worlds.border` | 1024 | The default world border size of build worlds (centered on (0, 0)), which is also how far visitors can go; owners can change it with `/bg world settings border` |
| `player-worlds.max-border` | 4096 | The largest border size owners can set |
| `player-worlds.inventory-isolation` | `owner` | Inventory isolation: `owner` lets owners decide for their own worlds, `always` isolates in every build world (recommended for survival servers), `never` never isolates. The old `separate-inventories` and `allow-inventory-isolation` are converted to this automatically |
| `player-worlds.defaults.*` | | The initial settings of new worlds: `rules` (`build`/`vanilla` preselected in the create menu), `visibility`, `member-mode`, `visitor-mode`, `visitor-interact`, `inventory-member`, `inventory-visitor`, `allow-fork`, `accept-submissions`, `respawn`; owners can still change them after creation |
| `player-worlds.max-loaded` | 20 | How many build worlds can be loaded at once; each draft and view world counts as one (the server world does not). When full, players opening a new world are refused and asked to try later |
| `player-worlds.allowed-types`/`allowed-terrain` | `[]` (all) | The types (`overworld`, `nether`, `end`) and terrains (`normal`, `large-biomes`, `amplified`, `flat`, `void`) regular players can pick; admins are not limited |
| `player-worlds.unmanaged-settings` | `[]` | World rules left to other plugins: `time`, `weather`, `difficulty`, `mobs`, `nature`. BuildGit does not set the listed ones in build worlds (it does not change game rules, time, weather or difficulty), the world settings menu does not list them, and `/bg world settings` cannot change them. Game rules already set stay in the world; the server world is never affected |
| `player-worlds.templates` | `[]` | Build worlds that can be used as templates (`owner/name`); you can also set this in game with `/bg world template on|off [world]` (stored in that world's `repo.yml`). The create dialog gets a "Template" option; creating from a template forks it, without the allowed-* limits |
| `player-worlds.trash-days` | 7 | How many days deleted build worlds stay in the trash; expired ones are deleted for good at startup and in a daily check (the trash is recorded in `buildgit/trash.yml`) |
| `player-worlds.world-name` | `bw_{owner-name}_{name}` | The folder name of build worlds (see §7.1); only affects worlds created later |
| `player-worlds.draft-world-name`/`merge-world-name`/`view-world-name` | `{world}-{draft}`/`{world}-{draft}`/`bgview_{world}_{commit}` | World folder names for drafts, conflict resolution and viewing old versions of build worlds (see §7.1); a draft remembers its folder when created, so old drafts are still found after you change this |
| `player-worlds.paths.*` | | Where the repositories, objects and genesis worlds of build worlds go (see §7.1) |
| `player-worlds.upload.connection` | `""` | The `s3.yml` connection used for uploading single-player worlds; uploads are off when empty |
| `player-worlds.upload.public-endpoint` | `""` | The S3 URL players' computers can reach; when empty, the connection's `endpoint` |
| `player-worlds.upload.max-mb` | `512` | Size limit of an uploaded save after packing (MB) |
| `tracking.*` | | The time window and distance for knock-on changes, and which entities are not recorded; usually no need to change |

The first part of `config.yml` is what you usually adjust; you rarely need to touch anything below "Advanced" (`sync`, `init`, `tracking`). Missing settings are added automatically after a plugin update (rewritten in the new order with descriptions, the old file kept as `config.yml.old`); unknown settings (usually typos or the wrong level) are warned about on the console at startup and on `/bg reload`.

## 6. Where data lives and how much space it takes

| Location | Contents |
|---|---|
| `<server directory>/buildgit/` | Repository: version history of all worlds, genesis world copies, unsaved marks, conflict resolutions in progress |
| `world/dimensions/minecraft/bg_<draft>…` | Each draft's worlds (the Paper 26.x location; older versions and Fabric follow the server's world folder rules) |
| `…/bgview_<save>…` | Temporary worlds for viewing old versions read-only, deleted after use |

`/bg disk` (needs `buildgit.disk`) lists:

- **Version history**: shared by all branches with identical content stored only once, so it cannot be split per branch; usually small.
- **Genesis world copies**: about the size of those worlds at init; for lazily copied worlds, only the areas players have visited.
- **Each draft's worlds**: only areas walked through or generated are written to disk. Delete drafts you no longer need with `/bg branch delete` to get the space back.

**Removing unused objects**: version history left behind by deleted drafts and build worlds deleted for good does not disappear on its own. `/bg disk clean` deletes objects no branch uses, in the background, and reports how many it deleted and how much space it freed.
- What is kept: the branches of the server world and every build world (including worlds in the trash and forks in progress), resolutions in progress, areas players pulled and have not sent back, and the `*.before-restore-*` left before a restore.
- Objects written or used within the last day are not deleted, saves in progress are not affected, and the server keeps running.
- Cleanup is refused when build world or trash entries were malformed and skipped at startup (their objects are unknown); fix them and restart first.
- Deleted objects cannot be recovered; check that your offsite backups work first if needed.

**Putting the genesis world on another disk**: set `init.genesis-dir`. The genesis world is only read when saving, opening drafts and viewing old versions, so a regular hard disk or NAS is fast enough. To move it after init: stop the server, move the whole old genesis world folder (`buildgit/genesis` by default) to the new place, change the setting, then start. If the setting changed but the data has not moved yet, BuildGit keeps using the old place and warns on the console. The location actually in use is recorded in `buildgit/genesis-dir`.

A draft belongs only to the world it was opened in (the Nether and the End are separate too): a draft opened in the overworld has only the overworld, one opened in the Nether only the Nether. `/bg branch list` lists the drafts of the current world with their latest save and time; add `--all` to list drafts of all worlds, which helps find drafts nobody has touched in a long time.

**Do not give other worlds names starting with `bg_` or `bgview_`**: BuildGit names its own worlds with these prefixes, and folders starting with `bgview_` are deleted at startup as leftover temporary worlds.

## 7. Loading and unloading worlds

- **Draft worlds**: loaded only when needed (`try`, `go`, publishing, a player rejoining into a draft). After all of a draft's worlds have been empty for longer than `worlds.unload-idle-seconds`, they are saved to disk and unloaded; they load automatically when someone goes in again, and nothing is lost.
- **main's worlds** (the original worlds under version control): when a multiworld plugin tries to unload one, it is allowed only when **neither it nor the matching world of any draft has players**, nothing is saving or merging, and its genesis world is not being copied. Otherwise BuildGit blocks it and explains why on the console (Fabric cannot block it).
- After main's world is unloaded, unsaved changes are kept and picked up again when it loads.
- **Build worlds** (§7.1): unloaded after `worlds.unload-idle-seconds` when empty, with no drafts or views open and no unsaved changes; they load automatically on `/bg open` or when someone rejoins into them. Those listed in `worlds.keep-loaded` (and a build world used as the lobby) always stay loaded.
- **Nothing can be written while a world is unloaded**: versions are recorded first and then written into the world, so as long as any versioned world of the branch is unloaded, publishing, reverting, restoring and conflict resolution are refused, with a message saying which world to load first. Load it back with your multiworld plugin to continue.

### 7.1 Build worlds

Besides the server's original world (the **server world**), you can create **build worlds** (admins always can; regular players need it enabled, see "Who can create" below). Each build world is a separate repository with its own history and drafts; saving, publishing, reverting and restoring only affect it (the plan is in [03-worlds.md](03-worlds.md)).

| Command | What it does |
|---|---|
| `/bg new [name] [options]` | Opens the create dialog: fill in the name, pick the world type (Overworld, Nether, End), terrain (normal, large biomes, amplified, superflat, void), rules (build, vanilla), a template when templates are set, then the seed, superflat layers and the block under spawn for void, and click "Create" to be teleported in. Names use lowercase letters, digits, `_` and `-`; empty numbers it automatically. You can also type the name and options directly, with option names and values separated by spaces and Tab completion (option names first, then the values once an option name is typed): `/bg new castle type nether terrain flat`. The options are `type`, `terrain`, `rules`, `seed …`, `layers …` (written like vanilla superflat preset codes, bottom to top, stacked from the bottom of the world, e.g. `layers bedrock,2*dirt,grass_block`, a desert `layers bedrock,3*stone,sandstone,sand;desert`), `block …` and `template owner/name`; these words cannot be world names. Options you type are filled into the dialog; add `confirm` to create directly without the dialog. `from <save>` (written last, may contain spaces) uploads a single-player world from the player's computer as a new build world; this needs the BuildGit client and `player-worlds.upload` set up (see §7.1) |
| `/bg open <world>` | Enter a build world: your own by name, someone else's as `owner/name`; the server world's name takes you back |
| `/bg fork <owner/name> [new name]` | Copy a build world as your own private world, with main's history; members always can, visitors only if that world allows copies. Without a new name, the original name is used |
| `/bg submit [message]` | In a forked world, send the last save of the current branch back to the source, which gets a `submit-<player>-<number>` draft; submitting again updates the same one |
| `/bg world submissions [list\|reject <branch> [reason]]` | The source's owner and managers see open submissions (without arguments a menu opens; click one to go in, then `/bg publish` to merge it) or reject one; after a full merge it is closed automatically and the submitter is notified |
| `/bg worlds [mine\|public\|recent] [page]` | Opens the build world list menu for players: mine (own and invited), public (most recently saved first), recently visited; click to go in. On the console it lists all build worlds |
| `/bg world` (in the server world) | The server world can also have members and visibility (`members`, `visibility`, with admins as owners). Member roles add to the usual permission nodes; when private, people without permission who are not members are sent to the lobby (`worlds.lobby`; without one it cannot be made private) |
| `/bg world members [list\|add <player> [builder\|manager]\|remove <player>]` | Manage members in a build world: owners can add and remove builders and managers; managers can only add and remove builders. The player must be online to be invited (changing the role of an existing member does not need that). Without arguments a members menu opens; click an online player's head to invite them |
| `/bg world visibility public\|private` | The owner makes the world public (everyone can come in and look around) or private (members only, the default) |
| `/bg world rename <new name>` | The owner renames it; the world folder and draft folders are renamed too. People inside are first sent back to the server world; `/bg open` again afterwards |
| `/bg world transfer <player> confirm` | The owner transfers the world to an online player and stays as a manager; folder names containing the owner change accordingly |
| `/bg world delete confirm` | The owner deletes it: people inside are sent back to the server world, and the world, drafts and repository are renamed in place into the trash (deleted for good after `player-worlds.trash-days` days) |
| `/bg world undelete [name]` | List your worlds in the trash, or take one back (admins write `owner/name` to take back anyone's) |
| `/bg world unlist\|relist [world]` | Admins unlist a world (forced private; the owner cannot make it public again) or undo that; without a world, the current one |
| `/bg world template on\|off [world]` | Admins make a build world a template or stop it being one; without a world, the current one. Templates appear in the create dialog, and creating from one forks it (with its history); those listed in `player-worlds.templates` are not affected |
| `/bg world settings [<setting> <value>]` | Owners and managers change this world's settings (table below); just `/bg world settings` opens the settings menu, where clicking moves to the next value, and the display name and description open a dialog to type in. Changes take effect immediately |

| Setting | Values | Description |
|---|---|---|
| `time` | `noon`, `sunset`, `midnight`, `day`, `cycle`, or a tick count from 0 to 23999 | Fixed at a time, or passing normally |
| `weather` | `clear`, `rain`, `thunder`, `cycle` | Only affects this world, independent of the server world |
| `difficulty` | `peaceful`, `easy`, `normal`, `hard`, `server` | `server` follows the server's difficulty |
| `mobs` | `on`, `off` | Spawning of animals, monsters, patrols, phantoms, wandering traders, raids |
| `nature` | `on`, `off` | Random ticks (crops, ice, leaves), fire spread, mob griefing, vine growth |
| `border` | 16 to `player-worlds.max-border`, or `default` | The world border size |
| `border-center` | (no value) | Set the border center to where you stand |
| `spawn` | (no value) | Set where people appear when teleported in (also where they respawn) to where you stand |
| `respawn` | `on`/`off` (default `on`) | People who die in this world respawn at its spawn point (their own bed or respawn anchor in this world works as in vanilla); when off, they go back to the server world as in vanilla |
| `member-mode` | `creative`, `survival`, `adventure` | Members' game mode when they come in |
| `visitor-mode` | `spectator`, `adventure` | Visitors' game mode when they come in |
| `visitor-interact` | `on`, `off` | Whether adventure-mode visitors can use buttons, levers, doors and pressure plates |
| `inventory-member`/`inventory-visitor` | `on`, `off` | Inventory isolation for members/visitors: they have their own inventory in this world (see below) |
| `kit-member`/`kit-visitor` | (no value) or `clear` | Opens a large chest to put in the starting kit for members/visitors (including armor and offhand); closing it saves; `clear` empties it; that role's inventory isolation must be on first |
| `kit-member-reset`/`kit-visitor-reset` | `on`/`off` (default `on`) | Put on the starting kit every time they come in; when off, only the first time, and after that whatever they left here last time |
| `kit-member-respawn`/`kit-visitor-respawn` | `on`/`off` (default `on`) | Replace the inventory with the starting kit when respawning in this world after death |
| `display-name` | text (up to 32 characters); none clears it | The name shown in the list, any language allowed; commands still use the original name |
| `description` | text (up to 120 characters); none clears it | The description in the list |
| `icon` | an item id; none uses the item in your hand | The icon in the list (grass block by default) |
| `allow-fork` | `on`/`off` (default `on`) | Whether visitors can copy it with `/bg fork` when public; members always can |
| `accept-submissions` | `on`/`off` (default `on`) | Whether forked worlds can send changes back with `/bg submit` |

- **Who can create**: people with `buildgit.world.admin` always can; regular players need `player-worlds.enabled: true` and `buildgit.world.create`, at most `player-worlds.max-per-player` each (3 by default). Everyone can use `open`, `home` and `worlds`; which worlds they can enter depends on member lists and visibility.
- **Uploading single-player worlds**: `/bg new <name> from <save>`, where `<save>` is the folder name in the player's `.minecraft/saves`. Only the overworld's blocks and entities are taken (not the Nether, the End or player data), without history; outside the save, terrain follows `terrain` (void by default). The flow: the server asks the client to pack it; the client first asks the player in chat whether to upload (showing the server address, the save name and the size limit), and only packs after **[Upload]** is clicked (or `/bgc import yes`); **[Cancel]** (`/bgc import no`) does not send it (the buttons carry this request's number, so clicking an old prompt does nothing). The server issues a presigned upload URL valid for 15 minutes and bound to the size, and the client uploads straight to `uploads/<player UUID>/<random>` on S3. The server downloads it (aborting when over the size), checks the size and SHA-256, deletes it, and then checks each chunk: chunks that cannot be read, are in the wrong place or are not fully generated are dropped, a save made by a newer version than the server is refused entirely, and older ones are upgraded to the current format. For players who cannot use operator blocks (not op or not in creative), uploaded command blocks, structure blocks and the like become air, command block minecarts are removed, and click commands on signs and similar are cleared; entities listed in `ignored-entities` are dropped. Set up the connection in `s3.yml` first and name it in `player-worlds.upload.connection`; when the S3 endpoint is an internal address, fill in `public-endpoint`. When a player leaves midway, the server deletes the half-uploaded file, but the upload may finish after the deletion, so we recommend a lifecycle rule on the bucket that deletes `uploads/` after a day.
- **Importing worlds on the server** (admins, `buildgit.world.admin`): `/bg new <name> import <world folder> [owner <player>]`. `<world folder>` is a folder in the server directory (e.g. a multiworld plugin's world, or a save you put there); completion lists folders that look like worlds. Only the blocks and entities of the overworld level are taken (26.x saves read `dimensions/minecraft/overworld`; to take the Nether, write that dimension's folder), without history; the original world is not touched, and a loaded one is saved before copying. `owner` is the owner once created (must be online, not limited by the per-player limit); without it, it is you; with `owner` the console can use it too. Like uploads, each chunk is checked and old data upgraded, but operator blocks are kept. The other options (`type`, `terrain`, `rules` and so on) are the same as `/bg new`; without `terrain`, everything outside the world is void.
- **Roles**: in build worlds, permissions for BuildGit's commands come from the player's role in that world, not from the `buildgit.role.*` you gave: owners and managers equal manager, builders equal builder, and visitors can only look (`status`, `log`, `diff`, `inspect` and so on) and cannot change blocks, enter drafts or view old versions. People with `buildgit.world.admin` can do everything in every world. Server administration such as `init`, `reload` and `backup` still checks permission nodes.
- When a member is removed or a world becomes private, people inside who no longer qualify are sent back to the server world; people removed while offline are sent back when they join.
- **Game modes**: entering a build world switches to that world's mode (members creative and visitors spectator by default); going back to the server world switches back to the original mode. The original mode and the position before entering (where people are sent when the world is deleted, renamed or they are removed) are recorded in `buildgit/players/<uuid>.yml`. BuildGit has no command for going back to the server world; that is left to the server's usual teleport commands (e.g. `/spawn`, multiworld plugins), and it is restored after restarts too.
- **Inventories**: shared across all worlds by default. With inventory isolation on (separately for members and visitors: `inventory-member on`, `inventory-visitor on`; or set the server to `player-worlds.inventory-isolation: always` to isolate both, `never` for neither), entering this world stores the original inventory, ender chest, experience and status effects and swaps in this world's own; going back to the server world swaps back. By default the starting kit is put on every time they come in (with `kit-*-reset off`, only the first time, and after that what they left last time), and again on respawn (`kit-*-respawn`). Worlds upgraded from older versions keep the old behavior: members' inventories are kept, visitors' are reset every time, and nothing is given on respawn. Stored inventories are in `buildgit/players/<uuid>/` (the original is `server.dat`, each world `<world id>.dat`), and starting kits next to the world's repository (`kit-member.dat`, `kit-visitor.dat`); all record the data version, so items are upgraded on read after a Minecraft upgrade.
- **Visitor limits**: visitors cannot change blocks, open containers or interact with entities; WorldEdit and Axiom edits are blocked; walking outside the world border (`player-worlds.border` by default, 1024×1024) pulls them back. With `visitor-interact` on, adventure-mode visitors can use mechanisms; the changes are recorded as made by that visitor and saved along with the next member save.
- **Portals**: Nether portals, End portals and End gateways do not work in build worlds (they would send people to the server's Nether and End).
- **Build rules** (picked at creation): the settings above default to always noon and clear weather, peaceful, no mob spawning and no natural changes; "vanilla" means time passing normally, the server's difficulty, mob spawning and natural changes. Both can be changed later with `/bg world settings`. Build worlds always turn off "spectators generate chunks" and Nether portals, and End-type worlds have no ender dragon.
- The type, terrain and seed cannot be changed after creation. A build world has only one dimension. Settings are stored in `repo.yml` in its repository.
- **World folders**: names come from `player-worlds.world-name`, e.g. bob's `castle` is `bw_bob_castle` (under `world/dimensions/minecraft/` on Paper 26.x, in the server root on 1.21.11). Its drafts are `bw_bob_castle-<draft>` by default (`draft-world-name`, `merge-world-name` and `view-world-name` set separate folder names for drafts, conflict resolution and viewing old versions). Creating is refused when a name clashes with an existing world, folder or the server world.
- **Where data lives**: by default the repository is in `buildgit/worlds/<id>/`, and objects are shared with the server world in `buildgit/objects/`. The list is in `buildgit/worlds.yml`; do not edit it by hand.
- To keep a whole build world (including history) inside its world folder, so zipping one folder takes everything along:

  ```yaml
  player-worlds:
    paths:
      repo: "{world-folder}/buildgit"
      objects: "{repo}/objects"
  ```

  Then deleting the world folder with other tools deletes its history too.
- `paths` only affects worlds created later; existing ones are found at the locations recorded in `buildgit/worlds.yml`. `{server}`, `{buildgit}` and `{world-folder}` are worked out at each startup, so they are still found after moving the whole server folder, or after Paper moves world folders when upgrading from 1.21.11 to 26.x.
- History backups cover all build worlds (§8.1), and `world=` restores one on its own. World folder backups only take the world folders of build worlds (not drafts, which can be rebuilt from versions), not their repositories. Inside build worlds, `pull`/`push` permissions follow the role in that world (builder or above; visitors can only `pull`, when "allow copies" is on), not `buildgit.pull` or `buildgit.push`.

## 8. Backups and moving

- **Back up together**: `buildgit/`, the genesis world (at `init.genesis-dir` when set) and all world folders (including `bg_` draft worlds) must be backed up **at the same point in time**, ideally with the server stopped. Restoring only one side leaves the worlds and the version history out of step.
- Moving to a new host: stop the server and move the whole server directory.
- Do not edit files in `buildgit/` by hand.

### 8.1 Backups

BuildGit can send backups to S3-compatible object storage (AWS S3, Backblaze B2, self-hosted S3-compatible services and so on) or to a local folder (e.g. another disk). When the whole host fails or a world is broken, you can get it back. There are two kinds of backup:

| Kind | Contents | Good for |
|---|---|---|
| **History** (`history`) | all branches and saves, genesis worlds, the `level.dat` (seed) of versioned worlds | frequent backups: small, only new saves are sent |
| **World folders** (`world`) | whole world folders: blocks, player data (inventories, positions, advancements), map art, scoreboards, unsaved changes | daily or after players leave: large, only changed files are sent |

**Setup**: after the first start, the settings folder (Paper: `plugins/BuildGit/`; Fabric: `config/buildgit/`) has two files; run `/bg reload` after changing them.

- `s3.yml`: S3 connections (URL, bucket, keys); you can have several. It holds keys, so it is separate from the other settings, and plugin updates never change it. Do not share this file. The keys only need read, write, list and delete permissions on this bucket.
- `backup.yml`: backup jobs. Each job decides what to back up, where to, when, and how many copies to keep. There are two jobs by default, so backups start as soon as you fill in the `default` connection in `s3.yml`:

  | Job | Contents | When | Kept |
  |---|---|---|---|
  | `history-s3` | history | every hour on the hour, only when something changed | 48 |
  | `world-s3` | world folders | daily at 04:00, and after the server has had no players for 10 minutes | 14 |

Settings of each job:

| Setting | Description |
|---|---|
| `what` | `history` or `world` (world folders) |
| `target` | `s3:<connection name in s3.yml>`, or `local:<folder>` (relative to the server root, or absolute) |
| `path` | The place inside the destination; give each server or job sharing a bucket its own name |
| `schedule` | cron schedule "minute hour day month weekday", e.g. `"0 * * * *"` every hour, `"0 4 * * *"` daily at 4:00, `"*/30 * * * *"` every 30 minutes; the time zone is `timezone` in `backup.yml` (empty uses the server's) |
| `idle-minutes` | Back up once after the server has had no players for this many minutes; only if someone joined since the last backup, so an empty server does not keep backing up |
| `keep` | How many snapshots to keep |
| `enabled` | `false` pauses this job |
| `exclude-worlds` | Worlds not to back up, e.g. `[world_nether, "alice/test"]`: world folders (relative to the server root), or build worlds as "owner/name" (matched by the current name, so update it after a rename). History jobs can only exclude build worlds, since the server world's history is one whole. When restoring world folders, excluded worlds are left as they are |
| `genesis-hours` (history) | When genesis world files already sent have changed, resend them at most every this many hours, 24 by default; `0` every time. New files are sent every time. See "Large worlds" below |
| `storage` (world folders) | `auto` (default): `folders` for local; `versioned` for S3 when the bucket has versioning on, `hashed` when not. `folders`: one complete folder per snapshot that any tool can use directly (local copies use hard links, so unchanged files take no space). `versioned`: uses the bucket's versioning and only stores changed files again. `hashed`: files are named by their content, so the same content is stored once; restore with BuildGit |
| `announce` (world folders) | Tell players in chat when it starts and ends |
| `exclude` (world folders) | Extra paths not to back up (relative to the server root, `*` allowed) |

You can set just one of `schedule` and `idle-minutes`, or both. When upgrading from an older version, the old settings in `s3.yml` are converted to a `history-s3` job automatically (backups continue where they left off); `enabled`, `prefix`, `interval-minutes` and `keep` in `s3.yml` can be removed afterwards.

**How it works**:

- All jobs run one after another in the background while the server keeps running. Only new or changed parts are uploaded; if the connection drops midway, the next run continues. When the network is down for a moment it retries a few times; from the third failure in a row, online players with `buildgit.backup` are warned every time.
- **History** writes a snapshot only when there are new saves, branches moved or were deleted, or genesis world files changed. The server world and each build world decide and write snapshots separately (build worlds under `<prefix>/worlds/<id>/`, together with their settings and starting kits), sharing one set of objects. Conflict resolutions in progress (`merge/<id>.yml`) are in the snapshot too, so `/bg merge continue` works as usual after a restore; chunks too large to fit in the region (`c.<x>.<z>.mcc`) are backed up with their region file. Saves themselves are kept forever; what gets deleted is only the record of "which branches existed at that point in time". For worlds using a lazy copy (`init.lazy-copy-above-mb`), the genesis world is incomplete and cannot be restored from history alone; `/bg backup status` lists them, so back up their world folders too.
- **World folder** backups briefly pause autosaving, flush the worlds to disk, copy the small changed files locally (player data, `level.dat`, `data/` and so on), then resume saving; region files (`.mca`) are copied, uploaded and deleted one by one after saving resumes, so neither the pause nor the local staging space grows with the world. A region file written to while being copied is copied again (up to 5 times). As a result, the region files in a snapshot may be a few minutes to a few hours newer than the player data (on the first backup of a large world); with announcements on, players see "The server is backing up the worlds". Only worlds someone has been in since the last backup are processed, the others reuse the previous copy; worlds nobody has ever been in are not backed up. Inventories stored by inventory isolation and players' switching state (`buildgit/players/`) are backed up and restored together, at the same point in time as the player data in the worlds. Draft and view worlds can be rebuilt from versions and are not backed up.
- A local backup on the same disk only protects against mistakes and corruption, not against the disk failing.
- Data of other plugins and mods (`plugins/`, `config/`) is not in the backup; back it up separately as usual.

**Commands** (need `buildgit.backup`):

| Command | Effect |
|---|---|
| `/bg backup status` | Each job's destination, schedule, last successful time and last error |
| `/bg backup now [job]` | Run now (all jobs when no name is given) |
| `/bg backup list [job]` | List recent snapshots |
| `/bg backup test [job]` | Test the destination: list, write a small file, read it back and delete it, showing how long each took; for S3 it also shows whether the bucket has versioning on (`storage: auto` picks versioned or hashed from this). It warns when the key lacks the GetBucketVersioning permission; in that case `storage: auto` world backups fail, so set `storage: hashed` |
| `/bg backup fetch <branch> [new name]` | Get one branch back from a history backup (e.g. a draft deleted by mistake), without restarting; when a branch with that name exists, give a new name |
| `/bg backup restore <job> [snapshot]` | Download a snapshot to restore (console only; the newest when no snapshot is given) |
| `… world=<owner/name>` | Added after `list`, `fetch` or `restore`: use that build world's snapshots instead. `restore` needs no restart: once downloaded, the world is unloaded and swapped, and the old one is kept renamed as `….before-restore-<time>`; when this server does not have the world, it is put back as in the backup |

**Restoring world folders** (e.g. a world got broken and should go back to a certain day):

1. On the console, run `/bg backup restore world-s3` (or first `/bg backup list world-s3` to see the snapshots, then name one). The server keeps running while it downloads to `buildgit-restore/`.
2. After the "is downloaded" message, restart. It is swapped in before the worlds load: the world folders, and the repository's branches and unsaved marks, go back to that point in time; draft worlds are rebuilt from versions afterwards. The originals are kept renamed as `….before-restore-<time>`; delete them yourself once everything looks right.

**Restoring a whole server** (e.g. the host failed, on a new host):

1. Install the same or a newer Minecraft version, BuildGit and the original datapacks and mods, and start once so the world is generated. To get the same terrain outside the genesis world when the Minecraft version changed, set `level-seed` in `server.properties` to the original seed before the world is generated (the restore reminds you).
2. Put the original `s3.yml` and `backup.yml` back and run `/bg reload`. No `/bg init` is needed: without a repository, `/bg backup status`, `list`, `test` and `restore` (server world) still work.
3. On the console, run `/bg backup restore history-s3`. After downloading, it first rebuilds main's worlds next to the current ones from "genesis world + main's latest save"; after the "is downloaded" message, restart, and the repository and these worlds' `region`/`entities` and `level.dat` are swapped for the backed-up ones.
4. If you have world folder backups, then run `/bg backup restore world-s3` and restart once more, and player data, unsaved changes and the rest come back too.
5. Put build worlds back one by one with `/bg backup restore history-s3 world=<owner/name>` (no restart needed).

- To cancel a restore: delete `buildgit-restore/` before restarting.
- Before restoring world folders, the disk space is checked: if the whole snapshot does not fit (plus 1 GB to spare), it is refused with how much is needed. Swapping in only renames the original folders, so until you delete `….before-restore-…`, the old and the new take space at the same time.
- An error while swapping in (e.g. a full disk) stops startup; fix it and restart, and it continues where it stopped.
- Backups written by a newer Minecraft cannot be restored onto an older server.

### 8.2 Large worlds (hundreds of GB to TB)

Large worlds work with the default settings, but prepare the space and bandwidth first. Taking a 1 TB server world as an example:

| When | What happens | What to prepare |
|---|---|---|
| `/bg init` | Over 512 MB, a lazy copy is used automatically and init finishes at once; afterwards the genesis world only grows by the areas players visit and newly generated ones | Genesis world space grows with exploration, at most as big as the world. A full copy (`init.lazy-copy-above-mb: -1`) needs another 1 TB and several hours |
| Saving, publishing, reverting, restoring | Only the changed places are processed, regardless of world size | — |
| Drafts, `view`, `play` | Only the regions walked through are synthesized (about 5–30 MB each) | Remember to delete drafts you no longer need |
| The first world folder backup after filling in `s3.yml` | Backed up whether or not it is initialized; region files are uploaded one by one, so local staging is only a few files big | Uploading 1 TB: about 2.5 hours at 1 Gbps, about a day at 100 Mbps; S3 needs about 1 TB plus the changes over the retention period |
| Daily world folder backups after that | Only changed files are sent (the region files where players are active) | A few GB per day |
| Hourly history backups | New saves, plus new genesis world files; files already sent that change later are resent at most every `genesis-hours` (24) hours | With a lazy copy, history backups are incomplete, so you must have world folder backups |
| Restoring world folders | Downloaded next to the current ones first, swapped in on restart, with the originals kept renamed | **Another 1 TB or more**; refused outright when there is not enough space |

The cost of `genesis-hours`: when the host fails and you restore from history, chunks first loaded or newly generated recently may not be in the backed-up genesis world. For lazily copied worlds, those chunks are read from the world folder backup of the same time; newly generated chunks are generated again by the world generator. To have it complete every time, set it to `0`.

## 9. Upgrading Minecraft

- Just swap in the jar for the new version. Old history and genesis worlds are not rewritten; when read, the server's own data upgrade code (DataFixer) converts them to the new format, so after upgrading you can still view old saves and restore versions from before the upgrade.
- **No downgrades**: once a newer server has written to the repository, an older server refuses to open it. Back up before upgrading (§8).
- **To convert the genesis world to the new format as well**: after upgrading, run `/bg genesis upgrade` (needs `buildgit.genesis`). It rewrites the chunks in the genesis world saved by older versions in the new format, so they do not need converting on every read; the more versions you jump, the more it saves.
  - The content does not change; old saves can still be viewed and restored.
  - It runs file by file in the background while the server keeps running, with progress in a bossbar. Stopping the server midway is fine: unfinished files stay as they were; just run it again next time.
  - Back up first (§8); the rewritten genesis world can only be read by servers of this version or newer (downgrades are impossible anyway).
  - For lazily copied worlds: chunks loaded for the first time later are copied into the genesis world as they are on disk, possibly in an old format again; run it again when needed.
- For areas taken to single-player on the client, the single-player version must be the same as the server's or newer.

## 10. Known limitations

- Players on Paper with the Axiom bypass permission (`axiomadmin.bypass_region_checks`): blocks and entities are recorded as usual, but changes from Axiom's biome brush are not. BuildGit records these players by reading AxiomPaper's internals, which may stop matching after an AxiomPaper update; it then turns itself off with a console warning, and these players' block edits are no longer recorded. Such players get a notice when they turn Axiom on.
- Blocks frozen by DebugStickPro (temporarily barriers on the server) are not saved until unfrozen; while frozen, `/bg status` keeps showing them as unsaved.
- Entity removals not caused by players (e.g. an armor stand blown up by a creeper) are not recorded, just like natural block changes.
- Biomes: when a draft and main changed the biome of the same spot (4×4×4 blocks), publishing keeps main's, and the publish preview lists how many; fix them with `/fillbiome` afterwards if needed. Taking areas to single-player (`pull`/`push`) does not carry biomes. Biomes changed by other plugins through their APIs are not recorded; use `/bg mark` to mark them.
- Large structures placed by the world generator, such as with `/place feature`, may not be fully recorded; use `/bg mark` to mark the area.
- When the server crashes, changes from about the last second may not be recorded (`tracking.journal-flush-ticks`).
- The Fabric version's Axiom recording reads the Axiom mod's internals, which may stop matching after an Axiom update; it then turns itself off with a console warning ("This Axiom version does not match BuildGit"), and Axiom edits are no longer recorded.
- The Fabric version has no DebugStickPro integration yet, and no API for other mods.

## 11. FAQ

**A player says they cannot see a command**: a missing permission; see §4.

**The console says a world "is under version control … so BuildGit keeps it loaded"**: that world or one of its draft worlds still has players, or is saving. Unload it after the players leave.

**Publishing says "These worlds are not loaded"**: load the listed worlds with your multiworld plugin and try again.

**Deleting a draft says "There are saves that are not merged"**: the draft has saved content that is not published to main yet, and deleting it loses that for good. Add `--force` if you are sure.

**Drafts cannot be opened after init**: the genesis world is still being copied in the background; you are notified when it is done.

**Not enough disk space**: first check which part with `/bg disk`, then find drafts nobody uses with `/bg branch list` and delete them.
