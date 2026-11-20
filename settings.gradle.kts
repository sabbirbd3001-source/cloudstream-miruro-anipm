pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloudstream-miruro-anipm"

include(":MiruroProvider")
include(":AniPmProvider")
