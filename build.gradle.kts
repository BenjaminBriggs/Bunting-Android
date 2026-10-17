import org.gradle.plugins.signing.SigningExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.vanniktech.maven.publish) apply false
}

// `./gradlew build` also builds and tests the included codegen-plugin build.
tasks.register("build") {
    dependsOn(gradle.includedBuild("bunting-codegen-gradle").task(":build"))
}

// signAllPublications=true makes signing mandatory for every release version, which
// would break publishToMavenLocal on machines without a GPG key. Only Maven Central
// needs signatures, so require them exactly when Central credentials are configured:
// a Central upload can never go out unsigned, and local publishing needs no keys.
subprojects {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
        extensions.configure<SigningExtension> {
            setRequired(providers.gradleProperty("mavenCentralUsername").map { true }.orElse(false))
        }
    }
}
