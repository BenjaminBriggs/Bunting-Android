// Included build — deliberately self-contained (own version pins, no shared
// catalog) so it can be consumed standalone via pluginManagement.includeBuild.
plugins {
    `java-gradle-plugin`
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
}

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

gradlePlugin {
    plugins {
        create("buntingCodegen") {
            id = "com.bunting.codegen"
            implementationClass = "com.bunting.codegen.BuntingCodegenPlugin"
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
