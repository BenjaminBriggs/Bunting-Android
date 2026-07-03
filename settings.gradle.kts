pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "bunting-sdk-android"

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

include(":bunting-verify", ":bunting", ":bunting-cli", ":bunting-android")
