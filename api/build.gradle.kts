// 給其他插件使用的公開 API：只依賴 Bukkit 型別，以 Java 21 編譯，讓 1.21.x 與 26.x 的插件都能引用。
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
java { withSourcesJar() }
