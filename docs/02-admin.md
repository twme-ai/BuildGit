# BuildGit 管理員手冊

給架設與管理伺服器的人看。玩家怎麼用請看 [README 的玩家使用說明](../README.md#玩家使用說明)；內部怎麼運作請看[設計文件](01-design.md)。

## 1. BuildGit 做什麼

BuildGit 只記錄**玩家造成的變更**（手動、指令、WorldEdit／FAWE、Axiom，以及它們引起的連帶變化，例如水流、紅石、方塊掉落；也包括 `/fillbiome`、`//setbiome` 等改的生態域），像 git 一樣存成一個個版本。玩家可以：

- 開「草稿」（主世界的副本）試做，滿意了再併回主世界；
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
| `buildgit.role.builder` | 一般建築師 | viewer 全部，加上開草稿（`try`）、存檔（`save`）、帶到單人世界改再送回（`pull`、`push`）。**不能把草稿併回主世界** |
| `buildgit.role.manager` | 負責審核、維護主世界的人 | builder 全部，加上併回（`publish`）、自訂訊息存檔（`commit`）、處理衝突與底層合併（`merge`）、撤銷（`revert`）、還原（`restore`）、手動補記（`mark`）、建立與刪除草稿（`branch create`、`branch delete`） |
| `buildgit.admin` | 伺服器管理員 | manager 全部，加上 `init`、`reload`、`disk`、`genesis`、`backup` |

LuckPerms 範例：

```
/lp group builder permission set buildgit.role.builder true
/lp group staff permission set buildgit.role.manager true
```

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
| `buildgit.go` | `go`：到某個草稿或主世界 |
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
| `buildgit.disk` | `disk`：BuildGit 用掉的磁碟空間 |
| `buildgit.genesis` | `genesis upgrade`：把起點世界改寫成目前版本的格式（§9） |
| `buildgit.backup` | `backup`：異地備份的狀態、立刻備份、列出快照（§8.1） |

注意：

- 包含關係由 BuildGit 自己判斷，Paper 與 Fabric 相同，所以在 Fabric 上給權限集也有效。
- 因為這樣，**對權限集裡的單一節點設成 false 不會把它拿掉**。想少給某一項時，請改成逐項給節點，不要用權限集。
- 沒有某項權限的玩家看不到那個子指令。
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
| `sync.max-blocks` | 12000000 | `/bg pull` 一次最多帶走的方塊數 |
| `sync.radius` | 80 | 沒有選取範圍、也不在改過的草稿裡時，`/bg pull` 帶走玩家周圍多少格（水平半徑、整個高度） |
| `init.genesis-dir` | 空白 | 起點世界放在哪個資料夾（相對於伺服器目錄或絕對路徑）；空白為 `buildgit/genesis`。要重新啟動才生效（見 §6） |
| `init.lazy-copy-above-mb` | -1 | 世界存檔超過這個大小（MB）時 init 改用延遲複製（見 §3）；0 一律延遲複製，-1 一律完整複製 |
| `integrations.worldedit`／`axiom`／`debugstickpro` | true | 是否記錄 WorldEdit／FAWE、Axiom、DebugStickPro 的編輯 |
| `tracking.*` | | 連帶變化的時間窗與距離、哪些實體不記錄；通常不用改 |

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

**把起點世界放到別的硬碟**：設定 `init.genesis-dir`。起點世界只在存檔、開草稿、看舊版時讀取，一般硬碟或 NAS 的速度就夠。已經 init 過要搬：關服，把原本的起點世界資料夾（預設 `buildgit/genesis`）整個搬到新位置，改好設定再開服。設定改了但資料還沒搬時，BuildGit 會繼續用原本的位置並在主控台提醒。實際使用的位置記在 `buildgit/genesis-dir`。

草稿只屬於開它的那個世界（地獄、終界也各自分開）：在主世界開的草稿只有主世界，在地獄開的只有地獄。`/bg branch list` 列出目前所在世界的草稿、最新存檔與時間，加上 `--all` 列出所有世界的草稿，方便找出很久沒動的草稿。

**請不要把其他世界取名為 `bg_` 或 `bgview_` 開頭**：BuildGit 用這兩個前綴命名自己的世界，`bgview_` 開頭的資料夾在啟動時會被當成殘留的暫時世界刪除。

## 7. 世界的載入與卸載

- **草稿世界**：有人需要時才載入（`try`、`go`、併回、玩家重新上線回到草稿）。一個草稿的所有世界都沒人超過 `worlds.unload-idle-seconds` 後，存到磁碟並卸載；下次進去自動載入，內容不會不見。
- **主世界**（受版本控制的原本世界）：多世界插件要卸載它時，只有在**它和所有草稿裡對應的世界都沒有玩家**、沒有在存檔或併回、起點世界也不在複製中時才放行，否則 BuildGit 會擋下並在主控台說明原因（Fabric 無法擋下）。
- 主世界卸載後，還沒存的變更會保留，再次載入時接回。
- **世界沒載入時不能寫入**：版本是先記錄、再寫進世界的，所以只要分支有任何受版本控制的世界沒載入，併回、撤銷、還原、衝突整合都會被拒絕，並告訴你要先載入哪個世界。用多世界插件把它載回來就能繼續。

## 8. 備份與搬家

- **一起備份**：`buildgit/`、起點世界（有設定 `init.genesis-dir` 時在那個位置）與所有世界資料夾（包含 `bg_` 開頭的草稿世界）要在**同一個時間點**備份，最好先關服。只還原其中一邊，世界和版本紀錄會對不上。
- 搬到新主機：關服後整個伺服器目錄一起搬即可。
- 不要手動修改 `buildgit/` 裡的檔案。

### 8.1 異地備份（S3）

把版本紀錄自動備份到 S3 相容的物件儲存（AWS S3、Backblaze B2、自架的 S3 相容服務等）。主機整台壞掉時，可以把所有分支與歷史找回來。

**設定**：第一次啟動後，設定資料夾（Paper：`plugins/BuildGit/`；Fabric：`config/buildgit/`）會有 `s3.yml`。填好網址、bucket、金鑰，把 `enabled` 改成 `true`，再 `/bg reload`。

- `s3.yml` 有金鑰，所以和 `config.yml` 分開；外掛更新時不會改動它。請不要分享這個檔案。
- 金鑰只需要這個 bucket（或 `prefix` 資料夾）的讀、寫、列出、刪除權限。
- 同一個 bucket 放多台伺服器時，每台設不同的 `prefix`。

| 設定 | 預設 | 說明 |
|---|---|---|
| `enabled` | `false` | 是否備份 |
| `endpoint` | 空白 | 服務的網址，例如 `https://s3.ap-northeast-1.amazonaws.com` |
| `region` | `us-east-1` | 區域；沒有區域的服務填 `us-east-1` |
| `bucket` | 空白 | bucket 名稱 |
| `prefix` | `buildgit` | bucket 裡的資料夾 |
| `path-style` | `false` | bucket 放在網址路徑裡（自架的服務多半要 `true`） |
| `access-key`、`secret-key` | 空白 | 金鑰 |
| `interval-minutes` | `60` | 每幾分鐘檢查一次 |
| `keep` | `48` | 保留最近幾份快照 |

**怎麼運作**：

- 啟動 5 分鐘後第一次檢查，之後每 `interval-minutes` 分鐘一次。有新的存檔、分支移動或刪除、起點世界的檔案變了，才備份並寫一份快照；沒有變化就什麼都不做。
- 只上傳新的部分：已經上傳過的存檔與檔案不會重傳。第一次備份要上傳整份起點世界，比較久。
- 備份的是**已存檔**的內容：所有分支（包括草稿上的自動存檔）、起點世界、受版本控制的世界的 `level.dat`（種子）。還沒存檔的變更、草稿世界的資料夾、玩家資料與其他插件的資料不在裡面，請照常做整台伺服器的備份。
- 快照只保留最近 `keep` 份；存檔本身永遠保留，所以刪掉的只是「那個時間點有哪些分支」的紀錄。
- 用了延遲複製（`init.lazy-copy-above-mb`）的世界，起點世界不完整，無法單靠這份備份還原；`/bg backup status` 會列出來。

**指令**（需要 `buildgit.backup`）：

| 指令 | 作用 |
|---|---|
| `/bg backup status` | 備份位置、上次成功的時間、上次的錯誤、無法單靠備份還原的世界 |
| `/bg backup now` | 立刻檢查並備份 |
| `/bg backup list` | 列出遠端最近的快照 |

從備份還原的功能還在開發中。

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
- 生態域：草稿和主世界改了同一處（4×4×4 格）的生態域時，併回保留主世界的，併回預覽會列出數量，需要的話再用 `/fillbiome` 修正。帶到單人世界改（`pull`／`push`）不帶生態域。其他插件以 API 改的生態域記錄不到，可以用 `/bg mark` 補記。
- `/place feature` 這類世界產生器放的大型結構可能記錄不完整，可以用 `/bg mark` 補記範圍。
- 伺服器當機時，最後約 1 秒內的變更可能沒記到（`tracking.journal-flush-ticks`）。
- Fabric 版的 Axiom 記錄讀的是 Axiom 模組的內部，Axiom 改版後可能對不上，這時會自動停用並在主控台警告（「這個版本的 Axiom 和 BuildGit 對不上」），Axiom 的編輯就記錄不到。
- Fabric 版目前沒有 DebugStickPro 整合，也沒有給其他模組的 API。

## 11. 常見問題

**玩家說看不到某個指令**：缺權限，見 §4。

**主控台說「受版本控制，BuildGit 先不讓它卸載」**：那個世界或它的草稿世界裡還有玩家，或正在存檔。等玩家離開後再卸載。

**併回時說「這些世界沒有載入」**：用多世界插件把列出的世界載入後再試一次。

**刪除草稿時說「有還沒併回的存檔」**：草稿裡有存過、但還沒併回主世界的內容，刪掉就找不回來。確定不要了再加 `--force`。

**init 之後不能開草稿**：起點世界還在背景複製，完成時會通知。

**磁碟不夠**：先 `/bg disk` 看是哪部分，再用 `/bg branch list` 找出不用的草稿刪除。
