# AxiomPaper 編譯期 API

AxiomPaper 的事件類別與 `Integration.CustomIntegration` 簽章，來源 [Moulberry/AxiomPaperPlugin](https://github.com/Moulberry/AxiomPaperPlugin)（MIT，見 LICENSE）。

只供 `paper:plugin` 以 compileOnly 引用；執行時使用伺服器上 AxiomPaper 自己的類別。發佈的 jar 不得包含 `com/moulberry/axiom/`，由 `paper:plugin` 的 `verifyNoAxiom` 檢查。
