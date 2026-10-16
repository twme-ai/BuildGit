pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/") { name = "papermc" }
        maven("https://maven.enginehub.org/repo/") { name = "enginehub" }
    }
}
rootProject.name = "buildgit"
include("core", "api", "paper:axiom-api", "paper:plugin", "paper:plugin-1_21_11", "paper:plugin-26_3", "fabric:mc26_2", "fabric:mc26_3", "fabric:mc1_21_11")
