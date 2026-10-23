# BuildGit 管理員手冊

給架設與管理伺服器的人看。玩家怎麼用請看 [README 的玩家使用說明](../README.md#玩家使用說明)；內部怎麼運作請看[設計文件](01-design.md)。

## 1. BuildGit 做什麼

BuildGit 只記錄**玩家造成的變更**（手動、指令、WorldEdit／FAWE、Axiom，以及它們引起的連帶變化，例如水流、紅石、方塊掉落；也包括 `/fillbiome`、`//setbiome` 等改的生態域），像 git 一樣存成一個個版本。玩家可以：

- 開「草稿」（main 的副本）試做，滿意了再併回 main（每個世界大家看到的那一份）；
- 查歷史、看某一格是誰在哪次存檔改的；
- 撤銷某次存檔，或把整個世界、某個範圍還原到舊版本。

自然變化（作物生長、生物、天氣）不會進版本。

## 2. 安裝

1. 依伺服器版本下載對應的 jar（[README 的下載表](../README.md#下載)），放進 `plugins/`（Paper）或 `mods/`（Fabric）。版本不符時插件會自行停用。
2. Fabric 另外需要 Fabric API。
3. 想記錄 WorldEdit、FAWE、Axiom、DebugStickPro 的編輯：裝上它們即可。Paper 支援全部；Fabric 版支援 WorldEdit 模組（Fabric 沒有 FAWE）與 Axiom 模組，還沒有 DebugStickPro。原版除錯棒兩個平台都會記錄。
4. 啟動伺服器。這時**還沒有任何世界受版本控制**，BuildGit 不會記錄任何東西。

## 3. 開始版本控制：`/bg init`

站在要管理的世界裡執行 `/bg init`。

- **每個世界各自 init**：在主世界 init 不會連帶管理地獄、終界；Multiverse 等多世界插件的世界也一樣，進去那個世界再 init。
- init 會把這個世界目前的樣子複製一份當「起點世界」，**在背景進行，伺服器不會暫停**。複製完成前，這個世界的變更先不存檔，也還不能開草稿；完成時會通知。
- 起點世界大約和那個世界的資料夾一樣大，請先確認磁碟空間。快的磁碟不夠大時，可以用 `init.genesis-dir` 把起點世界放到另一顆較大、較便宜的硬碟上（見 §6），仍然完整複製，不用遵守延遲複製的規則。
- **大世界可以改用延遲複製**（`init.lazy-copy-above-mb`，預設關閉）：世界存檔超過設定的大小時，init 不複製整個世界，立刻完成；每個 chunk 第一次被載入時才把它的原樣複製進起點世界，沒載入過的 chunk 直接讀世界自己的存檔。起點世界只會有玩家實際去過的範圍。代價是要遵守下面的規則，否則沒載入過的 chunk 的起點會被悄悄改掉、無法復原：
  - 不要在 BuildGit 沒有執行時開這個世界：包括先拿掉插件開服、拿到單人遊戲開、伺服器的 `--forceUpgrade`／`--eraseCache` 啟動參數；
  - 不要用離線工具改它的存檔（MCA Selector、刪除或修剪 region 檔、換掉世界資料夾）；
  - 不要把世界資料夾和 `buildgit/` 分開還原或搬移（§8）。
  
  升級 Minecraft 版本照常可以（§9）：新版伺服器載入舊 chunk 時會先複製舊的原樣。預先生成地形的插件（例如 Chunky）也可以用，只是它載入的 chunk 都會被複製，起點世界會跟著變大。已經 init 的世界不受這個設定影響。
- 沒有 init 的世界完全不受影響。

## 4. 權限

預設所有指令只有 OP 能用（Fabric：沒有權限模組時是權限等級 2）。建議用權限插件（例如 LuckPerms）給**權限集**：

| 權限集 | 適合給誰 | 可以做的事 |
|---|---|---|
| `buildgit.role.viewer` | 訪客、評審 | 選單；看狀態、歷史、存檔內容、方塊是誰改的（`status`、`log`、`graph`、`show`、`diff`、`blame`、`inspect`、`who`、`sel`、`branch list`）；進出草稿、唯讀看舊版本（`go`、`back`、`view`） |
| `buildgit.role.builder` | 一般建築師 | viewer 全部，加上開草稿（`try`）、存檔（`save`）、帶到單人世界改再送回（`pull`、`push`）。**不能把草稿併回 main** |
| `buildgit.role.manager` | 負責審核、維護 main 的人 | builder 全部，加上併回（`publish`）、自訂訊息存檔（`commit`）、處理衝突與底層合併（`merge`）、撤銷（`revert`）、還原（`restore`）、手動補記（`mark`）、建立與刪除草稿（`branch create`、`branch delete`） |
| `buildgit.admin` | 伺服器管理員 | manager 全部，加上 `init`、`reload`、`disk`、`genesis`、`backup`、管理所有建築世界（`buildgit.world.admin`） |

LuckPerms 範例：

```
/lp group builder permission set buildgit.role.builder true
/lp group staff permission set buildgit.role.manager true
```

`builder` 只能存檔、開草稿，不能把草稿併回 main（`publish`）；要讓他併回就給 `buildgit.publish`，或給 `buildgit.role.manager`。

權限不夠時，訊息會說缺哪個節點（例如「需要 `buildgit.branch.delete`」），照著加就好。

常見設定：所有玩家都能建立自己的建築世界，WorldEdit 只給建築世界的成員：

```
# 開放建立建築世界（另外要在 config.yml 設 player-worlds.enabled: true）
/lp group default permission set buildgit.world.create true
# WorldEdit 只在玩家是建造者以上的建築世界裡生效（訪客本來就一律擋下）
/lp group default permission set worldedit.* true buildgit:role=builder
```

在建築世界裡，BuildGit 自己的指令照角色決定（§7），不用另外給 `buildgit.role.*`。

**依玩家在哪裡給權限**：裝了 LuckPerms 時，BuildGit 提供這些情境（設計 §8.1），任何插件的權限都可以設成只在某處生效：

| 情境 | 值 |
|---|---|
| `buildgit:location` | `main`、`draft`（草稿）、`view`（檢視過去的版本）、`resolve`（衝突整合） |
| `buildgit:branch` | 分支名稱（例如 `main`）；整合時是要併入的分支 |
| `buildgit:world` | 伺服器世界的名稱（通常是 `world`），或建築世界的 `擁有者/名稱` |
| `buildgit:role` | 在建築世界裡的角色 `owner`、`manager`、`builder`、`visitor`；往下包含，擁有者同時是 `manager`、`builder` |

```
# 只在草稿裡能用 WorldEdit，main 不行
/lp group builder permission set worldedit.* true buildgit:location=draft
# 只有 house 這個草稿能併回
/lp group builder permission set buildgit.publish true buildgit:branch=house
# 能在建築世界裡建造的人都能用 WorldEdit
/lp group default permission set worldedit.* true buildgit:role=builder
# 看某位玩家現在的情境
/lp user <玩家> info
```

玩家換世界後一刻內就會生效。不在 BuildGit 管的世界時兩個情境都不帶。不想提供時在 `config.yml` 設 `integrations.luckperms-contexts: false`。

也可以逐項給：**每個指令都有自己的節點，彼此不包含**，例如只給 `buildgit.publish` 就只能併回，不能開草稿。

| 節點 | 內容 |
|---|---|
| `buildgit.menu` | 只輸入 `/bg` 時打開選單（選單裡的按鈕仍各自看權限） |
| `buildgit.status` | `status`：目前的分支與未存的變更；也決定看不看得到自動存檔通知 |
| `buildgit.log` | `log`：存檔歷史 |
| `buildgit.graph` | `graph`：分支歷史圖 |
| `buildgit.show` | `show`：某次存檔改了什麼 |
| `buildgit.diff` | `diff`：在世界裡標出差異 |
| `buildgit.blame` | `blame`：看著的方塊是誰改的 |
| `buildgit.inspect` | `inspect`：檢查模式，點方塊看是誰改的 |
| `buildgit.who` | `who`：每位玩家在哪個草稿 |
| `buildgit.sel` | `sel`：不用 WorldEdit 時的選取範圍 |
| `buildgit.go` | `go`：到某個草稿或 main |
| `buildgit.back` | `back`：回到來源世界 |
| `buildgit.view` | `view`：唯讀看舊版本 |
| `buildgit.try` | `try`：開草稿 |
| `buildgit.save` | `save`：存檔 |
| `buildgit.pull` | `pull`：把選取範圍帶到單人世界 |
| `buildgit.push` | `push`：把單人世界改過的範圍送回成草稿 |
| `buildgit.publish` | `publish`：把草稿併回 |
| `buildgit.commit` | `commit`：以自訂訊息存檔 |
| `buildgit.merge` | `merge <分支>`：底層合併任意分支 |
| `buildgit.merge.resolve` | `merge view`／`conflicts`／`goto`／`take`／`continue`／`abort`／`list`：處理衝突整合。有 `publish`、`revert`、`merge` 的人也能處理 |
| `buildgit.revert` | `revert`：撤銷某次存檔 |
| `buildgit.restore` | `restore`：把分支或範圍還原到舊版本 |
| `buildgit.mark` | `mark`：手動補記範圍 |
| `buildgit.branch.list` | `branch list`：列出草稿 |
| `buildgit.branch.create` | `branch create`：建立分支，不傳送 |
| `buildgit.branch.delete` | `branch delete`：刪除草稿與它的世界 |
| `buildgit.init` | `init`：讓所在的世界開始受版本控制（會複製一份起點世界，占用和世界差不多的磁碟空間） |
| `buildgit.reload` | `reload`：重新讀取設定與語言檔 |
| `buildgit.disk` | `disk`：BuildGit 用掉的磁碟空間；`disk clean` 回收沒人用的物件 |
| `buildgit.genesis` | `genesis upgrade`：把起點世界改寫成目前版本的格式（§9） |
| `buildgit.backup` | `backup`：異地備份的狀態、立刻備份、列出快照、取回分支、還原（§8.1） |
| `buildgit.world.create` | 建立自己的建築世界（`new`，§7.1，要 `player-worlds.enabled: true`）、`fork`、`submit` |
| `buildgit.world.admin` | 建築世界（§7.1）：不受上限建立、進出與管理任何人的建築世界、`world unlist|relist`、`world template` |

`open`、`home`、`worlds`、`world`、`help` 不需要權限節點：能進哪些世界看成員名單與可見性，`world` 底下能做什麼看在那個世界的角色。

注意：

- 包含關係由 BuildGit 自己判斷，Paper 與 Fabric 相同，所以在 Fabric 上給權限集也有效。
- 因為這樣，**對權限集裡的單一節點設成 false 不會把它拿掉**。想少給某一項時，請改成逐項給節點，不要用權限集。
- 沒有某項權限的玩家看不到那個子指令；`/bg help` 也只列出自己能用的，所在的地方（main、草稿、整合世界）最常用的排前面，`/bg help admin` 列出管理指令。
- 有衝突時，有 `publish`、`revert` 或 `merge` 權限的人也能處理衝突整合（`merge.resolve` 是給只負責處理衝突的人）。
- 玩家的建築本身不需要任何 BuildGit 權限：只要世界受版本控制，誰改的都會記錄。

## 5. 設定

設定檔：Paper 在 `plugins/BuildGit/config.yml`，Fabric 在 `config/buildgit/config.yml`。改完執行 `/bg reload`。

- **外掛更新後自動補上新設定**：新版本多了設定時，啟動（或 `/bg reload`）會以新版的預設檔為範本、填回你設的值，新的設定與說明一起出現。原本的檔案留在 `config.yml.old`；你自己加的註解、已經不用的設定不會保留，需要時從那裡找回。設定檔有語法錯誤時不會動它。
- **語言檔**：訊息在設定資料夾的 `lang/zh_tw.yml`、`lang/en_us.yml`，可以直接修改（MiniMessage 格式），改完執行 `/bg reload`。外掛更新後：沒改過的訊息換成新版、新的訊息補上，你改過的保留；原本的檔案留在 `.old`。`lang/.bundled/` 是上次寫出的內建版本，用來分辨哪些訊息改過，不要修改。
  - 改過的訊息若新版本換了參數（例如 `<name>`），要自己對照新版調整。
  - 其他語言：在 `lang/` 放 `<語言>.yml` 並把 `language` 設成它；缺的訊息用英文。

常會調整的項目：

| 設定 | 預設 | 說明 |
|---|---|---|
| `language` | `zh_tw` | 訊息語言（`zh_tw`、`en_us`） |
| `autosave.idle-seconds` | 300 | 世界裡所有玩家停手這麼久後自動存檔；0 停用 |
| `autosave.max-interval-seconds` | 1800 | 一直有人在蓋時，最長多久一定自動存檔一次；0 停用 |
| `worlds.unload-idle-seconds` | 60 | 草稿世界沒人這麼久後卸載（見 §7）；-1 永不卸載 |
| `worlds.lobby` | 空白 | 大廳：伺服器世界設成私人時，沒有權限、也不是成員的人送到這裡。建築世界寫「擁有者/名稱」（必須公開，一律持續載入），普通世界寫名稱或維度 id（例如 `lobby`、`minecraft:lobby`，自己用多世界插件建立）。空白時伺服器世界不能設成私人 |
| `worlds.keep-loaded` | `[]` | 持續載入的世界：啟動就載入、沒人也不卸載、不算進 `max-loaded`。寫「擁有者/名稱」或「擁有者/名稱:草稿」；改名、轉讓後照樣對得上（記在 `buildgit/keep-loaded.yml`）。**每個都一直佔著記憶體**，只列大廳與熱門作品 |
| `sync.max-blocks` | 12000000 | `/bg pull` 一次最多帶走的方塊數 |
| `sync.radius` | 80 | 沒有選取範圍、也不在改過的草稿裡時，`/bg pull` 帶走玩家周圍多少格（水平半徑、整個高度） |
| `init.genesis-dir` | 空白 | 起點世界放在哪個資料夾（相對於伺服器目錄或絕對路徑）；空白為 `buildgit/genesis`。要重新啟動才生效（見 §6） |
| `init.lazy-copy-above-mb` | -1 | 世界存檔超過這個大小（MB）時 init 改用延遲複製（見 §3）；0 一律延遲複製，-1 一律完整複製 |
| `integrations.worldedit`／`axiom`／`debugstickpro` | true | 是否記錄 WorldEdit／FAWE、Axiom、DebugStickPro 的編輯 |
| `player-worlds.enabled` | `false` | 一般玩家（有 `buildgit.world.create`）能不能建立建築世界 |
| `player-worlds.max-per-player` | 3 | 每人最多擁有幾個建築世界 |
| `player-worlds.border` | 1024 | 建築世界預設的世界邊界邊長（以 (0, 0) 為中心），也是訪客能去的範圍；擁有者可以用 `/bg world settings border` 改 |
| `player-worlds.max-border` | 4096 | 擁有者能設定的最大邊長 |
| `player-worlds.inventory-isolation` | `owner` | 背包隔離：`owner` 由擁有者為自己的世界決定、`always` 所有建築世界都隔離（生存伺服器建議）、`never` 都不隔離。舊版的 `separate-inventories`、`allow-inventory-isolation` 讀到時自動換成這個 |
| `player-worlds.defaults.*` | | 新建立的世界一開始的設定：`rules`（建立選單預選的 `build`／`vanilla`）、`visibility`、`member-mode`、`visitor-mode`、`visitor-interact`、`inventory-member`、`inventory-visitor`、`allow-fork`、`accept-submissions`、`respawn`；建立後擁有者還能改 |
| `player-worlds.max-loaded` | 20 | 同時載入的建築世界上限，草稿與檢視世界各算一個（伺服器世界不算）；滿了玩家要開新的世界時被拒絕，請他稍後再試 |
| `player-worlds.allowed-types`／`allowed-terrain` | `[]`（全部） | 一般玩家建立時能選的類型（`overworld`、`nether`、`end`）與地形（`normal`、`large-biomes`、`amplified`、`flat`、`void`）；管理員不受限制 |
| `player-worlds.unmanaged-settings` | `[]` | 交給其他插件管的世界規則：`time`、`weather`、`difficulty`、`mobs`、`nature`。列出的 BuildGit 不在建築世界裡設定（不改遊戲規則、時間、天氣、難度），世界設定選單不列、`/bg world settings` 也不能改。已經設過的遊戲規則留在世界裡；伺服器世界本來就不受影響 |
| `player-worlds.templates` | `[]` | 可以當範本的建築世界（`擁有者/名稱`）；也可以在遊戲裡用 `/bg world template on|off [世界]` 設定（記在那個世界的 `repo.yml`）；建立對話框多一個「範本」，從範本建立等於 fork 它，不受 allowed-* 限制 |
| `player-worlds.trash-days` | 7 | 刪除的建築世界在回收區保留幾天；過期的在啟動時與每天檢查一次後真正刪掉（回收區記在 `buildgit/trash.yml`） |
| `player-worlds.world-name` | `bw_{owner-name}_{name}` | 建築世界的資料夾名稱（見 §7.1）；只影響之後建立的世界 |
| `player-worlds.draft-world-name`／`merge-world-name`／`view-world-name` | `{world}-{draft}`／`{world}-{draft}`／`bgview_{world}_{commit}` | 建築世界的草稿、衝突整合、看舊版的世界資料夾名稱（見 §7.1）；草稿建立時記下，之後改設定舊的草稿照樣找得到 |
| `player-worlds.paths.*` | | 建築世界的版本庫、物件、起點世界放在哪裡（見 §7.1） |
| `player-worlds.upload.connection` | `""` | 上傳單人世界用的 `s3.yml` 連線名稱；空白時不開放 |
| `player-worlds.upload.public-endpoint` | `""` | 玩家電腦連得到的 S3 網址；空白時和連線的 `endpoint` 相同 |
| `player-worlds.upload.max-mb` | `512` | 上傳的存檔打包後的大小上限（MB） |
| `tracking.*` | | 連帶變化的時間窗與距離、哪些實體不記錄；通常不用改 |

`config.yml` 前半是一般會調整的，「進階」以下（`sync`、`init`、`tracking`）通常不用動。更新外掛後缺少的設定會自動補上（照新的順序與說明重寫，原本的檔案留在 `config.yml.old`）；認不得的設定（多半是打錯字或放錯層）啟動與 `/bg reload` 時在主控台警告。

## 6. 資料放在哪裡、占多少空間

| 位置 | 內容 |
|---|---|
| `<伺服器目錄>/buildgit/` | 版本庫：所有世界的版本紀錄、起點世界副本、未存的標記、進行中的衝突整合 |
| `world/dimensions/minecraft/bg_<草稿>…` | 每個草稿的世界（Paper 26.x 的位置；舊版與 Fabric 依伺服器的世界資料夾規則） |
| `…/bgview_<存檔>…` | 唯讀看舊版本時的暫時世界，用完就刪 |

`/bg disk`（需要 `buildgit.disk`）會列出：

- **版本紀錄**：所有分支共用，相同內容只存一份，所以沒辦法拆給個別分支；通常很小。
- **起點世界副本**：大約等於 init 時那些世界的大小；延遲複製的世界只有玩家去過的範圍。
- **每個草稿的世界**：只有走過、生成過的範圍才會寫進磁碟。不需要的草稿用 `/bg branch delete` 刪掉就會收回空間。

**回收沒人用的物件**：刪掉的草稿、真正刪除的建築世界留下的版本紀錄不會自動消失。`/bg disk clean` 在背景刪掉沒有任何分支用到的物件，完成後回報刪了幾個、釋放多少空間。
- 保留的範圍：伺服器世界與所有建築世界的分支（含回收區裡的世界、fork 到一半的世界）、進行中的整合、玩家取出還沒送回的範圍、還原前留下的 `*.before-restore-*`。
- 一天內寫入或用到的物件不刪，正在進行的存檔不受影響，伺服器照常運作。
- 啟動時有建築世界或回收區的登記格式不對、被略過時拒絕回收（不知道它們用了哪些物件）；修正後重新啟動再來。
- 刪掉的物件找不回來；需要的話先確認異地備份正常。

**把起點世界放到別的硬碟**：設定 `init.genesis-dir`。起點世界只在存檔、開草稿、看舊版時讀取，一般硬碟或 NAS 的速度就夠。已經 init 過要搬：關服，把原本的起點世界資料夾（預設 `buildgit/genesis`）整個搬到新位置，改好設定再開服。設定改了但資料還沒搬時，BuildGit 會繼續用原本的位置並在主控台提醒。實際使用的位置記在 `buildgit/genesis-dir`。

草稿只屬於開它的那個世界（地獄、終界也各自分開）：在主世界開的草稿只有主世界，在地獄開的只有地獄。`/bg branch list` 列出目前所在世界的草稿、最新存檔與時間，加上 `--all` 列出所有世界的草稿，方便找出很久沒動的草稿。

**請不要把其他世界取名為 `bg_` 或 `bgview_` 開頭**：BuildGit 用這兩個前綴命名自己的世界，`bgview_` 開頭的資料夾在啟動時會被當成殘留的暫時世界刪除。

## 7. 世界的載入與卸載

- **草稿世界**：有人需要時才載入（`try`、`go`、併回、玩家重新上線回到草稿）。一個草稿的所有世界都沒人超過 `worlds.unload-idle-seconds` 後，存到磁碟並卸載；下次進去自動載入，內容不會不見。
- **main 的世界**（受版本控制的原本世界）：多世界插件要卸載它時，只有在**它和所有草稿裡對應的世界都沒有玩家**、沒有在存檔或併回、起點世界也不在複製中時才放行，否則 BuildGit 會擋下並在主控台說明原因（Fabric 無法擋下）。
- main 的世界卸載後，還沒存的變更會保留，再次載入時接回。
- **建築世界**（§7.1）：沒人、沒有草稿與檢視、沒有未存的變更時，經過 `worlds.unload-idle-seconds` 後卸載；`/bg open` 或有人上線回到裡面時自動載入。`worlds.keep-loaded` 列出的（與是建築世界的大廳）一直載入。
- **世界沒載入時不能寫入**：版本是先記錄、再寫進世界的，所以只要分支有任何受版本控制的世界沒載入，併回、撤銷、還原、衝突整合都會被拒絕，並告訴你要先載入哪個世界。用多世界插件把它載回來就能繼續。

### 7.1 建築世界

除了伺服器原本的世界（**伺服器世界**），還可以建立**建築世界**（管理員隨時可以，一般玩家要開放，見下面「誰能建立」）：每個建築世界是獨立的版本庫，有自己的歷史與草稿，存檔、併回、撤銷、還原都只影響它自己（規劃見 [03-worlds.md](03-worlds.md)）。

| 指令 | 做什麼 |
|---|---|
| `/bg new [名稱] [選項]` | 打開建立對話框：填名稱、選世界類型（主世界、地獄、終界）、地形（一般、大型生態域、巨型化、超平坦、虛空）、規則（建築用、原版），有設範本時可選範本，再填種子、超平坦的層、虛空腳下的方塊，按「建立」後傳送進去。名稱用小寫英文、數字、`_`、`-`，留空就自動編號。也可以直接寫名稱和選項，選項名和值用空白隔開，Tab 會補全（先列選項名，打了選項名再列能填的值）：`/bg new castle type nether terrain flat`。選項有 `type`、`terrain`、`rules`、`seed …`、`layers …`（寫法和原版超平坦的預設代碼相同，由下往上，從世界最底下開始疊，例如 `layers bedrock,2*dirt,grass_block`、沙漠 `layers bedrock,3*stone,sandstone,sand;desert`）、`block …`、`template 擁有者/名稱`；這些字不能當世界名稱。寫了的選項會先填進對話框，加上 `confirm` 不開對話框直接建立。`from <存檔>`（寫在最後，可以有空格）把自己電腦上的單人世界上傳成新的建築世界，要裝 BuildGit 用戶端、設定 `player-worlds.upload`（見 §7.1） |
| `/bg open <世界>` | 進入建築世界：自己的寫名稱，別人的寫 `擁有者/名稱`；寫伺服器世界的名稱就是回去 |
| `/bg fork <擁有者/名稱> [新名稱]` | 複製一個建築世界成自己的私人世界，main 的歷史一起帶過來；成員都可以，訪客要那個世界允許複製。不給新名稱時用原本的名稱 |
| `/bg submit [說明]` | 在 fork 的世界把所在分支最後一次存檔送回來源，來源多一個 `submit-<玩家>-<編號>` 草稿；再提出一次更新同一個 |
| `/bg world submissions [list\|reject <分支> [理由]]` | 來源的擁有者與管理者看還沒處理的提出（不帶參數開選單，點一下進去看，在裡面 `/bg publish` 併回）或拒絕；整份併回後自動收掉並通知提出的人 |
| `/bg worlds [mine\|public\|recent] [頁]` | 玩家打開建築世界清單選單：我的（自己的與受邀的）、公開（最近存檔的在前）、最近去過，點一下進去；主控台列出所有建築世界 |
| `/bg world`（在伺服器世界） | 伺服器世界也能加成員、設可見性（`members`、`visibility`，管理員當擁有者）。成員的角色加在原本的權限節點之上；私人時沒有權限、也不是成員的人送到大廳（`worlds.lobby`，沒設就不能改成私人） |
| `/bg world members [list\|add <玩家> [builder\|manager]\|remove <玩家>]` | 在建築世界裡管理成員：擁有者可以加、移除建造者與管理者；管理者只能加、移除建造者。邀請時對方要在線上（已經是成員的改角色不用）。不帶參數開成員選單，點線上玩家的頭邀請 |
| `/bg world visibility public\|private` | 擁有者把世界改成公開（所有人都能進來參觀）或私人（只有成員，預設） |
| `/bg world rename <新名稱>` | 擁有者改名；世界資料夾、草稿資料夾跟著改。裡面的人先被送回伺服器世界，好了以後再 `/bg open` |
| `/bg world transfer <玩家> confirm` | 擁有者把世界轉讓給線上的玩家，自己留下來當管理者；資料夾名稱有擁有者時跟著改 |
| `/bg world delete confirm` | 擁有者刪除：裡面的人送回伺服器世界，世界、草稿、版本庫原地改名放進回收區（`player-worlds.trash-days` 天後真正刪掉） |
| `/bg world undelete [名稱]` | 列出自己在回收區裡的世界，或找回一個（管理員寫 `擁有者/名稱` 找回任何人的） |
| `/bg world unlist\|relist [世界]` | 管理員下架（強制私人，擁有者不能改回公開）或解除；沒給世界時是所在的世界 |
| `/bg world template on\|off [世界]` | 管理員把建築世界設成範本或取消；沒給世界時是所在的世界。範本出現在建立對話框，從它建立等於 fork（歷史一起帶過去）；`player-worlds.templates` 列的不受影響 |
| `/bg world settings [<設定> <值>]` | 擁有者與管理者改這個世界的設定（下表）；只打 `/bg world settings` 開設定選單，點一下換下一個值；顯示名稱、簡介會開對話框讓你輸入。改了馬上生效 |

| 設定 | 值 | 說明 |
|---|---|---|
| `time` | `noon`、`sunset`、`midnight`、`day`、`cycle`，或 0～23999 的刻數 | 固定在某個時刻，或正常流動 |
| `weather` | `clear`、`rain`、`thunder`、`cycle` | 只影響這個世界，不跟著伺服器世界 |
| `difficulty` | `peaceful`、`easy`、`normal`、`hard`、`server` | `server` 是照伺服器的難度 |
| `mobs` | `on`、`off` | 生成動物、怪物、巡邏隊、幻影、流浪商人、突襲 |
| `nature` | `on`、`off` | 隨機刻（作物、冰、樹葉）、火焰蔓延、生物破壞方塊、藤蔓蔓延 |
| `border` | 16～`player-worlds.max-border`，或 `default` | 世界邊界的邊長 |
| `border-center` | （不用值） | 邊界中心設成自己站的位置 |
| `spawn` | （不用值） | 傳送進來時出現的地方（也是死後重生的地方）設成自己站的位置 |
| `respawn` | `on`／`off`（預設 `on`） | 死在這個世界的人在出生點重生（自己的床、重生錨在這個世界時照原版）；關掉時照原版回伺服器世界 |
| `member-mode` | `creative`、`survival`、`adventure` | 成員進來時的遊戲模式 |
| `visitor-mode` | `spectator`、`adventure` | 訪客進來時的遊戲模式 |
| `visitor-interact` | `on`、`off` | 冒險模式的訪客能不能按按鈕、拉桿、開門、踩壓力板 |
| `inventory-member`／`inventory-visitor` | `on`、`off` | 成員／訪客的背包隔離：在這個世界有自己的背包（見下面） |
| `kit-member`／`kit-visitor` | （不用值）或 `clear` | 打開大箱子放成員／訪客的起始物品（含裝備與副手），關上就存好；`clear` 清空；要先打開那個角色的背包隔離 |
| `kit-member-reset`／`kit-visitor-reset` | `on`／`off`（預設 `on`） | 每次進來都換上起始物品；關掉時只有第一次給，之後是自己上次留在這裡的背包 |
| `kit-member-respawn`／`kit-visitor-respawn` | `on`／`off`（預設 `on`） | 死後在這個世界重生時，背包換成起始物品 |
| `display-name` | 文字（最多 32 字），不給是清掉 | 清單上顯示的名稱，可以用中文；指令裡還是用原本的名稱 |
| `description` | 文字（最多 120 字），不給是清掉 | 清單上的簡介 |
| `icon` | 物品 id，不給是手上的物品 | 清單上的圖示（預設草方塊） |
| `allow-fork` | `on`／`off`（預設 `on`） | 公開時訪客能不能用 `/bg fork` 複製；成員都可以 |
| `accept-submissions` | `on`／`off`（預設 `on`） | fork 的世界能不能用 `/bg submit` 送改動回來 |

- **誰能建立**：有 `buildgit.world.admin` 的人隨時可以；一般玩家要 `player-worlds.enabled: true` 並給 `buildgit.world.create`，每人最多 `player-worlds.max-per-player` 個（預設 3）。`open`、`home`、`worlds` 所有人都能用，能進哪些世界看成員名單與可見性。
- **上傳單人世界**：`/bg new <名稱> from <存檔>`，`<存檔>` 是玩家 `.minecraft/saves` 裡的資料夾名稱。只帶主世界的方塊與實體（地獄、終界、玩家資料都不帶），沒有歷史；存檔以外的地方照 `terrain`（預設虛空）生成。流程：伺服器請用戶端打包，發一個 15 分鐘有效、綁定大小的預簽上傳網址，用戶端直接傳到 S3 的 `uploads/<玩家 UUID>/<隨機>`；伺服器下載（超過大小就中止）、核對大小與 SHA-256、刪掉，再逐個 chunk 檢查：讀不懂、位置不對、沒生成完的不收，比伺服器新的版本存的整個拒絕，較舊的升級成目前的格式。不能用管理方塊的人（不是 op 或不在創造模式）上傳的指令方塊、結構方塊等換成空氣，指令方塊礦車拿掉，告示牌等的點擊指令清掉；`ignored-entities` 列的實體不收。要先在 `s3.yml` 設好連線、`player-worlds.upload.connection` 指定它；S3 的 endpoint 是內部位址時填 `public-endpoint`。玩家中途離開時伺服器會刪掉上傳到一半的檔案，但上傳可能在刪除之後才完成，建議替 bucket 的 `uploads/` 設一天後自動刪除的生命週期規則。
- **匯入伺服器上的世界**（管理員，`buildgit.world.admin`）：`/bg new <名稱> import <世界資料夾> [owner <玩家>]`。`<世界資料夾>` 是伺服器目錄裡的資料夾（例如多世界插件的世界、放進來的存檔），補全會列出看起來是世界的資料夾。只帶主世界那一層的方塊與實體（26.x 的存檔讀 `dimensions/minecraft/overworld`；要帶地獄的話寫那個維度的資料夾），沒有歷史；原本的世界不動，載入著的會先存檔再複製。`owner` 是建好後的擁有者（要在線上，不受每人上限限制），沒寫就是自己；有寫 `owner` 時主控台也能用。和上傳一樣逐個 chunk 檢查、升級舊版本的資料，但管理方塊照樣保留。其他選項（`type`、`terrain`、`rules` 等）和 `/bg new` 相同，沒寫 `terrain` 時世界以外的地方是虛空。
- **角色**：在建築世界裡，BuildGit 的指令權限由那個世界的角色決定，不看你給的 `buildgit.role.*`：擁有者與管理者等於 manager，建造者等於 builder，訪客只能看（`status`、`log`、`diff`、`blame` 等），不能改方塊、不能進草稿或檢視舊版本。有 `buildgit.world.admin` 的人在任何世界都能做所有事。`init`、`reload`、`backup` 等伺服器管理照舊看權限節點。
- 成員被移除或世界改成私人時，裡面沒資格的人會被送回伺服器世界；離線期間被移除的，上線時送回。
- **遊戲模式**：進建築世界時換成這個世界設定的模式（預設成員創造、訪客旁觀者）；回伺服器世界時換回原本的模式。原本的模式與進來前的位置（世界被刪、改名、被移出時送回那裡）記在 `buildgit/players/<uuid>.yml`。回伺服器世界沒有 BuildGit 的指令，交給伺服器原本的傳送指令（例如 `/spawn`、多世界插件），重啟後照樣恢復。
- **背包**：預設所有世界共通。打開背包隔離（成員、訪客分開：`inventory-member on`、`inventory-visitor on`，或伺服器設 `player-worlds.inventory-isolation: always` 兩者都隔離、`never` 都不隔離）後，進這個世界時把原本的背包、終界箱、經驗、狀態效果收起來，換成這個世界的那一份，回伺服器世界時換回原本的。預設每次進來都換上起始物品（`kit-*-reset off` 時只有第一次給，之後是自己上次留下的），死後重生也重給（`kit-*-respawn`）。從舊版升級的世界照舊：成員的背包保留、訪客每次重設、死後不重給。收起來的放在 `buildgit/players/<uuid>/`（原本的是 `server.dat`，各世界是 `<世界 id>.dat`），起始物品在世界的版本庫旁邊（`kit-member.dat`、`kit-visitor.dat`）；都記下資料版本，升級 Minecraft 後讀取時照樣升級物品。
- **訪客的限制**：不能改方塊，也不能開箱子、和實體互動；WorldEdit、Axiom 的編輯會被擋下；走到世界邊界（預設 `player-worlds.border`，1024×1024）外會被拉回來。冒險模式的訪客在 `visitor-interact` 打開時可以按機關，造成的改變記成那個訪客改的，之後成員存檔時會一起存進去。
- **傳送門**：建築世界裡的地獄門、終界門、折躍門都不能用（不然會被送到伺服器的地獄、終界）。
- **建築用規則**（建立時選）：上面的設定預設成固定正午與晴天、和平、不生成生物、沒有自然變化；「原版」是正常流動、照伺服器的難度、生成生物、有自然變化。建立後都可以用 `/bg world settings` 改。建築世界一律關掉「旁觀者生成地形」與地獄門，終界類型沒有終界龍。
- 類型、地形、種子建立後不能改。一個建築世界只有一個維度。設定記在它版本庫裡的 `repo.yml`。
- **世界資料夾**：名稱由 `player-worlds.world-name` 決定，例如 bob 的 `castle` 是 `bw_bob_castle`（Paper 26.x 在 `world/dimensions/minecraft/` 底下，1.21.11 在伺服器根目錄）。它的草稿預設是 `bw_bob_castle-<草稿>`（`draft-world-name`、`merge-world-name`、`view-world-name` 可以另外設定草稿、衝突整合、看舊版的世界資料夾名稱）。名稱和現有的世界、資料夾、伺服器世界衝突時會拒絕建立。
- **資料放在哪裡**：預設版本庫在 `buildgit/worlds/<id>/`，物件和伺服器世界共用 `buildgit/objects/`。清單在 `buildgit/worlds.yml`，不要手動改。
- 想把整個建築世界（含歷史）放進它的世界資料夾，壓縮一個資料夾就帶走：

  ```yaml
  player-worlds:
    paths:
      repo: "{world-folder}/buildgit"
      objects: "{repo}/objects"
  ```

  這樣用其他工具刪掉世界資料夾時，歷史也會一起刪掉。
- `paths` 只影響之後建立的世界；已經建立的照 `buildgit/worlds.yml` 記的位置找。`{server}`、`{buildgit}`、`{world-folder}` 每次啟動時才算，所以整個伺服器資料夾搬家、或 Paper 從 1.21.11 升到 26.x 搬動世界資料夾之後照樣找得到。
- 版本紀錄的備份涵蓋所有建築世界（§8.1），可以用 `world=` 單獨還原一個。世界資料夾的備份只帶建築世界的世界資料夾（草稿不帶，可以從版本重建），不帶它們的版本庫。在建築世界裡 `pull`／`push` 的權限照那個世界的角色（建造者以上；訪客在「允許複製」開著時只能 `pull`），不看 `buildgit.pull`、`buildgit.push`。

## 8. 備份與搬家

- **一起備份**：`buildgit/`、起點世界（有設定 `init.genesis-dir` 時在那個位置）與所有世界資料夾（包含 `bg_` 開頭的草稿世界）要在**同一個時間點**備份，最好先關服。只還原其中一邊，世界和版本紀錄會對不上。
- 搬到新主機：關服後整個伺服器目錄一起搬即可。
- 不要手動修改 `buildgit/` 裡的檔案。

### 8.1 備份

BuildGit 可以把備份送到 S3 相容的物件儲存（AWS S3、Backblaze B2、自架的 S3 相容服務等）或本機的資料夾（例如另一顆硬碟）。主機整台壞掉、或世界被弄壞時，可以找回來。備份分兩種：

| 種類 | 內容 | 適合 |
|---|---|---|
| **版本紀錄**（`history`） | 所有分支與存檔、起點世界、受版本控制的世界的 `level.dat`（種子） | 常常備份：量小，只傳新的存檔 |
| **世界資料夾**（`world`） | 整個世界資料夾：方塊、玩家資料（背包、位置、進度）、地圖畫、計分板、沒存檔的變更 | 每天或玩家離開後備份：量大，只傳變動的檔案 |

**設定**：第一次啟動後，設定資料夾（Paper：`plugins/BuildGit/`；Fabric：`config/buildgit/`）會有兩個檔案，改完用 `/bg reload` 生效。

- `s3.yml`：S3 的連線（網址、bucket、金鑰），可以有好幾組。有金鑰，所以和其他設定分開；外掛更新時不會改動它。請不要分享這個檔案。金鑰只需要這個 bucket 的讀、寫、列出、刪除權限。
- `backup.yml`：備份工作。每個工作自己決定備份什麼、送到哪裡、何時、留幾份。預設有兩個工作，所以只要在 `s3.yml` 填好 `default` 連線就會開始備份：

  | 工作 | 內容 | 時機 | 保留 |
  |---|---|---|---|
  | `history-s3` | 版本紀錄 | 每小時整點，有變化才備份 | 48 份 |
  | `world-s3` | 世界資料夾 | 每天 04:00，以及伺服器沒有玩家滿 10 分鐘後 | 14 份 |

每個工作的設定：

| 設定 | 說明 |
|---|---|
| `what` | `history`（版本紀錄）或 `world`（世界資料夾） |
| `target` | `s3:<s3.yml 裡的連線名稱>`，或 `local:<資料夾>`（相對伺服器根目錄或絕對路徑） |
| `path` | 在目的地裡的位置；同一個 bucket 放多台伺服器或多個工作時，每個用不同的名稱 |
| `schedule` | cron 排程「分 時 日 月 星期」，例如 `"0 * * * *"` 每小時、`"0 4 * * *"` 每天 4 點、`"*/30 * * * *"` 每 30 分鐘；時區是 `backup.yml` 的 `timezone`（空白用伺服器的） |
| `idle-minutes` | 伺服器沒有玩家滿幾分鐘後備份一次；上次備份之後有玩家上線過才會，所以沒人的伺服器不會一直備份 |
| `keep` | 保留幾份快照 |
| `enabled` | `false` 暫停這個工作 |
| `storage`（世界資料夾） | `auto`（預設）：本機用 `folders`；S3 的 bucket 有開版本控制時用 `versioned`，沒有時用 `hashed`。`folders`：每份快照一個完整的資料夾，任何工具都能直接取用（本機用硬連結，沒變的檔案不佔空間）。`versioned`：用 bucket 的版本控制，只多存變動的檔案。`hashed`：檔案以內容命名，一樣的內容只存一份，要用 BuildGit 還原 |
| `announce`（世界資料夾） | 開始與結束時在聊天室提示玩家 |
| `exclude`（世界資料夾） | 額外不備份的路徑（相對伺服器根目錄，可用 `*`） |

`schedule` 與 `idle-minutes` 可以只設一個，也可以都設。從舊版升級時，原本 `s3.yml` 的設定會自動轉成 `history-s3` 工作（備份接得上），`s3.yml` 裡的 `enabled`、`prefix`、`interval-minutes`、`keep` 之後可以刪掉。

**怎麼運作**：

- 所有工作在背景依序執行，伺服器照常運作。只上傳新的或變動的部分；中途斷線的話，下一次接著傳。網路暫時不通時會自動重試幾次；連續失敗 3 次起，每次都會提醒線上有 `buildgit.backup` 權限的人。
- **版本紀錄**有新的存檔、分支移動或刪除、起點世界的檔案變了，才寫一份快照。伺服器世界與每個建築世界各自判斷、各自寫快照（建築世界在 `<prefix>/worlds/<id>/`，連設定與起始物品一起），物件共用一份。進行中的衝突整合（`merge/<id>.yml`）也在快照裡，還原後可以照常 `/bg merge continue`；太大而存在 region 外的 chunk（`c.<x>.<z>.mcc`）和它的 region 檔一起備份。存檔本身永遠保留，刪掉的只是「那個時間點有哪些分支」的紀錄。用了延遲複製（`init.lazy-copy-above-mb`）的世界，起點世界不完整，無法單靠版本紀錄還原；`/bg backup status` 會列出來，請一併做世界資料夾的備份。
- **世界資料夾**備份時，會短暫暫停自動存檔、把世界寫回磁碟、在本機複製有變動的檔案，再恢復存檔並在背景上傳，有提示時玩家會看到「伺服器正在備份世界」。只處理上次備份之後有玩家進去過的世界，其他沿用上一份；從來沒有玩家進去過的世界不備份。背包隔離收起來的背包與玩家的切換狀態（`buildgit/players/`）跟著一起備份、一起還原，和世界裡的玩家資料是同一個時間點。草稿與檢視世界可以從版本重建，不備份。
- 放在同一顆硬碟的本機備份只防得了誤刪與損毀，防不了硬碟壞掉。
- 其他插件、模組的資料（`plugins/`、`config/`）不在備份裡，請照常另外備份。

**指令**（需要 `buildgit.backup`）：

| 指令 | 作用 |
|---|---|
| `/bg backup status` | 每個工作的目的地、排程、上次成功的時間、上次的錯誤 |
| `/bg backup now [工作]` | 立刻執行（沒給名稱就是所有工作） |
| `/bg backup list [工作]` | 列出最近的快照 |
| `/bg backup fetch <分支> [新名稱]` | 從版本紀錄的備份取回一個分支（例如誤刪的草稿），不必重新啟動；名稱已有分支時要給新名稱 |
| `/bg backup restore <工作> [快照]` | 下載快照準備還原（只能在主控台；沒給快照就用最新的） |
| `… world=<擁有者/名稱>` | 加在 `list`、`fetch`、`restore` 後面：改成那個建築世界的快照。`restore` 不必重新啟動：下載完卸載那個世界、換上，原本的改名為 `….before-restore-<時間>` 留著；這台伺服器沒有這個世界時照備份放回去 |

**還原世界資料夾**（例如世界被弄壞，回到某一天）：

1. 在主控台執行 `/bg backup restore world-s3`（或先 `/bg backup list world-s3` 看有哪些快照，再指定一份）。伺服器照常運作，下載到 `buildgit-restore/`。
2. 看到「已下載好」後重新啟動。世界載入前會換上：世界資料夾、版本庫的分支與還沒存檔的標記都回到那個時間點，草稿世界之後由版本重建。原本的都改名為 `….before-restore-<時間>` 留著，確認沒問題後再自己刪。

**整台還原**（例如主機壞掉，在新主機上）：

1. 裝好同一個或更新的 Minecraft 版本、BuildGit 與原本的資料包、模組，先啟動一次讓世界產生。要讓起點世界以外的地形一樣，換了 Minecraft 版本時請在世界產生前把 `server.properties` 的 `level-seed` 設成原本的種子（還原時會提醒）。
2. 把原本的 `s3.yml`、`backup.yml` 放回去，`/bg reload`。不必先 `/bg init`：還沒有版本庫時 `/bg backup status`、`list`、`restore`（伺服器世界）照樣能用。
3. 在主控台執行 `/bg backup restore history-s3`。下載完會先在旁邊把 main 的世界從「起點世界＋main 的最新存檔」重建好；看到「已下載好」後重新啟動，版本庫、這些世界的 `region`／`entities`、`level.dat` 會換成備份的。
4. 有世界資料夾的備份時，接著 `/bg backup restore world-s3` 再重新啟動一次，玩家資料、沒存檔的變更等也會回來。
5. 建築世界一個一個用 `/bg backup restore history-s3 world=<擁有者/名稱>` 放回去（不必重新啟動）。

- 不想還原了：重新啟動前刪掉 `buildgit-restore/`。
- 換上時出錯（例如磁碟滿）會停止啟動；修正後重新啟動會從中斷的地方繼續。
- 較新的 Minecraft 寫的備份不能還原到較舊的伺服器。

## 9. 升級 Minecraft 版本

- 換成新版本的 jar 即可。舊的歷史、起點世界不會被改寫，讀取時由伺服器自己的資料升級程式（DataFixer）轉成新版格式，所以升級後仍然可以看舊存檔、還原到升級前的版本。
- **不能降版**：版本庫被較新版本的伺服器寫過之後，較舊的伺服器會拒絕開啟它。升級前請先備份（§8）。
- **想讓起點世界也換成新格式**：升級後執行 `/bg genesis upgrade`（需要 `buildgit.genesis`）。它會把起點世界裡舊版本存的 chunk 改寫成新版的格式，之後讀取時就不用每次轉換；版本跨得越多，省下的越多。
  - 內容不會改變，舊存檔照樣可以看、可以還原。
  - 在背景逐個檔案進行，伺服器照常運作，bossbar 顯示進度。中途關服也沒關係：沒做完的檔案維持原樣，下次再執行即可。
  - 執行前請先備份（§8）；改寫後的起點世界只有這個版本以上的伺服器讀得懂（本來就不能降版）。
  - 用延遲複製的世界：之後才第一次載入的 chunk 會照磁碟上的樣子複製進起點世界，可能又是舊格式，需要時再執行一次。
- 用戶端帶到單人世界改的範圍，單人世界的版本要和伺服器相同或較新。

## 10. 已知限制

- Paper 上有 Axiom bypass 權限（`axiomadmin.bypass_region_checks`）的玩家：方塊與實體照常記錄，但 Axiom 生態域筆刷的變更記錄不到。BuildGit 是讀 AxiomPaper 的內部來記錄這些玩家，AxiomPaper 改版後可能對不上，這時會自動停用並在主控台警告，這些玩家的方塊編輯就記錄不到。這種玩家開啟 Axiom 時會收到提醒。
- DebugStickPro 凍結中的方塊（伺服器上暫時是屏障）先不存檔，解凍後才存進版本；凍結期間 `/bg status` 會一直顯示它未存。
- 非玩家造成的實體移除（例如盔甲座被苦力怕炸掉）不記錄，和方塊的自然變化一樣。
- 生態域：草稿和 main 改了同一處（4×4×4 格）的生態域時，併回保留 main 的，併回預覽會列出數量，需要的話再用 `/fillbiome` 修正。帶到單人世界改（`pull`／`push`）不帶生態域。其他插件以 API 改的生態域記錄不到，可以用 `/bg mark` 補記。
- `/place feature` 這類世界產生器放的大型結構可能記錄不完整，可以用 `/bg mark` 補記範圍。
- 伺服器當機時，最後約 1 秒內的變更可能沒記到（`tracking.journal-flush-ticks`）。
- Fabric 版的 Axiom 記錄讀的是 Axiom 模組的內部，Axiom 改版後可能對不上，這時會自動停用並在主控台警告（「這個版本的 Axiom 和 BuildGit 對不上」），Axiom 的編輯就記錄不到。
- Fabric 版目前沒有 DebugStickPro 整合，也沒有給其他模組的 API。

## 11. 常見問題

**玩家說看不到某個指令**：缺權限，見 §4。

**主控台說「受版本控制，BuildGit 先不讓它卸載」**：那個世界或它的草稿世界裡還有玩家，或正在存檔。等玩家離開後再卸載。

**併回時說「這些世界沒有載入」**：用多世界插件把列出的世界載入後再試一次。

**刪除草稿時說「有還沒併回的存檔」**：草稿裡有存過、但還沒併回 main 的內容，刪掉就找不回來。確定不要了再加 `--force`。

**init 之後不能開草稿**：起點世界還在背景複製，完成時會通知。

**磁碟不夠**：先 `/bg disk` 看是哪部分，再用 `/bg branch list` 找出不用的草稿刪除。
