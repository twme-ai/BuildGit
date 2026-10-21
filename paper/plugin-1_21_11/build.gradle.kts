import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins { id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" }

// Paper 1.21.11 版：與 26.2 共用 paper/plugin 的原始碼，只有版本相依的類別各自一份。
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

sourceSets.main {
    java.srcDirs("../plugin/src/main/java", "../../server/src/main/java", "src/main/java")
    resources.srcDirs("../plugin/src/main/resources", "../../server/src/main/resources")
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation(project(":core"))
    implementation(project(":api"))
    compileOnly(project(":paper:axiom-api"))
    compileOnly(project(":paper:debugstickpro-api"))
    compileOnly(libs.luckperms.api)
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:2.15.2") { isTransitive = false }
}

extra["minecraftVersion"] = "1.21.11"
apply(from = "../plugin-jar.gradle.kts")
