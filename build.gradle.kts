plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
}

// `./gradlew build` also builds and tests the included codegen-plugin build.
tasks.register("build") {
    dependsOn(gradle.includedBuild("bunting-codegen-gradle").task(":build"))
}
