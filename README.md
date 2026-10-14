# BuildGit

給 Minecraft 建築師用的版本控制。只記錄玩家造成的變更（含 WorldEdit／FAWE、Axiom 與連帶變化），以「起點世界＋覆寫表」保存每個版本；可以開草稿世界試做、存檔、併回、查歷史與責任歸屬。

- 平台：Paper 26.2（Fabric 與 1.21.11 之後支援）
- 設計文件：[`docs/01-design.md`](docs/01-design.md)；需求：[`docs/00-requirements.md`](docs/00-requirements.md)

## 下載

每次推送都會由 GitHub Actions 打包，jar 在該次執行的 Artifacts；推送 `v*` 標籤時會建立 Release。

## 編譯

需要 JDK 21 與 JDK 25（Gradle toolchain 會自動挑選）。

```sh
./gradlew build
# 產物：paper/plugin/build/libs/BuildGit-<版本>.jar
```

## 基本用法

```
/bg init                 開始版本控制（複製一次起點世界）
/bg try <名稱>           開草稿並傳送到同一個位置
/bg save [訊息]          存檔
/bg publish              把草稿併回來源
/bg back                 不併回，回到來源世界
/bg log、/bg show、/bg blame、/bg inspect
```

完整指令見遊戲內 `/bg help`。

## 給其他插件的 API

`api/` 模組；以 `ServicesManager` 取得 `BuildGitApi`，可直接記錄變更或以 `attribute(actor)` 指定作者。
