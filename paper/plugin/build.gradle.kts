import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Paper 26.2 版，也是兩個 Paper 版本共用原始碼所在的地方（1.21.11 版見 paper/plugin-1_21_11）。
// 伺服器 class 為 Java 25，所以這個模組以 Java 25 編譯；原始碼本身維持 Java 21 語法，1.21.11 版以 Java 21 編譯同一份。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

// 與 Fabric 版共用的伺服器邏輯（以原版類別撰寫）。
sourceSets.main {
    java.srcDir("../../server/src/main/java")
    resources.srcDir("../../server/src/main/resources")
}

dependencies {
    paperweight.paperDevBundle("26.2.build.129-stable")
    implementation(project(":core"))
    implementation(project(":api"))
    compileOnly(project(":paper:axiom-api"))
    // FAWE 的 Core 含 WorldEdit API；只用兩者共有的類別處理原版 WorldEdit，FAWE 專用類別放在獨立 class。
    // 世界以名稱對應回 Bukkit，不需要 worldedit-bukkit。
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.2") { isTransitive = false }
}

extra["minecraftVersion"] = "26.2"
apply(from = "../plugin-jar.gradle.kts")
