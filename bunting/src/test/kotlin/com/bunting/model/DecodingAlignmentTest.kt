package com.bunting.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class DecodingAlignmentTest {

    private fun decodeValue(literal: String): FlagValue =
        BuntingJson.decodeFromJsonElement(FlagValueSerializer, BuntingJson.parseToJsonElement(literal))

    @Test
    fun flagValueDecodePrecedenceMatchesSwift() {
        assertEquals(FlagValue.BoolValue(true), decodeValue("true"))
        assertEquals(FlagValue.IntValue(42L), decodeValue("42"))
        // A whole number decodes as Integer even for a double-typed flag; the
        // double() accessor coerces later (see facade task).
        assertEquals(FlagValue.IntValue(2L), decodeValue("2"))
        assertEquals(FlagValue.DoubleValue(2.5), decodeValue("2.5"))
        assertEquals(FlagValue.StringValue("free"), decodeValue("\"free\""))
        assertEquals(FlagValue.StringValue("18.0"), decodeValue("\"18.0\""))
        assertEquals(FlagValue.DateValue(Instant.parse("2026-07-02T00:00:00.123Z")),
            decodeValue("\"2026-07-02T00:00:00.123Z\""))
        assertTrue(decodeValue("\"{\\\"a\\\":1}\"") is FlagValue.JsonValue)
    }

    @Test
    fun decodesAnArtifactWithAllTypes() {
        val json = """
        {
          "schema_version": 1,
          "config_version": "2026-07-02.9",
          "published_at": "2026-07-02T00:00:00.000Z",
          "app_identifier": "demo",
          "flags": {
            "store/tier": {
              "type": "string",
              "development": { "default": "free" },
              "beta": { "default": "free" },
              "production": {
                "default": "free",
                "variants": [
                  { "type": "conditional", "order": 1, "value": "pro",
                    "conditions": [ { "type": "platform", "operator": "in", "values": ["ios","android"] } ] }
                ]
              }
            }
          },
          "tests": {},
          "rollouts": {}
        }
        """.trimIndent()
        val config = BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        assertEquals(1, config.schemaVersion)
        assertEquals("demo", config.appIdentifier)
        val prod = config.flags.getValue("store/tier").config(BuntingEnvironment.PRODUCTION)
        assertEquals(FlagValue.StringValue("free"), prod.defaultValue)
        assertEquals(1, prod.variants.size)
        assertEquals(VariantType.CONDITIONAL, prod.variants[0].type)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val json = """{"schema_version":2,"config_version":"x","published_at":"2026-07-02T00:00:00Z","app_identifier":"d","flags":{}}"""
        assertThrows(IllegalArgumentException::class.java) {
            BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        }
    }
}
