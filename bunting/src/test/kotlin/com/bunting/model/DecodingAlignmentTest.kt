package com.bunting.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

/** C2: values decode by the flag's declared type, never by their JSON shape. */
class DecodingAlignmentTest {

    private fun decode(type: FlagType, literal: String): FlagValue? =
        ArtifactDecoder.decodeValue(type, BuntingJson.parseToJsonElement(literal))

    @Test
    fun boolIsOnlyAJsonBoolean() {
        assertEquals(FlagValue.BoolValue(true), decode(FlagType.BOOL, "true"))
        assertEquals(FlagValue.BoolValue(false), decode(FlagType.BOOL, "false"))
        assertNull(decode(FlagType.BOOL, "\"true\""))
        assertNull(decode(FlagType.BOOL, "1"))
        assertNull(decode(FlagType.BOOL, "null"))
    }

    @Test
    fun intIsAWholeNumberIncludingIntegralFloats() {
        assertEquals(FlagValue.IntValue(42L), decode(FlagType.INT, "42"))
        // longOrNull rejects "2.0"; the contract reads it as the int 2.
        assertEquals(FlagValue.IntValue(2L), decode(FlagType.INT, "2.0"))
        assertEquals(FlagValue.IntValue(100L), decode(FlagType.INT, "1e2"))
        assertEquals(FlagValue.IntValue(-7L), decode(FlagType.INT, "-7"))
        assertEquals(FlagValue.IntValue(9_007_199_254_740_991L), decode(FlagType.INT, "9007199254740991"))
        assertNull(decode(FlagType.INT, "2.5"))
        assertNull(decode(FlagType.INT, "\"5\""))
        assertNull(decode(FlagType.INT, "true"))
        // Past Number.MAX_SAFE_INTEGER the reference can't represent it exactly.
        assertNull(decode(FlagType.INT, "9007199254740992"))
    }

    @Test
    fun doubleIsAnyFiniteNumberIncludingWholeOnes() {
        // A whole number is a double for a double-typed flag, never an IntValue.
        assertEquals(FlagValue.DoubleValue(3.0), decode(FlagType.DOUBLE, "3"))
        assertEquals(FlagValue.DoubleValue(2.5), decode(FlagType.DOUBLE, "2.5"))
        assertEquals(FlagValue.DoubleValue(1.5e-3), decode(FlagType.DOUBLE, "1.5e-3"))
        assertNull(decode(FlagType.DOUBLE, "\"2.5\""))
        assertNull(decode(FlagType.DOUBLE, "1e400")) // Infinity is not finite
        assertNull(decode(FlagType.DOUBLE, "false"))
    }

    @Test
    fun stringIsVerbatimWhateverItLooksLike() {
        assertEquals(FlagValue.StringValue("free"), decode(FlagType.STRING, "\"free\""))
        assertEquals(FlagValue.StringValue("[Beta] Hi"), decode(FlagType.STRING, "\"[Beta] Hi\""))
        assertEquals(FlagValue.StringValue("{not json}"), decode(FlagType.STRING, "\"{not json}\""))
        assertEquals(FlagValue.StringValue("2026-01-01T00:00:00Z"), decode(FlagType.STRING, "\"2026-01-01T00:00:00Z\""))
        assertEquals(FlagValue.StringValue("42"), decode(FlagType.STRING, "\"42\""))
        assertEquals(FlagValue.StringValue(""), decode(FlagType.STRING, "\"\""))
        assertNull(decode(FlagType.STRING, "42"))
        assertNull(decode(FlagType.STRING, "null"))
        assertNull(decode(FlagType.STRING, "[\"a\"]"))
    }

    @Test
    fun dateIsAnIsoUtcInstantWithOrWithoutFraction() {
        assertEquals(FlagValue.DateValue(Instant.parse("2026-07-02T10:00:04Z")),
            decode(FlagType.DATE, "\"2026-07-02T10:00:04Z\""))
        assertEquals(FlagValue.DateValue(Instant.parse("2026-07-02T10:00:04.796Z")),
            decode(FlagType.DATE, "\"2026-07-02T10:00:04.796Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-07-02\""))
        assertNull(decode(FlagType.DATE, "\"2026-07-02T00:00:00+01:00\""))
        assertNull(decode(FlagType.DATE, "\"2026-07-02T00:00:00\""))
        assertNull(decode(FlagType.DATE, "\"2026-07-02 00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "1782950400000"))
    }

    @Test
    fun dateMustNameARealCalendarMomentWithAtMostMilliseconds() {
        assertEquals(FlagValue.DateValue(Instant.parse("2026-07-02T10:00:04.500Z")),
            decode(FlagType.DATE, "\"2026-07-02T10:00:04.5Z\""))
        assertEquals(FlagValue.DateValue(Instant.parse("2028-02-29T00:00:00Z")),
            decode(FlagType.DATE, "\"2028-02-29T00:00:00Z\""))
        assertEquals(FlagValue.DateValue(Instant.parse("2026-12-31T23:59:59.999Z")),
            decode(FlagType.DATE, "\"2026-12-31T23:59:59.999Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-02-29T00:00:00Z\"")) // not a leap year
        assertNull(decode(FlagType.DATE, "\"2026-02-30T00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-04-31T00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-13-01T00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-00-01T00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-01-00T00:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-07-02T24:00:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-01-01T23:60:00Z\""))
        assertNull(decode(FlagType.DATE, "\"2026-06-30T23:59:60Z\"")) // no leap seconds
        assertNull(decode(FlagType.DATE, "\"2026-07-02T00:00:00.123456Z\"")) // finer than milliseconds
        assertNull(decode(FlagType.DATE, "\"2026-07-02T00:00:00.Z\""))
        assertNull(decode(FlagType.DATE, "\"0000-02-29T00:00:00Z\"")) // year 0000 is excluded
        assertNull(decode(FlagType.DATE, "\"1900-02-29T00:00:00Z\"")) // divisible by 100, not 400
        assertEquals(FlagValue.DateValue(Instant.parse("2000-02-29T00:00:00Z")),
            decode(FlagType.DATE, "\"2000-02-29T00:00:00Z\""))
        assertEquals(FlagValue.DateValue(Instant.parse("0001-01-01T00:00:00Z")),
            decode(FlagType.DATE, "\"0001-01-01T00:00:00Z\""))
    }

    @Test
    fun jsonIsAStringHoldingJsonTextVerbatim() {
        assertEquals(FlagValue.JsonValue("""{"a":1}"""), decode(FlagType.JSON, "\"{\\\"a\\\":1}\""))
        assertEquals(FlagValue.JsonValue("42"), decode(FlagType.JSON, "\"42\""))
        assertEquals(FlagValue.JsonValue("\n{\"a\":1}"), decode(FlagType.JSON, "\"\\n{\\\"a\\\":1}\""))
        assertNull(decode(FlagType.JSON, """{"a":1}"""))
        assertNull(decode(FlagType.JSON, "[1,2]"))
        assertNull(decode(FlagType.JSON, "42"))
    }

    @Test
    fun decodesAnArtifactWithAllTypesByDeclaredType() {
        val json = """
        {
          "schema_version": 1,
          "config_version": "2026-07-02.9",
          "published_at": "2026-07-02T00:00:00.000Z",
          "app_identifier": "demo",
          "flags": {
            "t/bool": { "type": "bool", "development": { "default": true }, "beta": { "default": true }, "production": { "default": true } },
            "t/int": { "type": "int", "development": { "default": 2.0 }, "beta": { "default": 2 }, "production": { "default": 2 } },
            "t/double": { "type": "double", "development": { "default": 3 }, "beta": { "default": 3 }, "production": { "default": 3.5 } },
            "t/string": { "type": "string", "development": { "default": "2026-01-01T00:00:00Z" }, "beta": { "default": "[x]" }, "production": { "default": "free",
              "variants": [ { "type": "conditional", "order": 1, "value": "pro",
                "conditions": [ { "type": "platform", "operator": "in", "values": ["ios","android"] } ] } ] } },
            "t/date": { "type": "date", "development": { "default": "2026-07-02T10:00:04Z" }, "beta": { "default": "2026-07-02T10:00:04Z" }, "production": { "default": "2026-07-02T10:00:04.796Z" } },
            "t/json": { "type": "json", "development": { "default": "42" }, "beta": { "default": "[]" }, "production": { "default": "{\"a\":1}" } }
          },
          "tests": {},
          "rollouts": {}
        }
        """.trimIndent()
        val result = ArtifactDecoder.decode(json)
        assertEquals(emptyList<DecodeIssue>(), result.issues)
        val flags = result.configuration.flags
        assertEquals(FlagValue.BoolValue(true), flags.getValue("t/bool").production.defaultValue)
        assertEquals(FlagValue.IntValue(2L), flags.getValue("t/int").development.defaultValue)
        assertEquals(FlagValue.DoubleValue(3.0), flags.getValue("t/double").development.defaultValue)
        assertEquals(FlagValue.StringValue("2026-01-01T00:00:00Z"), flags.getValue("t/string").development.defaultValue)
        assertEquals(FlagValue.StringValue("[x]"), flags.getValue("t/string").beta.defaultValue)
        assertEquals(FlagValue.StringValue("pro"), flags.getValue("t/string").production.variants.single().value)
        assertEquals(FlagValue.DateValue(Instant.parse("2026-07-02T10:00:04.796Z")), flags.getValue("t/date").production.defaultValue)
        assertEquals(FlagValue.JsonValue("42"), flags.getValue("t/json").development.defaultValue)
        assertEquals(FlagValue.JsonValue("[]"), flags.getValue("t/json").beta.defaultValue)
    }

    @Test
    fun serializerDecodesByTheSameContract() {
        val json = """{"schema_version":1,"config_version":"x","published_at":"2026-07-02T00:00:00Z","app_identifier":"d",
            "flags":{"ok":{"type":"double","development":{"default":1},"beta":{"default":1},"production":{"default":1}},
                     "bad":{"type":"color","development":{"default":"red"},"beta":{"default":"red"},"production":{"default":"red"}}}}"""
        val config = BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        assertEquals(setOf("ok"), config.flags.keys)
        assertEquals(FlagValue.DoubleValue(1.0), config.flags.getValue("ok").production.defaultValue)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val json = """{"schema_version":2,"config_version":"x","published_at":"2026-07-02T00:00:00Z","app_identifier":"d","flags":{}}"""
        // ArtifactRejectedException is a SerializationException, so still an IllegalArgumentException.
        assertThrows(IllegalArgumentException::class.java) {
            BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        }
        val rejection = assertThrows(ArtifactRejectedException::class.java) { ArtifactDecoder.decode(json) }
        assertEquals("unsupported schema_version 2", rejection.reason)
    }

    @Test
    fun wireEncodingRoundTrips() {
        val json = """
        {"schema_version":1,"config_version":"rt-1","published_at":"2026-07-02T00:00:00Z","app_identifier":"demo",
         "flags":{
           "a/b":{"type":"int","description":"d","deprecated":true,
             "development":{"default":1},"beta":{"default":2},
             "production":{"default":3,"variants":[
               {"type":"test","order":2,"test":"exp","values":{"x":10,"y":20}},
               {"type":"geo_fence","order":1},
               {"type":"rollout","order":3,"rollout":"r","value":30},
               {"type":"conditional","order":4,"value":40,"conditions":[{"type":"moon","operator":"in","values":["full"]},
                 {"type":"region","operator":"in","values":["US"]}]}]}}},
         "tests":{"exp":{"name":"exp","type":"test","salt":"s","conditions":[],"groups":[{"name":"x","percentage":50},{"name":"y","percentage":50}]}},
         "rollouts":{"r":{"name":"r","type":"rollout","salt":"s2","conditions":[],"percentage":25}}}
        """.trimIndent()
        val first = BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        val encoded = BuntingJson.encodeToString(BuntingConfiguration.serializer(), first)
        val second = BuntingJson.decodeFromString(BuntingConfiguration.serializer(), encoded)
        assertEquals(first, second)
    }
}
