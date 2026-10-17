package com.bunting.codegen

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * The v1 wire-contract value rules, mirroring the admin's reference decoder
 * (`bunting-admin/src/lib/artifact-decoder.ts`, `isValidWireValue`). The plugin
 * build cannot depend on `:bunting`, so codegen and [SeedValidator] share this copy.
 */
internal object WireContract {

    /** JSON number grammar (RFC 8259). kotlinx keeps unquoted tokens as raw text, so this is checked here. */
    private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    /** An ISO-8601 UTC instant with at most millisecond precision. Offsets other than `Z` are invalid. */
    private val ISO_INSTANT = Regex("([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})(\\.([0-9]{1,3}))?Z")

    /** `Number.MAX_SAFE_INTEGER`: 2^53 - 1. */
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0

    /** The value of a JSON number token as JavaScript's `JSON.parse` reads it, or null for anything else. */
    fun number(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString || JSON_NUMBER.matches(primitive.content) == false) return null
        return primitive.content.toDouble()
    }

    /** int: a JSON number with no fractional part (`2.0` and `1e3` count) within ±(2^53 - 1). */
    fun int(element: JsonElement?): Long? {
        val value = number(element) ?: return null
        if (value.isFinite() == false || value != Math.floor(value) || Math.abs(value) > MAX_SAFE_INTEGER) return null
        return value.toLong()
    }

    /** double: any finite JSON number. */
    fun double(element: JsonElement?): Double? = number(element)?.takeIf { it.isFinite() }

    /** bool: a JSON boolean. */
    fun bool(element: JsonElement?): Boolean? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** string and json: any JSON string, verbatim. */
    fun string(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /** date: a string holding an ISO-8601 UTC instant that names a real calendar moment. */
    fun instant(element: JsonElement?): Instant? = string(element)?.let(::parseInstant)

    /**
     * [raw] as an instant when it matches the pattern and names a real calendar
     * moment: year 0001-9999, month 1-12, day within the month (leap years counted), hour 0-23,
     * minute and second 0-59. Nothing rolls over: `02-30`, `24:00:00` and second
     * 60 are invalid, like more than three fraction digits or any offset.
     */
    fun parseInstant(raw: String): Instant? {
        val match = ISO_INSTANT.matchEntire(raw) ?: return null
        val fields = match.groupValues.subList(1, 7).map { it.toInt() }
        val year = fields[0]
        val month = fields[1]
        val day = fields[2]
        val hour = fields[3]
        val minute = fields[4]
        val second = fields[5]
        // YearMonth counts Gregorian leap years: divisible by 4 and not by 100, or by 400.
        if (year < 1 || month !in 1..12 || day !in 1..YearMonth.of(year, month).lengthOfMonth()) return null
        if (hour > 23 || minute > 59 || second > 59) return null
        val millis = match.groups[8]?.value.orEmpty().padEnd(3, '0').toLong()
        return LocalDateTime.of(year, month, day, hour, minute, second).toInstant(ZoneOffset.UTC).plusMillis(millis)
    }

    /**
     * True when every unquoted token is `true`, `false`, `null` or a valid number.
     * kotlinx's tree parser accepts any bare word there; `JSON.parse` does not.
     */
    fun hasOnlyValidLiterals(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all(::hasOnlyValidLiterals)
        is JsonArray -> element.all(::hasOnlyValidLiterals)
        is JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" ||
            JSON_NUMBER.matches(element.content)
    }
}
