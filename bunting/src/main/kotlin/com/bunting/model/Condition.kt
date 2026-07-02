package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A single targeting rule tested against the current context. All conditions in a
 *  list must pass (logical AND); model OR as separate lower-`order` variants. */
@Serializable
data class Condition(
    val type: ConditionType,
    val values: List<String>,
    val operator: ConditionOperator,
)

@Serializable
enum class ConditionType {
    @SerialName("os_version") OS_VERSION,
    @SerialName("app_version") APP_VERSION,
    @SerialName("build_number") BUILD_NUMBER,
    @SerialName("platform") PLATFORM,
    @SerialName("device_model") DEVICE_MODEL,
    @SerialName("device_class") DEVICE_CLASS,
    @SerialName("region") REGION,
    @SerialName("language") LANGUAGE,
    @SerialName("custom_attribute") CUSTOM_ATTRIBUTE,
}

@Serializable
enum class ConditionOperator {
    @SerialName("equals") EQUALS,
    @SerialName("does_not_equals") DOES_NOT_EQUALS,
    @SerialName("between") BETWEEN,
    @SerialName("greater_than_or_equal") GREATER_THAN_OR_EQUAL,
    @SerialName("greater_than") GREATER_THAN,
    @SerialName("less_than_or_equal") LESS_THAN_OR_EQUAL,
    @SerialName("less_than") LESS_THAN,
    @SerialName("in") IN,
    @SerialName("not_in") NOT_IN,
    @SerialName("custom") CUSTOM,
}
