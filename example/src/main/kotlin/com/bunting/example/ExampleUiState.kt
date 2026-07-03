package com.bunting.example

import com.bunting.BuntingState
import com.bunting.model.ConfigSource

/** Presenter for the config-status card, kept as a pure function for testability. */
data class ExampleUiState(
    val configVersion: String,
    val source: String,
    val signatureVerified: Boolean,
    val environment: String,
) {
    companion object {
        fun fromState(state: BuntingState): ExampleUiState = ExampleUiState(
            configVersion = state.configVersion ?: "not loaded",
            source = when (state.configSource) {
                ConfigSource.FETCHED -> "network"
                ConfigSource.CACHE -> "cache"
                ConfigSource.SEED -> "bundled seed"
                null -> "none"
            },
            signatureVerified = state.signatureVerified,
            environment = state.environment.name.lowercase(),
        )
    }
}
