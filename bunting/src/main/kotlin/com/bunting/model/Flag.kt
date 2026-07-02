package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A feature flag with per-environment configuration. */
@Serializable
data class Flag(
    val type: FlagType,
    val description: String? = null,
    val development: EnvironmentConfig,
    val beta: EnvironmentConfig,
    val production: EnvironmentConfig,
    val deprecated: Boolean = false,
) {
    fun config(environment: BuntingEnvironment): EnvironmentConfig = when (environment) {
        BuntingEnvironment.DEVELOPMENT -> development
        BuntingEnvironment.BETA -> beta
        BuntingEnvironment.PRODUCTION -> production
    }
}

/** Environment-specific flag configuration: an ordered variant list plus a default. */
@Serializable
data class EnvironmentConfig(
    @SerialName("default") val defaultValue: FlagValue,
    val variants: List<Variant> = emptyList(),
)
