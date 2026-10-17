package com.bunting.evaluation

import com.bunting.model.ArtifactDecoder
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

    // MARK: fail-closed conditions (C1)

    @Test
    fun unrecognizedConditionsNeverMatch() {
        val e = ConditionEvaluator(ctx()) { true }
        assertFalse(e.evaluate(Condition.UNRECOGNIZED))
        // custom_attribute ignores its operator, so an unrecognized one must still fail closed.
        assertFalse(e.evaluate(Condition(ConditionType.CUSTOM_ATTRIBUTE, listOf("manufacturer", "google"), ConditionOperator.UNRECOGNIZED)))
        assertFalse(e.evaluate(Condition(ConditionType.PLATFORM, listOf("android"), ConditionOperator.UNRECOGNIZED)))
        assertFalse(e.evaluateAll(listOf(Condition(ConditionType.PLATFORM, listOf("android"), ConditionOperator.IN), Condition.UNRECOGNIZED)))
    }

    // MARK: test assignment (C3) and placeholders

    private val header =
        """"schema_version":1,"config_version":"c3-1","published_at":"2026-07-02T00:00:00Z","app_identifier":"demo""""

    /** A string flag whose environments all share [variants], default "default". */
    private fun config(variants: String, tests: String = "{}", rollouts: String = "{}"): BuntingConfiguration {
        val env = """{"default":"default","variants":$variants}"""
        val text = """{$header,"flags":{"f":{"type":"string","development":$env,"beta":$env,"production":$env}},
            "tests":$tests,"rollouts":$rollouts}"""
        return ArtifactDecoder.decode(text).configuration
    }

    /** The first canonical test identity whose bucket for [salt] falls in [buckets]. */
    private fun idInBuckets(salt: String, buckets: IntRange): UUID =
        (1..10_000).asSequence()
            .map { UUID.fromString("AAAAAAAA-0000-4000-8000-%012d".format(it)) }
            .first { Bucketing.bucket(salt, it) in buckets }

    private fun evaluator(config: BuntingConfiguration, id: UUID, context: EvaluationContext = ctx()) =
        FlagEvaluator(config, BuntingEnvironment.PRODUCTION, context, id) { false }

    private val remainderTest = """{"exp":{"salt":"exp-salt","groups":[{"name":"a","percentage":30},{"name":"b","percentage":30}]}}"""

    @Test
    fun remainderUsersFallThroughToTheNextVariantThenDefault() {
        val withNext = config(
            """[{"type":"test","order":1,"test":"exp","values":{"a":"A","b":"B"}},
                {"type":"conditional","order":2,"value":"next"}]""",
            tests = remainderTest,
        )
        // Paths: 0 default, 1 group a, 2 group b, 3 conditional.
        val inA = evaluator(withNext, idInBuckets("exp-salt", 1..30))
        assertEquals(FlagValue.StringValue("A"), inA.evaluate("f"))
        assertEquals(1, inA.resolvePathIndex("f"))
        val inB = evaluator(withNext, idInBuckets("exp-salt", 31..60))
        assertEquals(FlagValue.StringValue("B"), inB.evaluate("f"))
        assertEquals(2, inB.resolvePathIndex("f"))
        // Before C3 a remainder user got the first group's value while the fingerprint
        // said "no match"; now both agree on the next variant.
        val remainder = evaluator(withNext, idInBuckets("exp-salt", 61..100))
        assertEquals(FlagValue.StringValue("next"), remainder.evaluate("f"))
        assertEquals(3, remainder.resolvePathIndex("f"))

        val alone = config("""[{"type":"test","order":1,"test":"exp","values":{"a":"A","b":"B"}}]""", tests = remainderTest)
        val remainderAlone = evaluator(alone, idInBuckets("exp-salt", 61..100))
        assertEquals(FlagValue.StringValue("default"), remainderAlone.evaluate("f"))
        assertEquals(0, remainderAlone.resolvePathIndex("f"))
    }

    @Test
    fun aGroupWithoutAValueSkipsTheVariant() {
        val c = config("""[{"type":"test","order":1,"test":"exp","values":{"a":"A"}}]""", tests = remainderTest)
        val inB = evaluator(c, idInBuckets("exp-salt", 31..60))
        assertEquals(FlagValue.StringValue("default"), inB.evaluate("f"))
        assertEquals(0, inB.resolvePathIndex("f"))
        assertEquals(3, inB.pathCount("f")) // the group still owns a path
    }

    @Test
    fun grouplessAndPreconditionedTestsDoNotMatch() {
        val tests = """{
          "groupless":{"salt":"s"},
          "empty":{"salt":"s","groups":[]},
          "ios_only":{"salt":"s","conditions":[{"type":"platform","operator":"in","values":["ios"]}],"groups":[{"name":"all","percentage":100}]},
          "unknown_pre":{"salt":"s","conditions":[{"type":"moon_phase","operator":"in","values":["full"]}],"groups":[{"name":"all","percentage":100}]},
          "android_only":{"salt":"s","conditions":[{"type":"platform","operator":"in","values":["android"]}],"groups":[{"name":"all","percentage":100}]}
        }"""
        val c = config(
            """[{"type":"test","order":1,"test":"groupless","values":{"all":"x"}},
                {"type":"test","order":2,"test":"empty","values":{"all":"x"}},
                {"type":"test","order":3,"test":"ios_only","values":{"all":"ios"}},
                {"type":"test","order":4,"test":"unknown_pre","values":{"all":"unknown"}},
                {"type":"test","order":5,"test":"missing","values":{"all":"missing"}},
                {"type":"test","order":6,"test":"android_only","values":{"all":"android"}}]""",
            tests = tests,
        )
        val e = evaluator(c, idInBuckets("s", 1..100))
        assertEquals(FlagValue.StringValue("android"), e.evaluate("f"))
        // Groupless, empty and missing tests own no paths; ios_only and unknown_pre own one each.
        assertEquals(3, e.resolvePathIndex("f"))
        assertEquals(4, e.pathCount("f"))
    }

    @Test
    fun placeholdersNeverMatchButKeepTheirPath() {
        val c = config(
            """[{"type":"geo_fence","order":1,"value":"x"},
                {"type":"conditional","order":2,"value":5},
                {"type":"conditional","order":3,"value":"unknown-op","conditions":[{"type":"platform","operator":"regex","values":["android"]}]},
                {"type":"rollout","order":4,"rollout":"gone","value":"r"},
                {"type":"conditional","order":5,"value":"hit","conditions":[{"type":"platform","operator":"in","values":["android"]}]}]""",
        )
        val e = evaluator(c, idInBuckets("s", 1..100))
        assertEquals(FlagValue.StringValue("hit"), e.evaluate("f"))
        assertEquals(5, e.resolvePathIndex("f"))
        assertEquals(6, e.pathCount("f"))
    }

    @Test
    fun variantsEvaluateInOrderNotWireOrder() {
        val c = config(
            """[{"type":"conditional","order":2,"value":"second"},{"type":"conditional","order":1,"value":"first"}]""",
        )
        val e = evaluator(c, idInBuckets("s", 1..100))
        assertEquals(FlagValue.StringValue("first"), e.evaluate("f"))
        assertEquals(1, e.resolvePathIndex("f"))
    }
}
