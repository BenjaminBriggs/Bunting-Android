rootProject.name = "bunting-sdk-android"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":bunting-verify", ":bunting", ":bunting-cli")
