import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "papermc"
            url = uri("https://repo.papermc.io/repository/maven-public/")
        }
        mavenCentral()
    }
}
includeBuild("/home/jlo/dev/omnia/.agents/skills/development-network/network")

rootProject.name = "vanish-nopacket"
include("vanish-common", "vanish-paper", "vanish-velocity")
