plugins { id("net.fabricmc.fabric-loom") version "1.19.1" }

// Fabric 26.2 版：Minecraft 26 起不再混淆，直接以 Mojang 名稱編譯、執行，不需要 remap。
// 與 1.21.11 版共用 fabric/common 與 server/ 的原始碼，只有版本相依的類別各自一份（dev.twme.buildgit.fabric.mc）。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }

repositories { maven("https://maven.nucleoid.xyz/") { name = "nucleoid" } }

sourceSets.main {
    java.srcDirs("../common/src/main/java", "../../server/src/main/java")
    resources.srcDirs("../common/src/main/resources", "../../server/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.20.0")
    implementation("net.fabricmc.fabric-api:fabric-api:0.161.0+26.2")
    implementation("xyz.nucleoid:fantasy:0.8.3+26.2")
    include("xyz.nucleoid:fantasy:0.8.3+26.2")
    implementation("me.lucko:fabric-permissions-api:0.7.0")
    include("me.lucko:fabric-permissions-api:0.7.0")
}

extra["minecraftVersion"] = "26.2"
apply(from = "../fabric-jar.gradle.kts")
