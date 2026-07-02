package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported flag value types. Wire values are lowercase (`bool`, `int`, ...). */
@Serializable
enum class FlagType {
    @SerialName("bool") BOOL,
    @SerialName("string") STRING,
    @SerialName("int") INT,
    @SerialName("double") DOUBLE,
    @SerialName("date") DATE,
    @SerialName("json") JSON,
}
