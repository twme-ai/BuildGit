plugins { id("fabric-loom") version "1.19.1" }

// Fabric 1.21.11 版：以 Mojang 名稱編譯，打包時 Loom 轉成執行期的 intermediary 名稱。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(21) }

repositories {
    maven("https://maven.nucleoid.xyz/") { name = "nucleoid" }
    maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
}

sourceSets.main {
    java.srcDirs("../common/src/main/java", "../../server/src/main/java")
    resources.srcDirs("../common/src/main/resources", "../../server/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:1.21.11")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.20.0")
    modImplementation("net.fabricmc.fabric-api:fabric-api:0.141.6+1.21.11")
    modImplementation("xyz.nucleoid:fantasy:0.7.0+1.21.11")
    include("xyz.nucleoid:fantasy:0.7.0+1.21.11")
    modImplementation("me.lucko:fabric-permissions-api:0.6.1")
    include("me.lucko:fabric-permissions-api:0.6.1")
    // WorldEdit 只在執行期選用（有裝才掛上記錄）
    modCompileOnly("com.sk89q.worldedit:worldedit-fabric-mc1.21.11:7.4.2") { isTransitive = false }
    compileOnly("com.sk89q.worldedit:worldedit-core:7.4.2") { isTransitive = false }
}

extra["minecraftVersion"] = "1.21.11"
apply(from = "../fabric-jar.gradle.kts")
