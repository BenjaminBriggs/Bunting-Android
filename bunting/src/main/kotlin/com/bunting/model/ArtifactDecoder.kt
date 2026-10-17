package com.bunting.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The artifact was rejected as a whole. Only the header is strict: invalid JSON, a
 *  `schema_version` other than 1, a missing `config_version` or `app_identifier`, a
 *  `published_at` that isn't an ISO-8601 UTC instant, or a `flags`/`tests`/`rollouts`
 *  container that isn't an object. Callers keep their cached or bundled config. */
class ArtifactRejectedException(val reason: String) : SerializationException("Config artifact rejected: $reason")

/** One entry [ArtifactDecoder] could not use as published. [path] locates it in the
 *  artifact (for example `flags["store/tier"].production.variants[2]`). */
data class DecodeIssue(val kind: Kind, val path: String, val reason: String) {
    enum class Kind {
        /** The flag was left out; its accessors return the call-site default. */
        SKIPPED_FLAG,

        /** The variant was kept as a never-matching placeholder (one fingerprint path). */
        UNRECOGNIZED_VARIANT,

        /** The variant had no integer `order` and was left out. */
        DROPPED_VARIANT,

        /** The condition was kept as [Condition.UNRECOGNIZED] and never matches. */
        UNRECOGNIZED_CONDITION,

        /** The test was left out; variants referencing it never match. */
        DROPPED_TEST,

        /** The rollout was left out; variants referencing it never match. */
        DROPPED_ROLLOUT,
    }

    override fun toString(): String = "${kind.name.lowercase()} $path: $reason"
}

/** A decoded artifact plus everything decoding had to skip, drop, or neutralise. */
data class DecodeResult(
    val configuration: BuntingConfiguration,
    val issues: List<DecodeIssue>,
    /** Keys of the flags left out of [configuration], in artifact order. */
    val skippedFlagKeys: List<String>,
) {
    /** True when what the admin published is exactly what this SDK uses. */
    val isLossless: Boolean get() = issues.isEmpty()
}

/**
 * Decodes `config.json` by the v1 wire contract (the admin's
 * `src/lib/artifact-decoder.ts` is the reference; the conformance bundle's
 * `decoding` set pins it):
 *
 * - One bad entry never rejects the artifact. A flag with an unknown type, a
 *   missing environment, a default that doesn't match its type, or `variants` that
 *   isn't an array is skipped. A variant with an unknown type or a missing or
 *   mistyped value or reference becomes a placeholder that never matches; one
 *   without an integer `order` is dropped. A condition with an unknown type or
 *   operator, or non-string `values`, never matches. A malformed test or rollout is
 *   dropped, so variants referencing it never match. Unknown keys are ignored.
 * - Only the header is strict (see [ArtifactRejectedException]).
 * - Values decode by the flag's declared type, never by their JSON shape.
 */
object ArtifactDecoder {
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    /** Decodes raw `config.json` text, or throws [ArtifactRejectedException]. */
    fun decode(text: String): DecodeResult {
        val root = try {
            BuntingJson.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) {
            // Covers SerializationException, which kotlinx throws for malformed JSON.
            throw ArtifactRejectedException("not valid JSON")
        }
        return decode(root)
    }

    /** Decodes an already-parsed artifact, or throws [ArtifactRejectedException]. */
    internal fun decode(root: JsonElement): DecodeResult {
        if (isStrictJson(root) == false) throw ArtifactRejectedException("not valid JSON")
        val obj = root as? JsonObject ?: throw ArtifactRejectedException("top level is not an object")

        val schemaVersion = obj["schema_version"]
        if (numberOrNull(schemaVersion) != SUPPORTED_SCHEMA_VERSION.toDouble()) {
            throw ArtifactRejectedException("unsupported schema_version ${describe(schemaVersion)}")
        }
        val configVersion = stringOrNull(obj["config_version"])?.takeIf { it.isNotEmpty() }
            ?: throw ArtifactRejectedException("missing config_version")
        val publishedAt = stringOrNull(obj["published_at"])?.takeIf { WireDate.parse(it) != null }
            ?: throw ArtifactRejectedException("published_at is not an ISO-8601 UTC instant")
        val appIdentifier = stringOrNull(obj["app_identifier"])
            ?: throw ArtifactRejectedException("missing app_identifier")
        val rawFlags = obj["flags"] as? JsonObject
            ?: throw ArtifactRejectedException("flags must be an object")
        val rawTests = optionalContainer(obj, "tests")
        val rawRollouts = optionalContainer(obj, "rollouts")

        val session = Session()
        val tests = LinkedHashMap<String, Test>()
        for ((name, raw) in rawTests) session.test(name, raw)?.let { tests[name] = it }
        val rollouts = LinkedHashMap<String, Rollout>()
        for ((name, raw) in rawRollouts) session.rollout(name, raw)?.let { rollouts[name] = it }
        val flags = LinkedHashMap<String, Flag>()
        for ((key, raw) in rawFlags) session.flag(key, raw)?.let { flags[key] = it }

        val configuration = BuntingConfiguration(
            schemaVersion = SUPPORTED_SCHEMA_VERSION,
            configVersion = configVersion,
            publishedAt = publishedAt,
            appIdentifier = appIdentifier,
            flags = flags,
            tests = tests,
            rollouts = rollouts,
        )
        return DecodeResult(configuration, session.issues.toList(), session.skippedFlagKeys.toList())
    }

    /**
     * C2: decodes a wire value by the declared [type], or returns null when the value
     * isn't valid for it.
     *
     * - bool: a JSON boolean
     * - int: a JSON number with no fractional part (`2.0` is the int 2), within
     *   ±(2^53 - 1) like the reference
     * - double: any finite JSON number (`3` is the double 3.0)
     * - string: any JSON string, verbatim
     * - date: a string holding an ISO-8601 UTC instant (`...Z`) on a real calendar
     *   date in years 0001-9999, with 0 to 3 fraction digits (see [WireDate])
     * - json: a string holding JSON text, verbatim (never a raw object or array)
     */
    internal fun decodeValue(type: FlagType, element: JsonElement?): FlagValue? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return when (type) {
            FlagType.BOOL -> if (primitive.isString) null else when (primitive.content) {
                "true" -> FlagValue.BoolValue(true)
                "false" -> FlagValue.BoolValue(false)
                else -> null
            }
            FlagType.INT -> numberOrNull(primitive)?.takeIf { isSafeInteger(it) }?.let { FlagValue.IntValue(it.toLong()) }
            FlagType.DOUBLE -> numberOrNull(primitive)?.takeIf { it.isFinite() }?.let { FlagValue.DoubleValue(it) }
            FlagType.STRING -> if (primitive.isString) FlagValue.StringValue(primitive.content) else null
            FlagType.DATE -> if (primitive.isString) WireDate.parse(primitive.content)?.let { FlagValue.DateValue(it) } else null
            FlagType.JSON -> if (primitive.isString) FlagValue.JsonValue(primitive.content) else null
        }
    }

    // MARK: per-entry decoding

    /** Collects issues for one decode. A flag decodes into a scratch session first, so
     *  a skipped flag reports only the skip, not the issues inside it. */
    private class Session {
        val issues = ArrayList<DecodeIssue>()
        val skippedFlagKeys = ArrayList<String>()

        fun flag(key: String, raw: JsonElement): Flag? {
            val path = "flags[\"$key\"]"
            fun skip(reason: String): Flag? {
                issues += DecodeIssue(DecodeIssue.Kind.SKIPPED_FLAG, path, reason)
                skippedFlagKeys += key
                return null
            }
            val obj = raw as? JsonObject ?: return skip("not an object")
            val rawType = obj["type"]
            val type = stringOrNull(rawType)?.let { FlagType.fromWire(it) }
                ?: return skip("unknown flag type \"${describeAsString(rawType)}\"")

            val scratch = Session()
            val environments = HashMap<BuntingEnvironment, EnvironmentConfig>()
            for (environment in BuntingEnvironment.entries) {
                val envPath = "$path.${environment.wireName}"
                val decoded = scratch.environment(obj[environment.wireName], type, envPath)
                when (decoded) {
                    is EnvironmentOutcome.Skip -> return skip(decoded.reason)
                    is EnvironmentOutcome.Decoded -> environments[environment] = decoded.config
                }
            }
            issues += scratch.issues

            return Flag(
                type = type,
                description = stringOrNull(obj["description"]),
                development = environments.getValue(BuntingEnvironment.DEVELOPMENT),
                beta = environments.getValue(BuntingEnvironment.BETA),
                production = environments.getValue(BuntingEnvironment.PRODUCTION),
                deprecated = (obj["deprecated"] as? JsonPrimitive)?.let { it.isString == false && it.content == "true" } ?: false,
            )
        }

        private sealed class EnvironmentOutcome {
            class Decoded(val config: EnvironmentConfig) : EnvironmentOutcome()
            class Skip(val reason: String) : EnvironmentOutcome()
        }

        private fun environment(raw: JsonElement?, type: FlagType, path: String): EnvironmentOutcome {
            val obj = raw as? JsonObject ?: return EnvironmentOutcome.Skip("$path is missing or not an object")
            val default = decodeValue(type, obj["default"])
                ?: return EnvironmentOutcome.Skip("$path.default is not a valid ${type.wireName}")
            val rawVariants = obj["variants"]
            val entries = when (rawVariants) {
                null, JsonNull -> emptyList()
                is JsonArray -> rawVariants
                else -> return EnvironmentOutcome.Skip("$path.variants must be an array")
            }
            val variants = entries.mapIndexedNotNull { index, entry -> variant(entry, type, "$path.variants[$index]") }
            return EnvironmentOutcome.Decoded(EnvironmentConfig(default, variants))
        }

        private fun variant(raw: JsonElement, type: FlagType, path: String): Variant? {
            val obj = raw as? JsonObject
            val order = obj?.get("order")?.let { numberOrNull(it) }?.takeIf { isSafeInteger(it) }
            // Orders past Int range can't be represented here; the admin never emits them.
            if (obj == null || order == null || order < Int.MIN_VALUE || order > Int.MAX_VALUE) {
                issues += DecodeIssue(DecodeIssue.Kind.DROPPED_VARIANT, path, "missing integer order")
                return null
            }
            val intOrder = order.toInt()
            fun placeholder(reason: String): Variant {
                issues += DecodeIssue(DecodeIssue.Kind.UNRECOGNIZED_VARIANT, path, reason)
                return Variant.unrecognized(intOrder)
            }

            val rawType = obj["type"]
            return when (stringOrNull(rawType)) {
                VariantType.CONDITIONAL.wireName -> {
                    val value = decodeValue(type, obj["value"])
                        ?: return placeholder("value is not a valid ${type.wireName}")
                    val rawConditions = obj["conditions"]
                    // Absent conditions match vacuously; keep them absent (null).
                    val conditions = if (rawConditions == null || rawConditions == JsonNull) {
                        null
                    } else {
                        conditions(rawConditions, path) ?: return placeholder("conditions must be an array")
                    }
                    Variant(type = VariantType.CONDITIONAL, order = intOrder, value = value, conditions = conditions)
                }
                VariantType.TEST.wireName -> {
                    val test = stringOrNull(obj["test"]) ?: return placeholder("missing test reference")
                    val rawValues = obj["values"] as? JsonObject ?: return placeholder("values must be an object")
                    val values = LinkedHashMap<String, FlagValue>()
                    for ((group, rawValue) in rawValues) {
                        values[group] = decodeValue(type, rawValue)
                            ?: return placeholder("group \"$group\" value is not a valid ${type.wireName}")
                    }
                    Variant(type = VariantType.TEST, order = intOrder, values = values, test = test)
                }
                VariantType.ROLLOUT.wireName -> {
                    val rollout = stringOrNull(obj["rollout"]) ?: return placeholder("missing rollout reference")
                    val value = decodeValue(type, obj["value"])
                        ?: return placeholder("value is not a valid ${type.wireName}")
                    Variant(type = VariantType.ROLLOUT, order = intOrder, value = value, rollout = rollout)
                }
                else -> placeholder("unknown variant type \"${describeAsString(rawType)}\"")
            }
        }

        /** Absent or null is an empty list; anything but an array is malformed (null). */
        fun conditions(raw: JsonElement?, path: String): List<Condition>? = when (raw) {
            null, JsonNull -> emptyList()
            is JsonArray -> raw.mapIndexed { index, entry -> condition(entry, "$path.conditions[$index]") }
            else -> null
        }

        private fun condition(raw: JsonElement, path: String): Condition {
            fun fail(reason: String): Condition {
                issues += DecodeIssue(DecodeIssue.Kind.UNRECOGNIZED_CONDITION, path, reason)
                return Condition.UNRECOGNIZED
            }
            val obj = raw as? JsonObject ?: return fail("not an object")
            val rawType = obj["type"]
            val type = stringOrNull(rawType)?.let { ConditionType.fromWire(it) }
                ?: return fail("unknown condition type \"${describeAsString(rawType)}\"")
            val rawOperator = obj["operator"]
            val operator = stringOrNull(rawOperator)?.let { ConditionOperator.fromWire(it) }
                ?: return fail("unknown operator \"${describeAsString(rawOperator)}\"")
            val rawValues = obj["values"] as? JsonArray ?: return fail("values must be an array of strings")
            val values = rawValues.map { stringOrNull(it) ?: return fail("values must be an array of strings") }
            return Condition(type = type, values = values, operator = operator)
        }

        fun test(name: String, raw: JsonElement): Test? {
            val path = "tests[\"$name\"]"
            fun drop(reason: String): Test? {
                issues += DecodeIssue(DecodeIssue.Kind.DROPPED_TEST, path, reason)
                return null
            }
            val obj = raw as? JsonObject ?: return drop("not an object")
            val salt = stringOrNull(obj["salt"]) ?: return drop("missing salt")
            val groups = when (val decoded = groups(obj["groups"])) {
                is GroupsOutcome.Malformed -> return drop(decoded.reason)
                is GroupsOutcome.Decoded -> decoded.groups
            }
            val conditions = conditions(obj["conditions"], path) ?: return drop("conditions must be an array")
            return Test(
                name = stringOrNull(obj["name"]) ?: name,
                description = stringOrNull(obj["description"]),
                type = "test",
                salt = salt,
                conditions = conditions,
                groups = groups,
            )
        }

        fun rollout(name: String, raw: JsonElement): Rollout? {
            val path = "rollouts[\"$name\"]"
            fun drop(reason: String): Rollout? {
                issues += DecodeIssue(DecodeIssue.Kind.DROPPED_ROLLOUT, path, reason)
                return null
            }
            val obj = raw as? JsonObject ?: return drop("not an object")
            val salt = stringOrNull(obj["salt"]) ?: return drop("missing salt")
            val percentage = percentageOrNull(obj["percentage"])
                ?: return drop("percentage must be an integer from 0 to 100")
            val conditions = conditions(obj["conditions"], path) ?: return drop("conditions must be an array")
            return Rollout(
                name = stringOrNull(obj["name"]) ?: name,
                description = stringOrNull(obj["description"]),
                type = "rollout",
                salt = salt,
                conditions = conditions,
                percentage = percentage,
            )
        }

        private sealed class GroupsOutcome {
            class Decoded(val groups: List<TestGroup>?) : GroupsOutcome()
            class Malformed(val reason: String) : GroupsOutcome()
        }

        /** Absent means a groupless test (whose variants never match); otherwise uniquely
         *  named groups with integer percentages summing to at most 100. */
        private fun groups(raw: JsonElement?): GroupsOutcome {
            val entries = when (raw) {
                null, JsonNull -> return GroupsOutcome.Decoded(null)
                is JsonArray -> raw
                else -> return GroupsOutcome.Malformed("groups must be an array")
            }
            val groups = ArrayList<TestGroup>()
            val names = HashSet<String>()
            var total = 0
            for (entry in entries) {
                val obj = entry as? JsonObject ?: return GroupsOutcome.Malformed("every group needs a name")
                val name = stringOrNull(obj["name"])?.takeIf { it.isNotEmpty() }
                    ?: return GroupsOutcome.Malformed("every group needs a name")
                val percentage = percentageOrNull(obj["percentage"])
                    ?: return GroupsOutcome.Malformed("group \"$name\" percentage must be an integer from 0 to 100")
                if (names.add(name) == false) return GroupsOutcome.Malformed("duplicate group \"$name\"")
                total += percentage
                groups += TestGroup(name, percentage)
            }
            if (total > 100) return GroupsOutcome.Malformed("group percentages sum to $total, more than 100")
            return GroupsOutcome.Decoded(groups)
        }
    }

    // MARK: JSON helpers (JavaScript semantics, so decoding matches the reference exactly)

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
    private val JSON_NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    /** A `tests`/`rollouts` container: absent or null is empty; anything but an object
     *  rejects the artifact. */
    private fun optionalContainer(root: JsonObject, name: String): JsonObject = when (val raw = root[name]) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> raw
        else -> throw ArtifactRejectedException("$name must be an object")
    }

    /** kotlinx accepts unquoted literals when building a JsonElement; JSON.parse does
     *  not. Every non-string literal must be `true`, `false`, `null`, or a JSON number. */
    private fun isStrictJson(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.all { isStrictJson(it) }
        is JsonArray -> element.all { isStrictJson(it) }
        is JsonNull -> true
        is JsonPrimitive -> element.isString || element.content == "true" || element.content == "false" ||
            JSON_NUMBER.matches(element.content)
    }

    private fun stringOrNull(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A JSON number as JavaScript reads it (a double), or null for anything else. */
    private fun numberOrNull(element: JsonElement?): Double? {
        val primitive = element as? JsonPrimitive ?: return null
        if (primitive.isString || primitive is JsonNull) return null
        if (JSON_NUMBER.matches(primitive.content) == false) return null
        return primitive.content.toDoubleOrNull()
    }

    /** `Number.isSafeInteger`: integral and within ±(2^53 - 1). */
    private fun isSafeInteger(value: Double): Boolean =
        value.isFinite() && value == Math.floor(value) && Math.abs(value) <= MAX_SAFE_INTEGER

    private fun percentageOrNull(element: JsonElement?): Int? =
        numberOrNull(element)?.takeIf { isSafeInteger(it) && it >= 0.0 && it <= 100.0 }?.toInt()

    /** `String(value)` for a reason message. */
    private fun describeAsString(element: JsonElement?): String = when (element) {
        null -> "undefined"
        is JsonPrimitive -> if (element.isString) element.content else describe(element)
        is JsonArray -> element.joinToString(",") { describeAsString(it) }
        is JsonObject -> "[object Object]"
    }

    /** `JSON.stringify(value)` for a reason message. */
    private fun describe(element: JsonElement?): String {
        if (element == null) return "undefined"
        val number = numberOrNull(element)
        if (number != null && isSafeInteger(number)) return number.toLong().toString()
        return element.toString()
    }
}

/**
 * ISO-8601 UTC instants as the contract admits them: `YYYY-MM-DDTHH:MM:SS[.fff]Z` with
 * 0 to 3 fraction digits, naming a real calendar moment in years 0001-9999. Date-only
 * strings, offsets, `24:00`, leap seconds, out-of-calendar days (`02-30`), year 0000,
 * and finer precision are invalid, so every SDK reads every admitted value the same
 * way. Fields are checked explicitly rather than trusting a lenient parser.
 */
internal object WireDate {
    private val SHAPE = Regex("(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,3}))?Z")

    fun parse(text: String): Instant? {
        val match = SHAPE.matchEntire(text) ?: return null
        val parts = match.groupValues
        val year = parts[1].toInt()
        val month = parts[2].toInt()
        val day = parts[3].toInt()
        val hour = parts[4].toInt()
        val minute = parts[5].toInt()
        val second = parts[6].toInt()
        val millis = parts[7].padEnd(3, '0').toInt()

        if (year < 1 || month !in 1..12) return null
        if (day < 1 || day > daysInMonth(year, month)) return null
        if (hour > 23 || minute > 59 || second > 59) return null
        return LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000).toInstant(ZoneOffset.UTC)
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
}
