package com.bunting.evaluation

import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.BuntingJson
import com.bunting.model.Condition
import com.bunting.model.ConditionOperator
import com.bunting.model.ConditionType
import com.bunting.model.FlagValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class EvaluationEngineTest {

    private fun ctx(
        platform: String = "android",
        deviceClass: String? = "phone",
        region: String? = "US",
        osVersion: String = "15.0.0",
        appVersion: String = "2.5.0",
        reserved: Map<String, String> = mapOf("manufacturer" to "google"),
    ) = EvaluationContext(
        platform = platform, osVersion = osVersion, appVersion = appVersion,
        buildNumber = "100", deviceModel = "Pixel 8", region = region,
        language = "en", deviceClass = deviceClass, reservedAttributes = reserved,
    )

    @Test
    fun bucketingIsDeterministicAndInRange() {
        val id = UUID.fromString("AAAAAAAA-0000-4000-8000-000000000001")
        val b = Bucketing.bucket("salt-alpha", id)
        assertEquals(b, Bucketing.bucket("salt-alpha", id))
        assertTrue(b in 1..100)
    }

    @Test
    fun conditionAbsentFieldNeverMatches() {
        val e = ConditionEvaluator(ctx(deviceClass = null, region = null)) { false }
        assertFalse(e.evaluate(Condition(ConditionType.DEVICE_CLASS, listOf("phone"), ConditionOperator.IN)))
        assertFalse(e.evaluate(Condition(ConditionType.REGION, listOf("US"), ConditionOperator.IN)))
    }

    @Test
    fun reservedAttributeResolvesInternally() {
        val e = ConditionEvaluator(ctx()) { false }
        val cond = Condition(ConditionType.CUSTOM_ATTRIBUTE, listOf("manufacturer", "google", "samsung"), ConditionOperator.CUSTOM)
        assertTrue(e.evaluate(cond))
        val absent = ConditionEvaluator(ctx(reserved = emptyMap())) { true }
        assertFalse(absent.evaluate(cond)) // absent reserved value never delegates to the resolver
    }

    @Test
    fun versionBetweenAndListMembership() {
        val e = ConditionEvaluator(ctx(osVersion = "15.0.0")) { false }
        assertTrue(e.evaluate(Condition(ConditionType.OS_VERSION, listOf("14.0", "16.0"), ConditionOperator.BETWEEN)))
        assertTrue(e.evaluate(Condition(ConditionType.PLATFORM, listOf("ios", "android"), ConditionOperator.IN)))
        assertFalse(e.evaluate(Condition(ConditionType.PLATFORM, listOf("ios"), ConditionOperator.IN)))
    }

    @Test
    fun fingerprintPrimitivesMatchReference() {
        assertEquals(0, ConfigFingerprint.bitWidth(1))
        assertEquals(1, ConfigFingerprint.bitWidth(2))
        assertEquals(2, ConfigFingerprint.bitWidth(3))
        assertEquals(2, ConfigFingerprint.bitWidth(4))
        // CRC-8/SMBUS of the ASCII byte 0x31 ("1") is 0x97 with poly 0x07 init 0x00
        // (verified against the admin reference codec and the Swift crc8 examples).
        assertEquals(0x97, ConfigFingerprint.crc8(listOf(0x31)))
    }

    @Test
    fun evaluatesFirstMatchingVariantThenDefault() {
        val json = """
        {
          "schema_version": 1, "config_version": "2026-07-02.1",
          "published_at": "2026-07-02T00:00:00.000Z", "app_identifier": "demo",
          "flags": {
            "f/gate": {
              "type": "bool",
              "development": { "default": false }, "beta": { "default": false },
              "production": {
                "default": false,
                "variants": [
                  { "type": "conditional", "order": 1, "value": true,
                    "conditions": [ { "type": "platform", "operator": "in", "values": ["android"] } ] }
                ]
              }
            }
          },
          "tests": {}, "rollouts": {}
        }
        """.trimIndent()
        val config = BuntingJson.decodeFromString(BuntingConfiguration.serializer(), json)
        val id = UUID.fromString("AAAAAAAA-0000-4000-8000-000000000001")
        val onAndroid = FlagEvaluator(config, BuntingEnvironment.PRODUCTION, ctx(platform = "android"), id) { false }
        assertEquals(FlagValue.BoolValue(true), onAndroid.evaluate("f/gate"))
        val onIos = FlagEvaluator(config, BuntingEnvironment.PRODUCTION, ctx(platform = "ios"), id) { false }
        assertEquals(FlagValue.BoolValue(false), onIos.evaluate("f/gate"))
    }
}
