import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.bunting.debug"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

// The presenter tests are pure-JVM Jupiter; run once against debug like :bunting-android.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enableUnitTest = false
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // BuntingClient is part of the screens' public signatures.
    api(project(":bunting"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Coordinates, license, SCM and signing come from the root gradle.properties.
// Publishes the release variant. AGP's built-in javadoc generation (its bundled
// Dokka) fails on these modules, so the javadoc jar Maven Central requires is
// empty; the sources jar carries the KDoc.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))
    pom {
        name = "Bunting Debug UI"
        description = "Jetpack Compose screens for inspecting Bunting feature flags and editing local overrides."
    }
}

val emptyJavadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifact(emptyJavadocJar)
    }
}
