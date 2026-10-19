# BuildGit

給 Minecraft 建築師用的版本控制。只記錄玩家造成的變更（含 WorldEdit／FAWE、Axiom、除錯棒與 DebugStickPro、生態域，以及連帶變化），以「起點世界＋覆寫表」保存每個版本；可以開草稿世界試做、存檔、併回、查歷史與責任歸屬。

- 平台：Paper 26.3、26.2、1.21.11；Fabric 26.3、26.2、1.21.11
- 管理員手冊（安裝、init、權限集、備份、升級）：[`docs/02-admin.md`](docs/02-admin.md)
- 設計文件：[`docs/01-design.md`](docs/01-design.md)；需求：[`docs/00-requirements.md`](docs/00-requirements.md)

## 下載

每次推送都會由 GitHub Actions 打包，jar 在該次執行的 Artifacts；推送 `v*` 標籤時會建立 Release。

每個 Minecraft 版本各有一個 jar，請下載與伺服器相同版本的那個：

| 伺服器 | 檔案 | Java |
|---|---|---|
| Paper 26.3 | `BuildGit-<版本>-paper-26.3.jar` | 25 |
| Paper 26.2 | `BuildGit-<版本>-paper-26.2.jar` | 25 |
| Paper 1.21.11 | `BuildGit-<版本>-paper-1.21.11.jar` | 21 以上 |
| Fabric 26.3 | `BuildGit-<版本>-fabric-26.3.jar` | 25 |
| Fabric 26.2 | `BuildGit-<版本>-fabric-26.2.jar` | 25 |
| Fabric 1.21.11 | `BuildGit-<版本>-fabric-1.21.11.jar` | 21 以上 |

版本不符時插件會在啟動時提示並自行停用；Fabric 版由 Fabric Loader 拒絕載入。

Fabric 版需要 Fabric API；Fantasy（分支世界）與 fabric-permissions-api 已包在 jar 裡。版本庫在伺服器目錄的 `buildgit/`，設定在 `config/buildgit/`。Fabric 版會記錄 WorldEdit 與 Axiom 模組的編輯，目前沒有 DebugStickPro 整合與公開 API。用戶端也可以裝 Fabric 版，用來把選取範圍帶到單人世界改再送回（見下方「帶到單人世界改」）。

## 編譯

需要 JDK 21 與 JDK 25（Gradle toolchain 會自動挑選）。

```sh
./gradlew build
# 產物：paper/plugin/build/libs/BuildGit-<版本>-paper-26.2.jar
#       paper/plugin-1_21_11/build/libs/BuildGit-<版本>-paper-1.21.11.jar
#       fabric/mc26_2/build/libs/BuildGit-<版本>-fabric-26.2.jar
#       fabric/mc1_21_11/build/libs/BuildGit-<版本>-fabric-1.21.11.jar
```

## 玩家使用說明

不懂版本控制也沒關係。記住一件事就好：**想試新點子時開一個「草稿」，滿意了再「併回」主世界。**
你在草稿裡做什麼都不會影響主世界，弄壞了直接離開就好。

> 指令需要權限（預設只有 OP 有）。看不到某個指令，請找管理員開權限。

### 最快上手：輸入 `/bg`

`/bg` 會打開一個箱子選單，常用功能都在裡面，點一下就執行。上排是動作，下排是所有草稿，點草稿就會過去。

### 一次完整的流程

1. **開草稿**：`/bg try new-roof`
   會開出一個和主世界一模一樣的副本，把你傳送到同一個位置。畫面上方的提示條會顯示「草稿 new-roof」。
2. **盡情蓋**：手動、WorldEdit、FAWE、Axiom、除錯棒（含 DebugStickPro）都可以，改了什麼、誰改的都會自動記下來。
3. **存檔**（可選）：`/bg save 屋頂骨架完成`
   就像遊戲的存檔點，之後可以回頭看或還原。就算忘了存，沒人在蓋一陣子後也會自動存檔。
4. **併回主世界**：`/bg publish`
   會先列出改了多少格、誰改的，按聊天欄的 **[確認併回]** 才會真的寫進主世界。
   併回後可以按 **[刪除草稿]** 收掉這個草稿。
5. **不要了？** `/bg back` 回到原本的世界，草稿留著，之後還能 `/bg go new-roof` 回來繼續。
6. **刪除草稿**：`/bg branch delete new-roof`（主世界不能刪）。
   還有沒存的變更、或有存過但還沒併回的存檔時會被擋下，確定不要了再加 `--force`；如果別的草稿是從它開出來的，要先處理那個草稿。
   `/bg branch list` 可以看所有草稿與各自最後存檔的時間。

只想併回一部分？先用 WorldEdit 選取範圍（或 `/bg sel pos1`、`/bg sel pos2`），再 `/bg publish --selection`。

### 看看改了什麼

| 想知道…… | 指令 |
|---|---|
| 我還有多少沒存 | `/bg status` |
| 在世界裡標出改過的方塊（會發光一陣子） | `/bg diff` |
| 這個方塊是誰改的 | `/bg inspect` 開啟後點方塊；或看著方塊打 `/bg blame` |
| 過去的存檔 | `/bg log`；每頁 8 筆，點 **[下一頁]** 或 `/bg log 2` 翻頁 |
| 所有草稿的分支歷史圖（像 `git log --graph`） | `/bg graph`；每頁 8 筆，點 **[下一頁]** 或 `/bg graph 2` 往前翻；加 `--all` 連自動存檔一起顯示 |
| 某次存檔改了哪些 | `/bg show <存檔編號>` |
| 舊版本長什麼樣子 | `/bg view <存檔編號>`（唯讀，不能動；`/bg back` 回來） |
| 大家在哪個草稿 | `/bg who` |

存檔編號就是 `/bg log` 裡的那串黃色英數字，點一下就會顯示那次存檔的內容；打指令時只要打前幾碼，按 Tab 也會列出最近的存檔。

### 改壞了怎麼辦

- **撤銷某一次存檔**，之後的變更保留：`/bg revert <存檔編號>`
- **整個退回舊版本**：`/bg restore <存檔編號>`；只退回選取範圍：加 `--selection`

`restore` 會先讓你確認範圍。兩者都會變成一次新的存檔，所以「還原」本身也能再撤銷。

### 遇到衝突

如果你在草稿裡改的方塊，主世界那邊也有人改了，併回時就會「衝突」。這時會把你帶到一個**整合世界**，
衝突的方塊會發光，畫面上方顯示還剩幾處。

- 點聊天欄的清單（或 `/bg merge goto 1`）跳到衝突的位置
- 想要哪一邊？直接把方塊改成你要的樣子就好；或用：
  - `/bg merge take theirs here`：附近的衝突都用**草稿**的版本
  - `/bg merge take ours here`：附近的衝突都用**主世界**的版本
  - 把 `here` 換成 `all` 就是全部
- 拿不定主意？`/bg merge view ours`／`theirs` 去看兩邊原本的樣子，`/bg back` 回來
- 處理完：`/bg merge continue`；不想合了：`/bg merge abort`（什麼都不會寫入）

### 帶到單人世界改

想在自己電腦上慢慢改？用戶端裝 BuildGit 的 Fabric 版（伺服器是 Paper 或 Fabric 都可以）：

1. 在伺服器上輸入 `/bg pull`。有選取範圍就帶走選取範圍；沒有的話，在草稿裡帶走草稿改過的地方，在主世界帶走你周圍一塊。名稱自動取（草稿名稱或你的名字），也可以自己給：`/bg pull house`。
2. 點訊息下方的 **[在單人世界打開]**：會離開伺服器，開一個這個範圍專屬的單人世界（第一次會建立創造模式、和平、虛空的世界），範圍放到同一個座標，你也會站在伺服器上的位置。也可以自己開任一個單人世界（版本相同或較新），輸入 `/bg load <名稱>`。
3. 改好後離開世界，會自動存成改過的版本。
4. 連回伺服器時會列出還沒送回的範圍，點 **[送回 …]**（或輸入 `/bg push <名稱>`）：改的東西會變成草稿，用 `/bg go` 看、`/bg publish` 併回。

取出之後伺服器上別人改的部分照常合併，兩邊都改到同一格時就是一般的衝突。

### 小提示

- 只有管理員在裡面執行過 `/bg init` 的世界受版本控制；地獄、終界與 Multiverse 等多世界插件的世界要各自 init。init 不會讓伺服器暫停：起點世界在背景複製。
- 草稿世界沒人在裡面時會自動從記憶體卸載，下次進去會自動載入，內容不會不見。
- 伺服器重開也不會遺失沒存的變更（當機時最多少記最後一秒）。
- 完整指令清單：遊戲內 `/bg help`。

## 指令總覽

```
/bg                      主選單（箱子介面）
/bg init                 讓所在的世界開始受版本控制（管理員；每個世界各自 init）
/bg try <名稱>           開草稿並傳送到同一個位置（名稱只能用小寫英文、數字、底線、連字號）
/bg go <草稿|main>       到某個草稿或主世界
/bg save [訊息]          存檔（里程碑）
/bg publish [--selection] 預覽併回，點確認後寫入；--selection 只併回選取範圍
/bg back                 不併回，回到來源世界
/bg merge take|continue|abort   有衝突時處理整合世界
/bg revert <存檔>        撤銷某次存檔
/bg restore <存檔> [--selection] 還原到舊版本
/bg view <存檔>          唯讀地看舊版本
/bg branch list|create|delete <名稱> [--force]  管理草稿
/bg graph [頁數] [--all]  分支歷史圖（分頁）
/bg pull [名稱]          把選取範圍（沒選時是草稿改過的地方或周圍）帶到單人世界（用戶端要裝 BuildGit）
/bg load <名稱>          （單人世界）放進取出的範圍
/bg push <名稱> [草稿]   把單人世界改過的範圍送回，存成草稿
/bg disk                 BuildGit 用掉的磁碟空間（管理員）
/bg log、show、diff、blame、inspect、who、sel
```

權限與權限集見[管理員手冊 §4](docs/02-admin.md#4-權限)。

## 給其他插件的 API

`api/` 模組；以 `ServicesManager` 取得 `BuildGitApi`，可直接記錄變更或以 `attribute(actor)` 指定作者。

## 授權

[MIT](LICENSE)
