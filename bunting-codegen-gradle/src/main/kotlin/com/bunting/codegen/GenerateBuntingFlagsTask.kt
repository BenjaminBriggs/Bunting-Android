package com.bunting.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Generates `BuntingGenerated.kt` from the seed config. Declared as a file
 * collection (not `@InputFile`) so a missing seed is a valid, tracked input
 * state — the task then emits the fallback file instead of failing the build.
 */
@CacheableTask
abstract class GenerateBuntingFlagsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val seedConfig: ConfigurableFileCollection

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val seedFile = seedConfig.files.firstOrNull { it.isFile }
        val seedJson = try {
            seedFile?.readText()
        } catch (e: Exception) {
            logger.warn("Bunting codegen: could not read seed config ${seedFile}: ${e.message}")
            null
        }
        if (seedJson == null) {
            logger.warn("Bunting codegen: no readable seed config; generating fallback (string-based access only).")
        }

        val pkg = packageName.get()
        val content = BuntingCodeGenerator.generate(seedJson, pkg)
        val target = outputDir.get().asFile.resolve(pkg.replace('.', '/')).resolve("BuntingGenerated.kt")
        target.parentFile.mkdirs()
        target.writeText(content)
    }
}
