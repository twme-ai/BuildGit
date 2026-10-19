plugins { id("net.fabricmc.fabric-loom") version "1.19.1" }

// Fabric 26.3 版：與 26.2 共用版本相依的類別（fabric/mc26_2），不需要 remap。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

repositories {
    maven("https://maven.nucleoid.xyz/") { name = "nucleoid" }
    maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
    maven("https://api.modrinth.com/maven") { name = "modrinth" }
}

sourceSets.main {
    java.srcDirs("../common/src/main/java", "../../server/src/main/java", "../mc26_2/src/main/java")
    resources.srcDirs("../common/src/main/resources", "../../server/src/main/resources", "../mc26_2/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:26.3")
    implementation("net.fabricmc:fabric-loader:0.20.0")
    implementation("net.fabricmc.fabric-api:fabric-api:0.163.0+26.3")
    implementation("xyz.nucleoid:fantasy:0.8.4+26.3")
    include("xyz.nucleoid:fantasy:0.8.4+26.3")
    implementation("me.lucko:fabric-permissions-api:0.7.0")
    include("me.lucko:fabric-permissions-api:0.7.0")
    // WorldEdit 只在執行期選用（有裝才掛上記錄）
    compileOnly("com.sk89q.worldedit:worldedit-fabric-mc26.3:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core-mc-mc26.3:7.4.6") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.6") { isTransitive = false }
    // Axiom 6.1.3 for MC26.3（Modrinth 版本 id）：只在執行期選用，不打包
    compileOnly("maven.modrinth:axiom:CrfjL7cY") { isTransitive = false }
}

extra["minecraftVersion"] = "26.3"
apply(from = "../fabric-jar.gradle.kts")
