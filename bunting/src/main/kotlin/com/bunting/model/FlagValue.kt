package com.bunting.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/** A type-erased flag value. Mirrors Swift `FlagValue`; JSON flags are carried as
 *  their raw UTF-8 string. */
@Serializable(with = FlagValueSerializer::class)
sealed class FlagValue {
    data class BoolValue(val value: Boolean) : FlagValue()
    data class IntValue(val value: Long) : FlagValue()
    data class DoubleValue(val value: Double) : FlagValue()
    data class StringValue(val value: String) : FlagValue()
    data class DateValue(val value: Instant) : FlagValue()
    data class JsonValue(val value: String) : FlagValue()
}

/**
 * Scalar decoder matching Swift's `FlagValue.init(from:)` precedence exactly:
 * Boolean → Integer(Long) → Double → (String: ISO-8601 date → `{`/`[` JSON → plain string).
 * A whole number always decodes as [FlagValue.IntValue]; the `double()` accessor
 * coerces it to a Double at read time.
 */
object FlagValueSerializer : KSerializer<FlagValue> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.bunting.model.FlagValue", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): FlagValue {
        val json = decoder as? JsonDecoder
            ?: throw SerializationException("FlagValue is only decodable from JSON")
        val primitive = json.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("FlagValue must be a JSON scalar")

        if (primitive.isString == false) {
            primitive.booleanOrNull?.let { return FlagValue.BoolValue(it) }
            primitive.longOrNull?.let { return FlagValue.IntValue(it) }
            primitive.doubleOrNull?.let { return FlagValue.DoubleValue(it) }
            throw SerializationException("Cannot decode flag value: ${primitive.content}")
        }

        val s = primitive.content
        parseIso8601(s)?.let { return FlagValue.DateValue(it) }
        val trimmed = s.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return FlagValue.JsonValue(s)
        return FlagValue.StringValue(s)
    }

    override fun serialize(encoder: Encoder, value: FlagValue) {
        val json = encoder as? JsonEncoder
            ?: throw SerializationException("FlagValue is only encodable to JSON")
        val element: JsonElement = when (value) {
            is FlagValue.BoolValue -> JsonPrimitive(value.value)
            is FlagValue.IntValue -> JsonPrimitive(value.value)
            is FlagValue.DoubleValue -> JsonPrimitive(value.value)
            is FlagValue.StringValue -> JsonPrimitive(value.value)
            is FlagValue.JsonValue -> JsonPrimitive(value.value)
            is FlagValue.DateValue -> JsonPrimitive(value.value.toString())
        }
        json.encodeJsonElement(element)
    }

    private fun parseIso8601(s: String): Instant? = try {
        // Accepts fractional seconds and trailing 'Z'; non-date strings like
        // "2.5.0" or "18.0" throw and fall through to JSON/string handling.
        Instant.parse(s)
    } catch (_: DateTimeParseException) {
        null
    }
}
