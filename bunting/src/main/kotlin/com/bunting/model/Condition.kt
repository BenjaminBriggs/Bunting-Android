package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single targeting rule tested against the current context. All conditions in a
 *  list must pass (logical AND); model OR as separate lower-`order` variants.
 *
 *  A condition the decoder could not understand (unknown type or operator, or
 *  `values` that aren't all strings) is kept as [UNRECOGNIZED], which never matches. */
data class Condition(
    val type: ConditionType,
    val values: List<String>,
    val operator: ConditionOperator,
) {
    companion object {
        /** The never-matching stand-in for a condition this SDK couldn't decode. */
        val UNRECOGNIZED: Condition =
            Condition(ConditionType.UNRECOGNIZED, emptyList(), ConditionOperator.UNRECOGNIZED)
    }
}

@Serializable
enum class ConditionType(val wireName: String) {
    @SerialName("os_version") OS_VERSION("os_version"),
    @SerialName("app_version") APP_VERSION("app_version"),
    @SerialName("build_number") BUILD_NUMBER("build_number"),
    @SerialName("platform") PLATFORM("platform"),
    @SerialName("device_model") DEVICE_MODEL("device_model"),
    @SerialName("device_class") DEVICE_CLASS("device_class"),
    @SerialName("region") REGION("region"),
    @SerialName("language") LANGUAGE("language"),
    @SerialName("custom_attribute") CUSTOM_ATTRIBUTE("custom_attribute"),

    /** Decoded from a condition this SDK couldn't understand. Never matches. */
    @SerialName("unrecognized") UNRECOGNIZED("unrecognized");

    companion object {
        /** The type for a wire name, or null when this SDK doesn't know it. Never
         *  returns [UNRECOGNIZED], which is not a wire value. */
        fun fromWire(raw: String): ConditionType? =
            entries.firstOrNull { it != UNRECOGNIZED && it.wireName == raw }
    }
}

@Serializable
enum class ConditionOperator(val wireName: String) {
    @SerialName("equals") EQUALS("equals"),
    @SerialName("does_not_equals") DOES_NOT_EQUALS("does_not_equals"),
    @SerialName("between") BETWEEN("between"),
    @SerialName("greater_than_or_equal") GREATER_THAN_OR_EQUAL("greater_than_or_equal"),
    @SerialName("greater_than") GREATER_THAN("greater_than"),
    @SerialName("less_than_or_equal") LESS_THAN_OR_EQUAL("less_than_or_equal"),
    @SerialName("less_than") LESS_THAN("less_than"),
    @SerialName("in") IN("in"),
    @SerialName("not_in") NOT_IN("not_in"),
    @SerialName("custom") CUSTOM("custom"),

    /** Decoded from a condition this SDK couldn't understand. Never matches. */
    @SerialName("unrecognized") UNRECOGNIZED("unrecognized");

    companion object {
        /** The operator for a wire name, or null when this SDK doesn't know it. Never
         *  returns [UNRECOGNIZED], which is not a wire value. */
        fun fromWire(raw: String): ConditionOperator? =
            entries.firstOrNull { it != UNRECOGNIZED && it.wireName == raw }
    }
}
