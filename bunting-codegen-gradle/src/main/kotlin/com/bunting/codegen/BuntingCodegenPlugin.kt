package com.bunting.codegen

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.api.AndroidBasePlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Registers a per-variant `generate<Variant>BuntingFlags` task and adds its output
 * to the variant's Kotlin sources via the AGP variant API. Requires an Android
 * application or library module (the generated code targets the `Bunting` facade).
 */
class BuntingCodegenPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create("buntingCodegen", BuntingCodegenExtension::class.java)
        extension.seedConfig.convention(project.layout.projectDirectory.file("src/main/assets/BuntingConfig.json"))
        extension.packageName.convention("com.bunting.generated")

        var applied = false
        project.plugins.withType(AndroidBasePlugin::class.java) {
            applied = true
            val components = project.extensions.getByType(AndroidComponentsExtension::class.java)
            components.onVariants(components.selector().all()) { variant ->
                val taskName = "generate${variant.name.replaceFirstChar { it.uppercase() }}BuntingFlags"
                val task = project.tasks.register(taskName, GenerateBuntingFlagsTask::class.java) { t ->
                    t.seedConfig.from(extension.seedConfig)
                    t.packageName.set(extension.packageName)
                }
                // Register with the java source set: AGP's Kotlin-Android compilation picks
                // up .kt files there, and (unlike sources.kotlin on this AGP line) the
                // generated-directory wiring reliably feeds compileDebugKotlin.
                val sources = variant.sources.java ?: variant.sources.kotlin
                sources?.addGeneratedSourceDirectory(task, GenerateBuntingFlagsTask::outputDir)
            }
        }
        project.afterEvaluate {
            if (applied == false) {
                project.logger.warn(
                    "com.bunting.codegen was applied to ${project.path} but no Android plugin is present; no accessors will be generated."
                )
            }
        }
    }
}
