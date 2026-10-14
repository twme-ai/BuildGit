import io.papermc.paperweight.userdev.ReobfArtifactConfiguration
import java.util.zip.ZipFile

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Paper 26.2 插件本體。伺服器 class 為 Java 25，所以整個模組以 Java 25 編譯；1.21.11 之後另開轉接模組。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

dependencies {
    paperweight.paperDevBundle("26.2.build.129-stable")
    implementation(project(":core"))
    implementation(project(":api"))
    compileOnly(project(":paper:axiom-api"))
    // FAWE 的 Core 含 WorldEdit API；只用兩者共有的類別處理原版 WorldEdit，FAWE 專用類別放在獨立 class。
    // 世界以名稱對應回 Bukkit，不需要 worldedit-bukkit。
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.2") { isTransitive = false }
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    val zstd = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts
        .first { it.moduleVersion.id.name == "zstd-jni" }.moduleVersion.id.version
    inputs.property("zstd", zstd)
    filesMatching("plugin.yml") { expand("version" to version, "zstd" to zstd) }
}

tasks.jar {
    archiveBaseName.set("BuildGit")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        // 以 Mojang 名稱編譯；Paper 看到這個屬性就不會把插件當成 Spigot 名稱去 remap。
        attributes("paperweight-mappings-namespace" to "mojang")
        attributes("Implementation-Version" to project.version)
    }
    dependsOn(configurations.runtimeClasspath)
    // zstd-jni 帶各平台原生函式庫（數 MB）；改由 plugin.yml 的 libraries 讓 Paper 從 Maven Central 下載。
    from({
        configurations.runtimeClasspath.get()
            .filterNot { it.name.startsWith("zstd-jni") }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class", "META-INF/versions/**/module-info.class")
}

val verifyNoAxiom = tasks.register("verifyNoAxiom") {
    dependsOn(tasks.jar)
    val artifact = tasks.jar.flatMap { it.archiveFile }
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
