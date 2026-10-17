plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.bunting.cli.MainKt")
}

// Not published to Maven: `distZip` builds build/distributions/bunting-cli-<version>.zip,
// which the release workflow attaches to the GitHub release.
version = providers.gradleProperty("VERSION_NAME").get()

dependencies {
    implementation(project(":bunting"))
    implementation(project(":bunting-verify"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
