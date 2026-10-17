package com.bunting.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** C1: one bad entry never rejects the artifact; only the header is strict. */
class ArtifactDecoderTest {

    private val header =
        """"schema_version":1,"config_version":"c-1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo""""

    private fun artifact(flags: String = "{}", extra: String = ""): String =
        "{$header,\"flags\":$flags${if (extra.isEmpty()) "" else ",$extra"}}"

    /** A flag whose three environments share one default and variant list. */
    private fun flag(type: String, default: String, variants: String = "[]"): String {
        val env = """{"default":$default,"variants":$variants}"""
        return """{"type":"$type","development":$env,"beta":$env,"production":$env}"""
    }

    private fun rejection(text: String): String =
        assertThrows(ArtifactRejectedException::class.java) { ArtifactDecoder.decode(text) }.reason

    private fun kinds(result: DecodeResult): List<DecodeIssue.Kind> = result.issues.map { it.kind }

    // MARK: header

    @Test
    fun headerProblemsRejectTheArtifact() {
        assertEquals("not valid JSON", rejection(artifact().dropLast(1)))
        assertEquals("not valid JSON", rejection("""{$header,"flags":{"a":{"type":bool}}}"""))
        assertEquals("top level is not an object", rejection("[]"))
        assertEquals("unsupported schema_version 2", rejection(artifact().replace("\"schema_version\":1", "\"schema_version\":2")))
        assertEquals("unsupported schema_version \"1\"", rejection(artifact().replace("\"schema_version\":1", "\"schema_version\":\"1\"")))
        assertEquals("unsupported schema_version undefined", rejection(artifact().replace("\"schema_version\":1,", "")))
        assertEquals("missing config_version", rejection(artifact().replace("\"c-1\"", "\"\"")))
        assertEquals("missing config_version", rejection(artifact().replace("\"config_version\":\"c-1\",", "")))
        assertEquals("published_at is not an ISO-8601 UTC instant",
            rejection(artifact().replace("2026-07-02T00:00:00.000Z", "2026-07-02")))
        assertEquals("published_at is not an ISO-8601 UTC instant",
            rejection(artifact().replace("2026-07-02T00:00:00.000Z", "2026-07-02T00:00:00+00:00")))
        assertEquals("missing app_identifier", rejection(artifact().replace("\"app_identifier\":\"demo\"", "\"app_identifier\":7")))
        assertEquals("flags must be an object", rejection("{$header}"))
        assertEquals("flags must be an object", rejection(artifact(flags = "[]")))
        assertEquals("tests must be an object", rejection(artifact(extra = "\"tests\":[]")))
        assertEquals("rollouts must be an object", rejection(artifact(extra = "\"rollouts\":\"none\"")))
    }

    @Test
    fun lenientHeaderSpellingsAreAccepted() {
        // 1.0 is the number 1; tests/rollouts may be absent or null; app_identifier may be empty.
        val result = ArtifactDecoder.decode(
            artifact(extra = "\"tests\":null").replace("\"schema_version\":1", "\"schema_version\":1.0")
                .replace("\"app_identifier\":\"demo\"", "\"app_identifier\":\"\"")
        )
        assertTrue(result.isLossless)
        assertEquals(emptyMap<String, Test>(), result.configuration.tests)
        assertEquals(emptyMap<String, Rollout>(), result.configuration.rollouts)
        assertEquals("2026-07-02T00:00:00.000Z", result.configuration.publishedAt)
    }

    @Test
    fun unknownKeysAreIgnoredEverywhere() {
        val text = """
        {$header,"future":{"x":1},
         "flags":{"a":{"type":"bool","owner":"team","development":{"default":false,"note":1},"beta":{"default":false},
           "production":{"default":false,"variants":[{"type":"conditional","order":1,"value":true,"weight":3,
             "conditions":[{"type":"platform","operator":"in","values":["android"],"id":"c1"}]}]}}},
         "tests":{"t":{"salt":"s","groups":[{"name":"g","percentage":10,"color":"red"}],"extra":true}},
         "rollouts":{"r":{"salt":"s","percentage":5,"extra":true}}}
        """.trimIndent()
        val result = ArtifactDecoder.decode(text)
        assertTrue(result.isLossless, "${result.issues}")
        assertEquals(setOf("a"), result.configuration.flags.keys)
        assertEquals(setOf("t"), result.configuration.tests.keys)
        assertEquals(setOf("r"), result.configuration.rollouts.keys)
    }

    // MARK: flags

    @Test
    fun badFlagsAreSkippedAndTheRestSurvive() {
        val flags = """
        {
          "ok": ${flag("bool", "false")},
          "unknown_type": ${flag("color", "\"red\"")},
          "missing_env": {"type":"bool","development":{"default":false},"production":{"default":false}},
          "wrong_default": ${flag("int", "\"5\"")},
          "null_default": ${flag("string", "null")},
          "variants_object": {"type":"bool","development":{"default":false,"variants":{}},"beta":{"default":false},"production":{"default":false}},
          "not_object": "bool"
        }
        """.trimIndent()
        val result = ArtifactDecoder.decode(artifact(flags))
        assertEquals(setOf("ok"), result.configuration.flags.keys)
        assertEquals(
            listOf("unknown_type", "missing_env", "wrong_default", "null_default", "variants_object", "not_object"),
            result.skippedFlagKeys,
        )
        assertTrue(kinds(result).all { it == DecodeIssue.Kind.SKIPPED_FLAG })
        val reasons = result.issues.associate { it.path to it.reason }
        assertEquals("unknown flag type \"color\"", reasons["flags[\"unknown_type\"]"])
        assertEquals("flags[\"missing_env\"].beta is missing or not an object", reasons["flags[\"missing_env\"]"])
        assertEquals("flags[\"wrong_default\"].development.default is not a valid int", reasons["flags[\"wrong_default\"]"])
        assertEquals("flags[\"variants_object\"].development.variants must be an array", reasons["flags[\"variants_object\"]"])
        assertEquals("not an object", reasons["flags[\"not_object\"]"])
    }

    @Test
    fun aSkippedFlagReportsOnlyTheSkip() {
        // The bad variant in development would be a placeholder, but beta's default
        // skips the flag, so only the skip is reported.
        val bad = """{"type":"bool","development":{"default":false,"variants":[{"type":"geo","order":1}]},
            "beta":{"default":"no"},"production":{"default":false}}"""
        val result = ArtifactDecoder.decode(artifact("""{"x":$bad}"""))
        assertEquals(listOf(DecodeIssue.Kind.SKIPPED_FLAG), kinds(result))
    }

    @Test
    fun descriptionAndDeprecatedAreOptional() {
        val flags = """{"a":{"type":"bool","description":"Gate","deprecated":true,"development":{"default":false},"beta":{"default":false},"production":{"default":false}},
            "b":{"type":"bool","description":5,"deprecated":"yes","development":{"default":false},"beta":{"default":false},"production":{"default":false}}}"""
        val config = ArtifactDecoder.decode(artifact(flags)).configuration
        assertEquals("Gate", config.flags.getValue("a").description)
        assertTrue(config.flags.getValue("a").deprecated)
        assertNull(config.flags.getValue("b").description)
        assertFalse(config.flags.getValue("b").deprecated)
    }

    // MARK: variants

    @Test
    fun malformedVariantsBecomePlaceholdersInWireOrder() {
        val variants = """[
          {"type":"geo_fence","order":5,"value":"x"},
          {"type":"conditional","order":4,"value":5},
          {"type":"conditional","order":3,"value":"x","conditions":{}},
          {"type":"test","order":2,"values":{"a":"x"}},
          {"type":"test","order":6,"test":"t","values":["x"]},
          {"type":"test","order":7,"test":"t","values":{"a":"x","b":1}},
          {"type":"rollout","order":8,"value":"x"},
          {"type":"rollout","order":9,"rollout":"r","value":true},
          {"order":10,"value":"x"},
          {"type":"conditional","order":1,"value":"ok"}
        ]"""
        val result = ArtifactDecoder.decode(artifact("""{"f":${flag("string", "\"d\"", variants)}}"""))
        val prod = result.configuration.flags.getValue("f").production
        assertEquals(listOf(5, 4, 3, 2, 6, 7, 8, 9, 10, 1), prod.variants.map { it.order })
        assertEquals(List(9) { VariantType.UNRECOGNIZED } + VariantType.CONDITIONAL, prod.variants.map { it.type })
        assertEquals(Variant.unrecognized(5), prod.variants[0])
        // 9 placeholders per environment.
        assertEquals(27, result.issues.count { it.kind == DecodeIssue.Kind.UNRECOGNIZED_VARIANT })
        val reasons = result.issues.filter { it.path.startsWith("flags[\"f\"].production") }.map { it.reason }
        assertEquals(
            listOf(
                "unknown variant type \"geo_fence\"",
                "value is not a valid string",
                "conditions must be an array",
                "missing test reference",
                "values must be an object",
                "group \"b\" value is not a valid string",
                "missing rollout reference",
                "value is not a valid string",
                "unknown variant type \"undefined\"",
            ),
            reasons,
        )
    }

    @Test
    fun variantsWithoutAnIntegerOrderAreDropped() {
        val variants = """[
          {"type":"conditional","value":"no-order"},
          {"type":"conditional","order":"1","value":"string-order"},
          {"type":"conditional","order":1.5,"value":"fractional"},
          "not-an-object",
          {"type":"conditional","order":2.0,"value":"kept"}
        ]"""
        val result = ArtifactDecoder.decode(artifact("""{"f":${flag("string", "\"d\"", variants)}}"""))
        val prod = result.configuration.flags.getValue("f").production
        assertEquals(listOf(2), prod.variants.map { it.order })
        assertEquals(FlagValue.StringValue("kept"), prod.variants.single().value)
        assertEquals(12, result.issues.count { it.kind == DecodeIssue.Kind.DROPPED_VARIANT })
        assertEquals("flags[\"f\"].development.variants[0]", result.issues.first().path)
    }

    @Test
    fun absentConditionsStayAbsentAndMatchVacuously() {
        val variants = """[{"type":"conditional","order":1,"value":"a"},{"type":"conditional","order":2,"value":"b","conditions":null},
            {"type":"conditional","order":3,"value":"c","conditions":[]}]"""
        val prod = ArtifactDecoder.decode(artifact("""{"f":${flag("string", "\"d\"", variants)}}"""))
            .configuration.flags.getValue("f").production
        assertEquals(listOf(null, null, emptyList<Condition>()), prod.variants.map { it.conditions })
    }

    // MARK: conditions

    @Test
    fun unknownOrMalformedConditionsNeverMatch() {
        val conditions = """[
          {"type":"moon_phase","operator":"in","values":["full"]},
          {"type":"platform","operator":"matches_regex","values":["ios"]},
          {"type":"build_number","operator":"equals","values":[100]},
          {"type":"platform","operator":"in"},
          "platform",
          {"type":"platform","operator":"in","values":["ios"]}
        ]"""
        val variants = """[{"type":"conditional","order":1,"value":true,"conditions":$conditions}]"""
        val result = ArtifactDecoder.decode(artifact("""{"f":${flag("bool", "false", variants)}}"""))
        val decoded = result.configuration.flags.getValue("f").production.variants.single().conditions!!
        assertEquals(List(5) { Condition.UNRECOGNIZED } + Condition(ConditionType.PLATFORM, listOf("ios"), ConditionOperator.IN), decoded)
        assertEquals(15, result.issues.count { it.kind == DecodeIssue.Kind.UNRECOGNIZED_CONDITION })
        assertEquals("flags[\"f\"].development.variants[0].conditions[0]", result.issues.first().path)
    }

    // MARK: tests and rollouts

    @Test
    fun malformedTestsAreDropped() {
        val tests = """{
          "ok": {"salt":"s","groups":[{"name":"a","percentage":50},{"name":"b","percentage":50}]},
          "groupless": {"salt":"s"},
          "named_elsewhere": {"name":"other","salt":"s","groups":[]},
          "no_salt": {"groups":[{"name":"a","percentage":50}]},
          "duplicate": {"salt":"s","groups":[{"name":"a","percentage":10},{"name":"a","percentage":10}]},
          "fractional": {"salt":"s","groups":[{"name":"a","percentage":12.5}]},
          "out_of_range": {"salt":"s","groups":[{"name":"a","percentage":150}]},
          "over_100": {"salt":"s","groups":[{"name":"a","percentage":60},{"name":"b","percentage":60}]},
          "unnamed": {"salt":"s","groups":[{"percentage":10}]},
          "empty_name": {"salt":"s","groups":[{"name":"","percentage":10}]},
          "groups_object": {"salt":"s","groups":{}},
          "conditions_object": {"salt":"s","conditions":{}},
          "not_object": 5
        }"""
        val result = ArtifactDecoder.decode(artifact(extra = "\"tests\":$tests"))
        val decoded = result.configuration.tests
        assertEquals(setOf("ok", "groupless", "named_elsewhere"), decoded.keys)
        assertNull(decoded.getValue("groupless").groups)
        assertEquals("other", decoded.getValue("named_elsewhere").name)
        assertEquals("ok", decoded.getValue("ok").name)
        assertEquals(10, result.issues.count { it.kind == DecodeIssue.Kind.DROPPED_TEST })
        val reasons = result.issues.associate { it.path to it.reason }
        assertEquals("missing salt", reasons["tests[\"no_salt\"]"])
        assertEquals("duplicate group \"a\"", reasons["tests[\"duplicate\"]"])
        assertEquals("group \"a\" percentage must be an integer from 0 to 100", reasons["tests[\"fractional\"]"])
        assertEquals("group percentages sum to 120, more than 100", reasons["tests[\"over_100\"]"])
        assertEquals("every group needs a name", reasons["tests[\"empty_name\"]"])
        assertEquals("groups must be an array", reasons["tests[\"groups_object\"]"])
        assertEquals("conditions must be an array", reasons["tests[\"conditions_object\"]"])
    }

    @Test
    fun malformedRolloutsAreDropped() {
        val rollouts = """{
          "ok": {"salt":"s","percentage":100,"conditions":[{"type":"moon","operator":"in","values":["x"]}]},
          "whole_float": {"salt":"s","percentage":25.0},
          "over": {"salt":"s","percentage":150},
          "fractional": {"salt":"s","percentage":12.5},
          "string": {"salt":"s","percentage":"50"},
          "no_salt": {"percentage":50},
          "conditions_object": {"salt":"s","percentage":50,"conditions":{}}
        }"""
        val result = ArtifactDecoder.decode(artifact(extra = "\"rollouts\":$rollouts"))
        val decoded = result.configuration.rollouts
        assertEquals(setOf("ok", "whole_float"), decoded.keys)
        assertEquals(25, decoded.getValue("whole_float").percentage)
        // A rollout precondition this SDK can't read is neutralised, not dropped.
        assertEquals(listOf(Condition.UNRECOGNIZED), decoded.getValue("ok").conditions)
        assertEquals(5, result.issues.count { it.kind == DecodeIssue.Kind.DROPPED_ROLLOUT })
        assertEquals(1, result.issues.count { it.kind == DecodeIssue.Kind.UNRECOGNIZED_CONDITION })
    }

    @Test
    fun variantsReferencingDroppedEntriesStayTyped() {
        // The reference keeps them as test/rollout variants; they simply never match.
        val variants = """[{"type":"test","order":1,"test":"gone","values":{"a":"t"}},{"type":"rollout","order":2,"rollout":"gone","value":"r"}]"""
        val result = ArtifactDecoder.decode(artifact("""{"f":${flag("string", "\"d\"", variants)}}""",
            "\"tests\":{\"gone\":{\"groups\":[]}},\"rollouts\":{\"gone\":{\"salt\":\"s\",\"percentage\":101}}"))
        val prod = result.configuration.flags.getValue("f").production
        assertEquals(listOf(VariantType.TEST, VariantType.ROLLOUT), prod.variants.map { it.type })
        assertEquals(emptySet<String>(), result.configuration.tests.keys)
        assertEquals(emptySet<String>(), result.configuration.rollouts.keys)
    }
}
