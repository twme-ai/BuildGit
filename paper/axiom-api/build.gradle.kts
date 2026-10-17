// AxiomPaper 的編譯期簽章（MIT，見 LICENSE）。只供 paper:plugin compileOnly，不得打包進插件。
// 操作佇列的簽章帶有伺服器類別（ServerLevel、ServerPlayer），所以以 1.21.11 的開發套件編譯；類別名稱各版相同。
plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
}
