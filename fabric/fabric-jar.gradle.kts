// 兩個 Fabric 版本共用：core 與 Paper 內建、Fabric 沒有的函式庫以 jar-in-jar 打包，fabric.mod.json 版本替換。
// 套用前需先設定 extra["minecraftVersion"]（檔名用）。
val minecraftVersion = extra["minecraftVersion"] as String
val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bundled = listOf("adventure-api", "adventure-minimessage", "adventure-gson", "snakeyaml", "zstd")
        .map { catalog.findLibrary(it).get().get() }
    for (dep in bundled) {
        "implementation"(dep)
        "include"(dep)
    }
    // jar-in-jar 不會帶上間接相依，Adventure 需要的另外列出
    val adventure = catalog.findVersion("adventure").get().requiredVersion
    for (dep in listOf("net.kyori:adventure-key:$adventure", "net.kyori:adventure-text-serializer-json:$adventure",
            "net.kyori:adventure-text-serializer-commons:$adventure", "net.kyori:examination-api:1.3.0",
            "net.kyori:examination-string:1.3.0", "net.kyori:option:1.1.0"))
        "include"(dep)
    "implementation"(project(":core"))
    "include"(project(":core"))
}

tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    inputs.property("minecraft", minecraftVersion)
    filesMatching("fabric.mod.json") { expand("version" to version, "minecraft" to minecraftVersion) }
}

// 1.21.11 版的成品是 remapJar；26.2 版不需要 remap，成品就是 jar。
val product = if (tasks.names.contains("remapJar")) "remapJar" else "jar"
tasks.withType<AbstractArchiveTask>().configureEach {
    if (name == "jar" || name == "remapJar") {
        archiveBaseName.set("BuildGit")
        archiveClassifier.set(if (name == product) "fabric-$minecraftVersion" else "dev")
    }
}
