package com.bunting.model

/** A percentage-based gradual rollout. */
data class Rollout(
    val name: String,
    val description: String? = null,
    val type: String,
    val salt: String,
    val conditions: List<Condition> = emptyList(),
    val percentage: Int,
)
