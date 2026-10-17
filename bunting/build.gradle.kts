import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.vanniktech.maven.publish)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}

dependencies {
    api(project(":bunting-verify"))
    // Both appear in the public API (StateFlow/CoroutineScope on the facade and
    // options, Json/serializers on the models), so published consumers need them
    // on their compile classpath.
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }

// Coordinates, license, SCM and signing come from the root gradle.properties.
// Dokka is not set up, so the javadoc jar Maven Central requires is empty; the
// sources jar carries the KDoc.
mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    pom {
        name = "Bunting"
        description = "Pure-JVM core of the Bunting feature-flag SDK: config models, local flag evaluation, signed config fetching and caching, identity, and overrides."
    }
}
