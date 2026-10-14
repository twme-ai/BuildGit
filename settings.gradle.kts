pluginManagement {
    repositories {
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
include("core", "api", "paper:axiom-api", "paper:plugin")
