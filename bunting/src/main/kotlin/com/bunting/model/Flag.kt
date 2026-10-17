package com.bunting.model

/** A feature flag with per-environment configuration. Every value in it (defaults
 *  and variant values) carries the flag's declared [type]; see [ArtifactDecoder]. */
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

/** Environment-specific flag configuration: a variant list (in wire order; evaluation
 *  sorts by `order`) plus a default. */
data class EnvironmentConfig(
    val defaultValue: FlagValue,
    val variants: List<Variant> = emptyList(),
)
