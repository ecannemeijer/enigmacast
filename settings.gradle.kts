pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // jlibtorrent 2.x (torrent engine) is only published in FrostWire's own repository
        // (Maven Central stops at 1.2.0.18 from 2018). Limited to that one group.
        maven("https://dl.frostwire.com/maven") {
            content { includeGroup("com.frostwire") }
        }
    }
}

rootProject.name = "EnigmaCast"
include(":app")
