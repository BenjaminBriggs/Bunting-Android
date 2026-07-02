package com.bunting.model

import kotlinx.serialization.Serializable

/** A percentage-based gradual rollout. */
@Serializable
data class Rollout(
    val name: String,
    val description: String? = null,
    val type: String,
    val salt: String,
    val conditions: List<Condition> = emptyList(),
    val percentage: Int,
)
