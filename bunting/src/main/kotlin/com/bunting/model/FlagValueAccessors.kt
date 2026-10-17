package com.bunting.model

import java.time.Instant

/**
 * Typed read accessors over [FlagValue], mirroring the Swift `FlagValue`
 * extension. Every accessor returns null on a type mismatch except [doubleValue],
 * which also reads a [FlagValue.IntValue] as a Double. Decoding already turns every
 * value of a double-typed flag into a [FlagValue.DoubleValue] (a wire `2` included),
 * so the coercion only matters for an int-typed flag read through `double()`.
 */

/** The [FlagType] this value carries. */
val FlagValue.flagType: FlagType
    get() = when (this) {
        is FlagValue.BoolValue -> FlagType.BOOL
        is FlagValue.StringValue -> FlagType.STRING
        is FlagValue.IntValue -> FlagType.INT
        is FlagValue.DoubleValue -> FlagType.DOUBLE
        is FlagValue.DateValue -> FlagType.DATE
        is FlagValue.JsonValue -> FlagType.JSON
    }

/** The Boolean payload, or null if this is not a [FlagValue.BoolValue]. */
val FlagValue.boolValue: Boolean?
    get() = (this as? FlagValue.BoolValue)?.value

/** The Long payload, or null if this is not a [FlagValue.IntValue]. */
val FlagValue.intValue: Long?
    get() = (this as? FlagValue.IntValue)?.value

/** The Double payload; a [FlagValue.IntValue] is coerced to Double, everything else null. */
val FlagValue.doubleValue: Double?
    get() = when (this) {
        is FlagValue.DoubleValue -> value
        is FlagValue.IntValue -> value.toDouble()
        else -> null
    }

/** The String payload, or null if this is not a [FlagValue.StringValue]. */
val FlagValue.stringValue: String?
    get() = (this as? FlagValue.StringValue)?.value

/** The Instant payload, or null if this is not a [FlagValue.DateValue]. */
val FlagValue.dateValue: Instant?
    get() = (this as? FlagValue.DateValue)?.value

/** The raw JSON as UTF-8 bytes, or null if this is not a [FlagValue.JsonValue]. */
val FlagValue.jsonBytes: ByteArray?
    get() = (this as? FlagValue.JsonValue)?.value?.toByteArray(Charsets.UTF_8)
