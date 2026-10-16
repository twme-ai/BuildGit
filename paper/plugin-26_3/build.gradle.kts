import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Paper 26.3 版：與 26.2 共用 paper/plugin 的原始碼，對 26.3 的伺服器編譯。
java { toolchain.languageVersion.set(JavaLanguageVersion.of(25)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

sourceSets.main {
    java.srcDirs("../plugin/src/main/java", "../../server/src/main/java")
    resources.srcDirs("../plugin/src/main/resources", "../../server/src/main/resources")
}

dependencies {
    paperweight.paperDevBundle("26.3.build.197-stable")
    implementation(project(":core"))
    implementation(project(":api"))
    compileOnly(project(":paper:axiom-api"))
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.2") { isTransitive = false }
}

extra["minecraftVersion"] = "26.3"
apply(from = "../plugin-jar.gradle.kts")
