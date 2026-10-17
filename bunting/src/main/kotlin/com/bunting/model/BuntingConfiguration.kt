package com.bunting.model

import kotlinx.serialization.Serializable

/** The root artifact: all flags, tests, and rollouts for one app, as this SDK decoded
 *  them. Decoding goes through [ArtifactDecoder] (also when using
 *  `BuntingConfiguration.serializer()`), so flags it had to skip and tests or
 *  rollouts it had to drop are absent here. */
@Serializable(with = BuntingConfigurationSerializer::class)
data class BuntingConfiguration(
    val schemaVersion: Int,
    val configVersion: String,
    val publishedAt: String,
    val appIdentifier: String,
    val flags: Map<String, Flag>,
    val tests: Map<String, Test> = emptyMap(),
    val rollouts: Map<String, Rollout> = emptyMap(),
) {
    init {
        require(schemaVersion == ArtifactDecoder.SUPPORTED_SCHEMA_VERSION) {
            "Unsupported schema_version $schemaVersion; this SDK only understands ${ArtifactDecoder.SUPPORTED_SCHEMA_VERSION}"
        }
    }
}
