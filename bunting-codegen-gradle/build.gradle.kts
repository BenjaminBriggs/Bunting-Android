import java.util.Properties

// Included build — deliberately self-contained (own version pins, no shared
// catalog) so it can be consumed standalone via pluginManagement.includeBuild.
// The release version and license come from the SDK root gradle.properties so
// the plugin and the libraries always ship as one version.
plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("com.gradle.plugin-publish") version "2.0.0"
}

val sdkProperties = Properties().apply {
    rootDir.resolve("../gradle.properties").inputStream().use { load(it) }
}
fun sdkProperty(name: String): String = checkNotNull(sdkProperties.getProperty(name)) { "$name missing from ../gradle.properties" }

group = sdkProperty("GROUP")
version = sdkProperty("VERSION_NAME")

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("com.android.tools.build:gradle-api:8.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

// Published to the Gradle Plugin Portal with `publishPlugins` (see docs/releasing.md).
// The id sits under io.github.benjaminbriggs, the namespace the Portal ties to the
// BenjaminBriggs GitHub account.
gradlePlugin {
    website = sdkProperty("POM_URL")
    vcsUrl = sdkProperty("POM_SCM_URL")
    plugins {
        create("buntingCodegen") {
            id = "io.github.benjaminbriggs.bunting.codegen"
            implementationClass = "com.bunting.codegen.BuntingCodegenPlugin"
            displayName = "Bunting codegen"
            description = "Generates strongly-typed Kotlin accessors for Bunting feature flags from the bundled seed " +
                "config, and adds fetchBuntingConfig to download and verify the latest signed config."
            tags = listOf("feature-flags", "feature-toggles", "codegen", "android", "bunting")
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            if (this@configureEach.name == "pluginMaven") {
                name = "Bunting codegen Gradle plugin"
                description = "Implementation of the io.github.benjaminbriggs.bunting.codegen Gradle plugin."
            }
            url = sdkProperty("POM_URL")
            licenses {
                license {
                    name = sdkProperty("POM_LICENSE_NAME")
                    url = sdkProperty("POM_LICENSE_URL")
                }
            }
            developers {
                developer {
                    id = sdkProperty("POM_DEVELOPER_ID")
                    name = sdkProperty("POM_DEVELOPER_NAME")
                    url = sdkProperty("POM_DEVELOPER_URL")
                }
            }
            scm {
                url = sdkProperty("POM_SCM_URL")
                connection = sdkProperty("POM_SCM_CONNECTION")
                developerConnection = sdkProperty("POM_SCM_DEV_CONNECTION")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
