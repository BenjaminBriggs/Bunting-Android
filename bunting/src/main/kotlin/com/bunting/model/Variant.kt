package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A conditional override for a flag value. Variants evaluate in ascending `order`;
 *  the first whose conditions match wins. */
@Serializable
data class Variant(
    val type: VariantType,
    val order: Int,
    val value: FlagValue? = null,
    val values: Map<String, FlagValue>? = null,
    val conditions: List<Condition>? = null,
    val test: String? = null,
    val rollout: String? = null,
)

@Serializable
enum class VariantType {
    @SerialName("conditional") CONDITIONAL,
    @SerialName("test") TEST,
    @SerialName("rollout") ROLLOUT,
}
