// 兩個 Paper 版本共用的打包設定：plugin.yml 版本替換、把 core／api 打包進插件 jar、檢查沒有打包選用插件的 API。
// 套用前需先設定 extra["minecraftVersion"]（檔名用）。
import java.util.zip.ZipFile

val minecraftVersion = extra["minecraftVersion"] as String
val runtimeClasspath = configurations.named("runtimeClasspath")

tasks.named<ProcessResources>("processResources") {
    val version = project.version.toString()
    inputs.property("version", version)
    val zstd = runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .first { it.moduleVersion.id.name == "zstd-jni" }.moduleVersion.id.version
    inputs.property("zstd", zstd)
    inputs.property("minecraft", minecraftVersion)
    filesMatching("plugin.yml") { expand("version" to version, "zstd" to zstd, "minecraft" to minecraftVersion) }
}

val jar = tasks.named<Jar>("jar") {
    archiveBaseName.set("BuildGit")
    archiveClassifier.set("paper-$minecraftVersion")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // 以 Mojang 名稱編譯；Paper 看到這個屬性就不會把插件當成 Spigot 名稱去 remap。
        attributes("paperweight-mappings-namespace" to "mojang")
        attributes("Implementation-Version" to project.version)
    }
    dependsOn(runtimeClasspath)
    // zstd-jni 帶各平台原生函式庫（數 MB）；改由 plugin.yml 的 libraries 讓 Paper 從 Maven Central 下載。
    from({
        runtimeClasspath.get()
            .filterNot { it.name.startsWith("zstd-jni") }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
}

val verifyNoAxiom = tasks.register("verifyNoAxiom") {
    dependsOn(jar)
    val artifact = jar.flatMap { it.archiveFile }
    inputs.file(artifact)
    doLast {
        ZipFile(artifact.get().asFile).use { zip ->
            check(zip.entries().asSequence().none { it.name.startsWith("com/moulberry/") || it.name.startsWith("com/sk89q/") || it.name.startsWith("com/fastasyncworldedit/") || it.name.startsWith("com/github/luben/") }) {
                "選用插件的 API 不得打包進 BuildGit"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyNoAxiom) }
