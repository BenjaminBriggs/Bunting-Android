package com.bunting.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Supported flag value types. Wire values are lowercase (`bool`, `int`, ...). A flag
 *  whose wire type is none of these is skipped at decode time, so there is no
 *  unknown case here. */
@Serializable
enum class FlagType(val wireName: String) {
    @SerialName("bool") BOOL("bool"),
    @SerialName("string") STRING("string"),
    @SerialName("int") INT("int"),
    @SerialName("double") DOUBLE("double"),
    @SerialName("date") DATE("date"),
    @SerialName("json") JSON("json");

    companion object {
        /** The type for a wire name, or null when this SDK doesn't know it. */
        fun fromWire(raw: String): FlagType? = entries.firstOrNull { it.wireName == raw }
    }
}
