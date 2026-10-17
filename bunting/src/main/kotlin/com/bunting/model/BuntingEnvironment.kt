package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BuntingEnvironment {
    @SerialName("development") DEVELOPMENT,
    @SerialName("beta") BETA,
    @SerialName("production") PRODUCTION;

    /** Index in a config fingerprint (0 development, 1 beta, 2 production). */
    val fingerprintIndex: Int
        get() = when (this) {
            DEVELOPMENT -> 0
            BETA -> 1
            PRODUCTION -> 2
        }

    /** The environment's key in a flag object (`development`, `beta`, `production`). */
    val wireName: String
        get() = when (this) {
            DEVELOPMENT -> "development"
            BETA -> "beta"
            PRODUCTION -> "production"
        }

    companion object {
        fun fromWire(raw: String): BuntingEnvironment? = when (raw) {
            "development" -> DEVELOPMENT
            "beta" -> BETA
            "production" -> PRODUCTION
            else -> null
        }
    }
}
