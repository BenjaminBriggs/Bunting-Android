package com.bunting.model

import kotlinx.serialization.Serializable

/** An A/B test that deterministically buckets users into named groups. */
@Serializable
data class Test(
    val name: String,
    val description: String? = null,
    val type: String,
    val salt: String,
    val conditions: List<Condition> = emptyList(),
    val groups: List<TestGroup>? = null,
) {
    /** The first group whose cumulative percentage covers [bucket] (1–100), or null. */
    fun assignGroup(bucket: Int): String? {
        val g = groups ?: return null
        if (g.isEmpty()) return null
        var cumulative = 0
        for (group in g) {
            cumulative += group.percentage
            if (bucket <= cumulative) return group.name
        }
        return null
    }
}

@Serializable
data class TestGroup(val name: String, val percentage: Int)
