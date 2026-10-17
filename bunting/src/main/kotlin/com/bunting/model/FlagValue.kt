package com.bunting.model

import java.time.Instant

/** A type-erased flag value. Mirrors Swift `FlagValue`; JSON flags are carried as
 *  their raw UTF-8 string.
 *
 *  Values have no type of their own on the wire: [ArtifactDecoder] decodes each one
 *  by its flag's declared [FlagType], never by its JSON shape, so a string-typed
 *  `"2026-01-01T00:00:00Z"` stays a [StringValue] and a double-typed `3` is a
 *  [DoubleValue]. */
sealed class FlagValue {
    data class BoolValue(val value: Boolean) : FlagValue()
    data class IntValue(val value: Long) : FlagValue()
    data class DoubleValue(val value: Double) : FlagValue()
    data class StringValue(val value: String) : FlagValue()
    data class DateValue(val value: Instant) : FlagValue()
    data class JsonValue(val value: String) : FlagValue()
}
