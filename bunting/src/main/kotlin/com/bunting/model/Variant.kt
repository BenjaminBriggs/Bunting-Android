package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A conditional override for a flag value. Variants evaluate in ascending `order`;
 *  the first that matches wins.
 *
 *  A variant the decoder could not understand (unknown type, or a missing or
 *  mistyped value or reference) is kept as a [VariantType.UNRECOGNIZED] placeholder
 *  carrying only its `order`: it never matches but still counts as one fingerprint
 *  path, so fingerprints stay aligned with the admin. */
data class Variant(
    val type: VariantType,
    val order: Int,
    val value: FlagValue? = null,
    val values: Map<String, FlagValue>? = null,
    val conditions: List<Condition>? = null,
    val test: String? = null,
    val rollout: String? = null,
) {
    companion object {
        /** The never-matching placeholder for a variant this SDK couldn't decode. */
        fun unrecognized(order: Int): Variant = Variant(type = VariantType.UNRECOGNIZED, order = order)
    }
}

@Serializable
enum class VariantType(val wireName: String) {
    @SerialName("conditional") CONDITIONAL("conditional"),
    @SerialName("test") TEST("test"),
    @SerialName("rollout") ROLLOUT("rollout"),

    /** A placeholder for a variant this SDK couldn't decode. Never matches. */
    @SerialName("unrecognized") UNRECOGNIZED("unrecognized"),
}
