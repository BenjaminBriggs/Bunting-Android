package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The root artifact: all flags, tests, and rollouts for one app. */
@Serializable
data class BuntingConfiguration(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("config_version") val configVersion: String,
    @SerialName("published_at") val publishedAt: String,
    @SerialName("app_identifier") val appIdentifier: String,
    val flags: Map<String, Flag>,
    val tests: Map<String, Test> = emptyMap(),
    val rollouts: Map<String, Rollout> = emptyMap(),
) {
    init {
        require(schemaVersion == 1) {
            "Unsupported schema_version $schemaVersion; this SDK only understands 1"
        }
    }
}
