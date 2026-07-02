package com.bunting.evaluation

/** Runtime context for flag evaluation. Set once at startup; drives condition
 *  evaluation and the memoization key. */
data class EvaluationContext(
    val platform: String,
    val osVersion: String,
    val appVersion: String,
    val buildNumber: String,
    val deviceModel: String,
    val region: String?,
    val language: String,
    val deviceClass: String? = null,
    val reservedAttributes: Map<String, String> = emptyMap(),
) {
    /** Stable within-process hash folding every field that affects evaluation. */
    fun computeHash(): Int = listOf(
        platform, osVersion, appVersion, buildNumber, deviceModel,
        deviceClass, reservedAttributes, region, language,
    ).hashCode()

    companion object {
        /** The `custom_attribute` names the SDK reserves and resolves internally. */
        val reservedAttributeNames: Set<String> = setOf("manufacturer")
    }
}

/** Resolves whether a named app-defined attribute is active for the current user. */
typealias CustomAttributeResolver = (String) -> Boolean
