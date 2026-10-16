# DebugStickPro 編譯期 API

DebugStickPro 的事件類別與 `FreezeBlockManager.isFreezeBlock` 簽章，來源 [TWME-TW/DebugStickPro](https://github.com/TWME-TW/DebugStickPro)（Apache-2.0，見 LICENSE）。

只供 `paper:plugin` 以 compileOnly 引用；執行時使用伺服器上 DebugStickPro 自己的類別。發佈的 jar 不得包含 `dev/twme/debugstickpro/`，由 `paper:plugin` 的 `verifyNoAxiom` 檢查。
