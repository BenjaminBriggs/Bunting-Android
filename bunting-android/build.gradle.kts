import com.vanniktech.maven.publish.AndroidSingleVariantLibrary

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.maven.publish)
}

android {
    namespace = "com.bunting.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// The library code is variant-independent and the Robolectric fixtures live in
// src/debug/assets, so unit tests run once against the debug variant only.
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
    api(project(":bunting"))
    implementation(libs.androidx.lifecycle.process)
    // Used directly by this module (BuntingJson/BootstrapConfig.serializer, CoroutineScope);
    // :bunting also exports both through `api`.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    // Provides Dispatchers.Main so the configure(...) default scope = MainScope() works at runtime.
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.junit4)
}

// Coordinates, license, SCM and signing come from the root gradle.properties.
// Publishes the release variant. AGP's built-in javadoc generation (its bundled
// Dokka) fails on these modules, so the javadoc jar Maven Central requires is
// empty; the sources jar carries the KDoc.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = false))
    pom {
        name = "Bunting Android"
        description = "Android binding for the Bunting feature-flag SDK: configure from assets, device context, and foreground auto-refresh."
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
