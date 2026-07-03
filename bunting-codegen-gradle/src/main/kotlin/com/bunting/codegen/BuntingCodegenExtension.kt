package com.bunting.codegen

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property

/**
 * ```
 * buntingCodegen {
 *     seedConfig = layout.projectDirectory.file("src/main/assets/BuntingConfig.json")
 *     packageName = "com.example.app.bunting"
 * }
 * ```
 */
abstract class BuntingCodegenExtension {
    /** The seed config artifact. Default: `src/main/assets/BuntingConfig.json`. */
    abstract val seedConfig: RegularFileProperty

    /** Package of the generated file. Default: `com.bunting.generated`. */
    abstract val packageName: Property<String>
}
