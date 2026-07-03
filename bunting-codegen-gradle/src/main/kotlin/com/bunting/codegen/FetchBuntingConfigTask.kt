package com.bunting.codegen

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fetches the latest signed config from the bootstrap's endpoint, verifies it,
 * and overwrites the seed config — the file codegen reads and the app bundles.
 * The Android counterpart of Swift's `FetchConfigPlugin`; run it from the Gradle
 * tool window (`bunting/fetchBuntingConfig`) or `./gradlew :app:fetchBuntingConfig`.
 * Never up-to-date (it exists to check for new config), and it fails rather than
 * writing anything on any fetch/verification problem.
 */
abstract class FetchBuntingConfigTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bootstrapConfig: RegularFileProperty

    @get:OutputFile
    abstract val seedConfig: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun fetch() {
        val bootstrapFile = bootstrapConfig.get().asFile
        val bytes = ConfigFetcher.fetch(bootstrapFile.readText())
        val target = seedConfig.get().asFile
        target.parentFile.mkdirs()
        target.writeBytes(bytes)
        logger.lifecycle("Bunting: wrote ${bytes.size} bytes of verified config to ${target.relativeToOrSelf(project.projectDir)}")
    }
}
