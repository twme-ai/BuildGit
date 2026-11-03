# BuildGit

Version control for Minecraft builders. BuildGit records only the changes players make (including WorldEdit/FAWE, Axiom, the debug stick and DebugStickPro, biomes, and knock-on changes) and stores each version as a "genesis world + overlay". You can open draft worlds to try things out, save, publish back, and look through history and who changed what.

- Platforms: Paper 26.3, 26.2, 1.21.11; Fabric 26.3, 26.2, 1.21.11
- Admin manual (installation, init, permission sets, backups, upgrades): [`docs/02-admin.md`](docs/02-admin.md)
- Design document: [`docs/01-design.md`](docs/01-design.md); requirements: [`docs/00-requirements.md`](docs/00-requirements.md)
- The documents under `docs/` are currently written in Chinese.

## Download

GitHub Actions packages every push; the jars are in that run's Artifacts. Pushing a `v*` tag creates a Release.

There is one jar per Minecraft version. Download the one that matches your server:

| Server | File | Java |
|---|---|---|
| Paper 26.3 | `BuildGit-<version>-paper-26.3.jar` | 25 |
| Paper 26.2 | `BuildGit-<version>-paper-26.2.jar` | 25 |
| Paper 1.21.11 | `BuildGit-<version>-paper-1.21.11.jar` | 21+ |
| Fabric 26.3 | `BuildGit-<version>-fabric-26.3.jar` | 25 |
| Fabric 26.2 | `BuildGit-<version>-fabric-26.2.jar` | 25 |
| Fabric 1.21.11 | `BuildGit-<version>-fabric-1.21.11.jar` | 21+ |

On a version mismatch the plugin says so at startup and disables itself; Fabric Loader refuses to load the Fabric jar.

The Fabric version needs Fabric API; Fantasy (branch worlds) and fabric-permissions-api are bundled in the jar. The repository lives in `buildgit/` in the server directory, and the settings in `config/buildgit/`. The Fabric version records edits from the WorldEdit and Axiom mods; it has no DebugStickPro integration or public API yet. You can also install the Fabric version on your client, to take a selection to single-player, edit it there and send it back (see "Editing in single-player" below).

## Languages

Messages default to English. Each player sees BuildGit in their own game language when there is a lang file for it (bundled: `en_us`, `zh_tw`). You can add your own as `lang/<code>.yml`. The console uses the `language` setting in `config.yml`; set `language-per-player: false` to show everyone `language`.

## Building

Needs JDK 21 and JDK 25 (the Gradle toolchain picks them automatically).

```sh
./gradlew build
# Output: paper/plugin-26_3/build/libs/BuildGit-<version>-paper-26.3.jar
#         paper/plugin/build/libs/BuildGit-<version>-paper-26.2.jar
#         paper/plugin-1_21_11/build/libs/BuildGit-<version>-paper-1.21.11.jar
#         fabric/mc26_3/build/libs/BuildGit-<version>-fabric-26.3.jar
#         fabric/mc26_2/build/libs/BuildGit-<version>-fabric-26.2.jar
#         fabric/mc1_21_11/build/libs/BuildGit-<version>-fabric-1.21.11.jar
```

## Player guide

You don't need to know version control. Just remember one thing: **when you want to try a new idea, open a "draft", and "publish" it back to main when you're happy with it.**
Nothing you do in a draft affects main; if you break something, just leave.

A few terms:

- **Server world**: the server's original world, where everyone builds together. Get back there with the server's usual teleport command (e.g. `/spawn`).
- **Build world**: your own separate world created with `/bg new`, with its own history and drafts; you can invite others in. The "Overworld / Nether / End" type you pick only decides which vanilla dimension the sky and environment look like.
- **main**: the copy of each world (server world or build world) that everyone sees.
- **Draft**: a copy of main to try things in; publish it back to main when you're happy.

> Commands need permissions (only OPs have them by default). If you can't see a command, ask an admin for the permission.

### Quickest start: type `/bg`

`/bg` opens a chest menu titled "Versions and drafts of this world · world name". Click to run things:

- **Top three rows**: main and every draft of this world; click one to go there. The one you are in is green glass.
- **Fourth row**: actions for where you are ("Back" and "Publish" in a draft, "Conflicts" and "Finish resolution" in a resolution world); on the far right, the build world list, plus "World settings" next to it in build worlds you manage.
- **Bottom row**: Inspect mode, History | Outline unsaved changes, Status, Save | Drafts (who is where), Selection.

Buttons you can't see need permissions you don't have. If you forget a command, type `/bg help`; it lists only what you can use.

### A complete workflow

1. **Open a draft**: `/bg try new-roof`
   This opens a copy identical to main and teleports you to the same spot. The bar at the top of the screen shows "draft new-roof".
2. **Build freely**: by hand, WorldEdit, FAWE, Axiom, the debug stick (including DebugStickPro); what changed and who changed it are recorded automatically.
3. **Save** (optional): `/bg save roof frame done`
   Like a save point in a game: you can look back at it or restore it later. If you forget, BuildGit saves automatically after nobody has built for a while.
4. **Publish to main**: `/bg publish`
   It first lists how many blocks changed and who changed them; when changes are spread over several places it lists each area (connected chunks count as one area), and you can click to teleport there. Nothing is written to main until you click **[Confirm publish]** in chat.
   After publishing, click **[Delete draft]** to remove the draft.
5. **Changed your mind?** `/bg back` returns to the original world. The draft stays, and `/bg go new-roof` takes you back to continue.
6. **Delete a draft**: `/bg branch delete new-roof` (main cannot be deleted).
   It is refused while there are unsaved changes, or saves that are not published yet; add `--force` when you're sure. If another draft was made from it, deal with that draft first.
   `/bg branch list` shows all drafts and when each was last saved.

Only want to publish part of it? Select an area with WorldEdit (or `/bg sel pos1`, `/bg sel pos2`), then `/bg publish --selection`.

### Seeing what changed

| To find out… | Command |
|---|---|
| How much I haven't saved | `/bg status` |
| Outline changed blocks in the world (they glow for a while) | `/bg diff` |
| Who changed this block | turn on `/bg inspect`, then click a block |
| Past saves | `/bg log`; 8 per page, click **[Next]** or `/bg log 2` to turn pages |
| A branch history graph of all drafts (like `git log --graph`) | `/bg log --graph`; 8 per page, click **[Next]** or `/bg log --graph 2` to go back further; add `--all` to include autosaves |
| What a save changed | `/bg diff <save id>`: lists who changed how many blocks and outlines them in the world; add `--cells` to list every block |
| Compare before and after in place | after `/bg diff`, click **[Before/After]** (or `/bg diff flip`): only you see the old state, the world is unchanged; click again to return to now |
| What an old version looked like | `/bg view <save id>` (read-only; `/bg back` to return) |
| Watch a build go up step by step | `/bg play [from] [to]`: plays saves in order in a read-only world you can walk around; from and to can be save ids, dates (`10-20`, `10-20/14:00`) or how long ago (`3h`, `2d`). While playing: `/bg play pause\|resume\|next\|prev\|goto <step>\|speed <seconds>\|stop` |
| Which drafts exist and who is in them | `/bg branch list` |

A save id is the yellow string of letters and digits in `/bg log`; click it to list what that save changed and outline it in the world. When typing commands, the first few characters are enough, and Tab lists recent saves.

### When something goes wrong

- **Undo one save** and keep later changes: `/bg revert <save id>`
- **Go back to an old version entirely**: `/bg restore <save id>`; only the selection: add `--selection`

`restore` asks you to confirm the area first. Both become a new save, so a restore can itself be undone.

### Conflicts

If blocks you changed in a draft were also changed by someone in main, publishing runs into a "conflict". BuildGit then takes you to a **resolution world**,
where conflicting blocks glow and the top of the screen shows how many are left.

- Click the list in chat (or `/bg merge goto 1`) to jump to a conflict
- Which side do you want? Just change the block to what you want, or use:
  - `/bg merge take theirs here`: use the **draft's** version for nearby conflicts
  - `/bg merge take ours here`: use **main's** version for nearby conflicts
  - replace `here` with `all` for everything
- Not sure? `/bg merge view ours` / `theirs` shows how each side looked; `/bg back` to return
- When done: `/bg merge continue`; to give up: `/bg merge abort` (nothing is written)

### Editing in single-player

Want to take your time on your own computer? Install the Fabric version of BuildGit on your client (the server can be Paper or Fabric):

1. On the server, type `/bg pull`. With a selection it takes the selection; without one, it takes what the draft changed when you're in a draft, or an area around you in main. It is named automatically (the draft's name or yours), or name it yourself: `/bg pull house`.
2. Click **[Open in single-player]** under the message: you leave the server and a single-player world just for this area opens (the first time, it creates a creative, peaceful, void world). The area is placed at the same coordinates, and you stand where you were on the server. You can also open any single-player world (same version or newer) and type `/bg load <name>`.
3. When done, leave the world; the edited version is saved automatically.
4. When you join the server again, it lists the areas not sent back yet. Click **[Send back …]** (or type `/bg push <name>`): your changes become a draft; look at it with `/bg go` and publish with `/bg publish`.

What others change on the server after you pulled merges as usual; when both sides changed the same block, it is a normal conflict.

### Your own build worlds

If the server allows it, you can create your own build worlds (admins always can):

1. `/bg new`: opens the create dialog. Fill in a name (empty picks one), choose the type (Overworld / Nether / End), terrain (normal, superflat, void and so on) and rules, or a template when there are any, and click "Create" to go there.
   You can also type the name and options directly, with option names and values separated by spaces and Tab completion: `/bg new house type nether terrain flat`. Add `confirm` to create it without the dialog.
2. Inside, you can open drafts, save and publish just the same, with history separate from the server world.
3. `/bg world members add <player>` invites someone to build with you; `/bg world visibility public` opens it for everyone to visit.
4. `/bg world settings` opens the settings menu: time, weather, mobs, game modes, spawn point, the name and description in the list, and more.
5. `/bg worlds` shows everyone's build worlds; click one to go in. Get back to the server world with the server's usual teleport command (e.g. `/spawn`).
6. Want to change someone else's world? `/bg fork <owner/name>` copies it as your own; when done, `/bg submit` sends it back for them to merge.

### Tips

- Only worlds where an admin has run `/bg init` are under version control; the Nether, the End and worlds from multiworld plugins like Multiverse each need their own init. Init doesn't pause the server: the genesis world is copied in the background.
- Draft worlds unload from memory when nobody is in them and load again when you go in; nothing is lost.
- Restarting the server doesn't lose unsaved changes (a crash loses at most the last second).
- Full command list: `/bg help` in game.

## Command overview

```
/bg                      main menu (chest interface)
/bg init                 put the current world under version control (admin; init each world separately)
/bg try [name]           open a draft and teleport to the same spot (lowercase letters, digits, _ and - only; picked automatically when omitted, e.g. steve-1)
/bg go <draft|main>      go to a draft or main
/bg save [message]       save (a milestone)
/bg publish [--selection] preview publishing and write after you confirm; --selection publishes only the selection
/bg back                 return to the source world without publishing
/bg merge take|continue|abort   handle the resolution world when there are conflicts
/bg revert <save>        undo one save
/bg restore <save> [--selection] restore to an old version
/bg view <save>          look at an old version read-only
/bg play [from] [to] [--all]  play back history (walk around to watch); while playing pause|resume|next|prev|goto|speed|stop
/bg branch list|delete <name> [--force]  list drafts and who is in them, delete a draft
/bg log --graph [page] [--all]  branch history graph (paged)
/bg pull [name]          take the selection (without one, what the draft changed or the area around you) to single-player (the client needs BuildGit)
/bg load <name>          (single-player) place a pulled area
/bg push <name> [draft]  send an area edited in single-player back as a draft
/bg disk                 disk space used by BuildGit (admin)
/bg backup [status|now|list|fetch|restore]  back up history and world folders to S3 or a local folder, fetch branches, restore from backups (admin, see admin manual §8.1)
/bg new [name] [options] create a build world (without options it opens a dialog; regular players can use it when the server allows, see admin manual §7.1)
/bg open <world>, worlds enter a build world, list build worlds
/bg fork, submit         copy someone else's build world, send your changes back
/bg world settings|members|visibility|…  manage the build world you are in
/bg help [admin]         list the commands you can use
/bg log, show, diff, blame, inspect, who, sel
```

For permissions and permission sets, see [admin manual §4](docs/02-admin.md#4-permissions).

## API for other plugins

The `api/` module. Get `BuildGitApi` from the `ServicesManager`; you can record changes directly or set the author with `attribute(actor)`.

## License

[MIT](LICENSE)
