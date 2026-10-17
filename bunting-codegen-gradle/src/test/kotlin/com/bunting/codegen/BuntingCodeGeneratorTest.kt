package com.bunting.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.GradleException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuntingCodeGeneratorTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream(name)) { "missing test resource $name" }
            .readBytes().decodeToString()

    // MARK: - Golden file

    @Test
    fun goldenThirteenFlagFixture() {
        val generated = BuntingCodeGenerator.generate(resource("/golden/BuntingConfig.json"), "com.example.bunting")
        assertEquals(resource("/golden/BuntingGenerated.kt.expected"), generated)
    }

    private fun generate(flagsJson: String, warnings: MutableList<String> = mutableListOf()): String =
        BuntingCodeGenerator.generate("""{"flags":$flagsJson}""", "p") { warnings.add(it) }

    /** A flag whose three environments all carry [default] (the SDK skips a flag missing any of them). */
    private fun flag(type: String, default: String) =
        """{"type":"$type","development":{"default":$default},"beta":{"default":$default},"production":{"default":$default}}"""

    // MARK: - Key-mapping rules

    @Test
    fun singleSegmentKeyBecomesRootProperty() {
        val generated = generate("""{"kill_switch":${flag("bool", "true")}}""")
        assertTrue(generated.contains("public val Bunting.killSwitch: Boolean"))
        assertTrue(generated.contains("""Bunting.bool("kill_switch", true)"""))
    }

    @Test
    fun onlyFirstSlashCreatesNamespaceDeeperSegmentsJoin() {
        val generated = generate("""{"a/b/c_d":${flag("string", "\"x\"")}}""")
        assertTrue(generated.contains("public val Bunting.a: AFlags"))
        assertTrue(generated.contains("public val bCD: String"))
        assertTrue(generated.contains("""Bunting.string("a/b/c_d", "x")"""))
    }

    @Test
    fun camelCaseHandlesDigitsAndRuns() {
        assertEquals("useNewPaywallDesign", BuntingCodeGenerator.camelCase("use_new_paywall_design"))
        assertEquals("maxItems2", BuntingCodeGenerator.camelCase("max_items_2"))
        assertEquals("a1B2", BuntingCodeGenerator.camelCase("a1__b2"))
        assertEquals("", BuntingCodeGenerator.camelCase("__"))
    }

    // MARK: - Pre-configure access

    @Test
    fun accessorsReadThroughTheDefaultReturningFacade() {
        // Bunting.instance throws before configure(); the facade methods return the default.
        val generated = generate(
            """{"root":${flag("bool", "true")},"ns/n":${flag("int", "1")},"ns/j":${flag("json", "\"{}\"")}}"""
        )
        assertFalse(generated.contains("Bunting.instance"))
        assertFalse(generated.contains("BuntingClient"))
        assertTrue(generated.contains("""get() = Bunting.bool("root", true)"""))
        assertTrue(generated.contains("""get() = Bunting.int("ns/n", 1L)"""))
        assertTrue(generated.contains("""get() = Bunting.jsonData("ns/j")"""))
        assertTrue(generated.contains("get() = NsFlags()"))
        assertTrue(generated.contains("public class NsFlags internal constructor() {"))
    }

    // MARK: - C2: values are read by declared type (reference: artifact-decoder.ts isValidWireValue)

    @Test
    fun wholeNumberDoubleDefaultEmitsDoubleLiteral() {
        assertTrue(generate("""{"m":${flag("double", "2")}}""").contains("""Bunting.double("m", 2.0)"""))
        assertTrue(generate("""{"m":${flag("double", "1e-5")}}""").contains("""Bunting.double("m", 1.0E-5)"""))
        assertTrue(generate("""{"m":${flag("double", "-0.5")}}""").contains("""Bunting.double("m", -0.5)"""))
    }

    @Test
    fun intIsAnyWholeJsonNumberInTheSafeIntegerRange() {
        assertTrue(generate("""{"n":${flag("int", "20")}}""").contains("""Bunting.int("n", 20L)"""))
        assertTrue(generate("""{"n":${flag("int", "-3")}}""").contains("""Bunting.int("n", -3L)"""))
        // JavaScript reads 2.0 and 1e3 as the integers 2 and 1000.
        assertTrue(generate("""{"n":${flag("int", "2.0")}}""").contains("""Bunting.int("n", 2L)"""))
        assertTrue(generate("""{"n":${flag("int", "1e3")}}""").contains("""Bunting.int("n", 1000L)"""))
        assertTrue(generate("""{"n":${flag("int", "9007199254740991")}}""").contains("""Bunting.int("n", 9007199254740991L)"""))
        assertTrue(generate("""{"n":${flag("int", "-9007199254740991")}}""").contains("""Bunting.int("n", -9007199254740991L)"""))
    }

    @Test
    fun dateIsAUtcInstantWithOptionalFractionalSeconds() {
        assertTrue(
            generate("""{"d":${flag("date", "\"2025-12-31T23:59:59Z\"")}}""")
                .contains("""Bunting.date("d", Instant.parse("2025-12-31T23:59:59Z"))""")
        )
        assertTrue(
            generate("""{"d":${flag("date", "\"2025-12-31T23:59:59.250Z\"")}}""")
                .contains("""Instant.parse("2025-12-31T23:59:59.250Z")""")
        )
    }

    @Test
    fun datesMustNameARealCalendarMomentWithAtMostMilliseconds() {
        fun literal(raw: String): String? = BuntingCodeGenerator.defaultLiteral(
            BuntingCodeGenerator.FlagKind.DATE, kotlinx.serialization.json.JsonPrimitive(raw),
        )
        assertEquals("""Instant.parse("2026-07-02T10:00:04.796Z")""", literal("2026-07-02T10:00:04.796Z"))
        assertEquals("""Instant.parse("2026-07-02T10:00:04.500Z")""", literal("2026-07-02T10:00:04.5Z"))
        assertEquals("""Instant.parse("2024-02-29T23:59:59Z")""", literal("2024-02-29T23:59:59Z"))
        assertEquals("""Instant.parse("2000-02-29T00:00:00Z")""", literal("2000-02-29T00:00:00Z"))
        assertEquals("""Instant.parse("0001-01-01T00:00:00Z")""", literal("0001-01-01T00:00:00Z"))
        assertEquals("""Instant.parse("9999-12-31T23:59:59.999Z")""", literal("9999-12-31T23:59:59.999Z"))
        // Nothing rolls over, and nothing finer than a millisecond is accepted.
        for (invalid in listOf(
            "0000-01-01T00:00:00Z", "1900-02-29T00:00:00Z", "2026-02-29T00:00:00Z", "2026-02-30T00:00:00Z", "2026-04-31T00:00:00Z", "2026-01-01T24:00:00Z",
            "2026-01-01T24:00:00.000Z", "2025-12-31T23:59:60Z", "2026-13-01T00:00:00Z", "2026-00-10T00:00:00Z",
            "2026-01-00T00:00:00Z", "2026-01-32T00:00:00Z", "2026-01-01T00:60:00Z", "2026-07-02T10:00:04.7969Z",
            "2026-07-02T10:00:04.Z", "2026-07-02T10:00:04+00:00",
        )) {
            assertEquals(null, literal(invalid), invalid)
        }
    }

    @Test
    fun stringAndJsonDefaultsAreVerbatimStrings() {
        // A date-shaped or JSON-shaped string on a string flag stays a string.
        val generated = generate(
            """{"s":${flag("string", "\"2025-12-31T23:59:59Z\"")},"t":${flag("string", "\"{\\\"a\\\":1}\"")},""" +
                """"layout/home":${flag("json", "\"[1,2]\"")},"layout/scalar":${flag("json", "\"42\"")}}"""
        )
        assertTrue(generated.contains("""Bunting.string("s", "2025-12-31T23:59:59Z")"""))
        assertTrue(generated.contains("""Bunting.string("t", "{\"a\":1}")"""))
        assertTrue(generated.contains("public val home: ByteArray?"))
        assertTrue(generated.contains("""Bunting.jsonData("layout/home")"""))
        assertTrue(generated.contains("""Bunting.jsonData("layout/scalar")"""))
    }

    // MARK: - C1: flags the SDK would skip get no accessor, only a warning

    @Test
    fun unknownTypeIsSkippedWithWarning() {
        val warnings = mutableListOf<String>()
        val generated = generate("""{"x":${flag("mystery", "\"v\"")},"y":${flag("bool", "true")}}""", warnings)
        assertFalse(generated.contains("Bunting.x"))
        assertTrue(generated.contains("public val Bunting.y: Boolean"))
        assertEquals(listOf("skipped flag \"x\": unknown type \"mystery\""), warnings)
    }

    @Test
    fun defaultsThatAreNotValidForTheDeclaredTypeAreSkipped() {
        val mismatches = mapOf(
            "bool_from_string" to flag("bool", "\"true\""),
            "bool_from_number" to flag("bool", "1"),
            "int_fractional" to flag("int", "2.5"),
            "int_from_string" to flag("int", "\"7\""),
            "int_above_safe_range" to flag("int", "9007199254740992"),
            "int_below_safe_range" to flag("int", "-9007199254740992"),
            "int_overflow" to flag("int", "1e999"),
            "double_from_string" to flag("double", "\"0.5\""),
            "double_overflow" to flag("double", "1e999"),
            "string_from_number" to flag("string", "5"),
            "string_from_null" to flag("string", "null"),
            "date_unparseable" to flag("date", "\"not-a-date\""),
            "date_only" to flag("date", "\"2026-07-02\""),
            "date_with_offset" to flag("date", "\"2026-07-02T00:00:00+01:00\""),
            "date_with_zero_offset" to flag("date", "\"2026-07-02T00:00:00+00:00\""),
            "date_without_zone" to flag("date", "\"2025-12-31T23:59:59\""),
            "date_lowercase_z" to flag("date", "\"2025-12-31T23:59:59z\""),
            "date_impossible_month" to flag("date", "\"2025-13-01T00:00:00Z\""),
            "date_second_60" to flag("date", "\"2025-12-31T23:59:60Z\""),
            "json_from_object" to flag("json", """{"a":1}"""),
            "not_an_object" to "true",
            "no_type" to """{"development":{"default":true},"beta":{"default":true},"production":{"default":true}}""",
        )
        val warnings = mutableListOf<String>()
        val generated = generate(mismatches.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }, warnings)
        assertFalse(generated.contains("public val"), generated)
        assertEquals(mismatches.keys.sorted(), warnings.map { it.substringAfter("skipped flag \"").substringBefore("\"") })
        assertTrue(warnings.contains("skipped flag \"bool_from_string\": development default \"true\" is not a valid bool"))
        assertTrue(warnings.contains("skipped flag \"date_with_offset\": development default \"2026-07-02T00:00:00+01:00\" is not a valid date"))
    }

    @Test
    fun everyEnvironmentMustDecodeNotJustDevelopment() {
        val good = """{"default":true,"variants":[]}"""
        val warnings = mutableListOf<String>()
        val generated = generate(
            """{
                "missing_beta":{"type":"bool","development":$good,"production":$good},
                "bad_production_default":{"type":"bool","development":$good,"beta":$good,"production":{"default":"yes"}},
                "variants_not_array":{"type":"bool","development":$good,"beta":{"default":true,"variants":{}},"production":$good},
                "null_variants_ok":{"type":"bool","development":{"default":true,"variants":null},"beta":{"default":true},"production":$good}
            }""",
            warnings,
        )
        assertEquals(
            listOf(
                "skipped flag \"bad_production_default\": production default \"yes\" is not a valid bool",
                "skipped flag \"missing_beta\": the beta environment is missing or not an object",
                "skipped flag \"variants_not_array\": beta variants must be an array",
            ),
            warnings,
        )
        assertTrue(generated.contains("public val Bunting.nullVariantsOk: Boolean"))
    }

    @Test
    fun legacyDefaultKeysAreSkipped() {
        // C4: only `default` exists; `defaultValue` / `defaultdefault` are not read.
        val warnings = mutableListOf<String>()
        val generated = generate(
            """{"a":{"type":"bool","development":{"defaultdefault":true},"beta":{"default":true},"production":{"default":true}},""" +
                """"b":{"type":"int","development":{"defaultValue":7},"beta":{"default":7},"production":{"default":7}}}""",
            warnings,
        )
        assertFalse(generated.contains("public val"))
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.endsWith("the development environment has no default") })
    }

    @Test
    fun skipSetMatchesTheReferenceDecodingVector() {
        // decoding.json "tolerant" case from the admin's conformance generator: the
        // flags codegen skips must be exactly the flags the reference decoder skips.
        val vector = Json.parseToJsonElement(resource("/decoding/tolerant.json")).jsonObject
        val artifact = vector.getValue("artifact").toString()
        val expectedSkipped = vector.getValue("skipped_flags").jsonArray.map { it.jsonPrimitive.content }
        val warnings = mutableListOf<String>()
        val generated = BuntingCodeGenerator.generate(artifact, "p") { warnings.add(it) }
        assertEquals(expectedSkipped, warnings.map { it.substringAfter("skipped flag \"").substringBefore("\"") })
        assertTrue(generated.contains("""Bunting.int("typed/int_written_as_float", 2L)"""), generated)
        assertTrue(generated.contains("""Bunting.double("typed/double_written_as_int", 3.0)"""))
        assertTrue(generated.contains("""Bunting.date("typed/date_fractional", Instant.parse("2026-07-02T10:00:04.796Z"))"""))
        assertTrue(generated.contains("""Bunting.string("typed/iso_looking_string", "2026-01-01T00:00:00Z")"""))
    }

    // MARK: - Escaping

    @Test
    fun stringDefaultsAreEscaped() {
        val generated = generate("""{"s":${flag("string", "\"say \\\"hi\\\" for ${'$'}5 \\\\ now\\nnext\\u0001\"")}}""")
        assertTrue(generated.contains("""Bunting.string("s", "say \"hi\" for \${'$'}5 \\ now\nnext\u0001")"""), generated)
    }

    @Test
    fun flagKeysAreEscapedInStringLiterals() {
        val key = """we\"ird\\key${'$'}x\n"""
        val generated = generate("""{"$key":${flag("bool", "true")}}""")
        assertTrue(generated.contains("""Bunting.bool("we\"ird\\key\${'$'}x\n", true)"""), generated)
        // The raw key never appears unescaped inside a string literal.
        assertFalse(generated.contains("(\"we\"ird"))
    }

    @Test
    fun descriptionsCannotCloseOrOpenTheKdoc() {
        val generated = generate(
            """{"d":{"type":"bool","description":"ends */ here /* nested\nsecond line","development":{"default":true},""" +
                """"beta":{"default":true},"production":{"default":true}}}"""
        )
        assertTrue(generated.contains("/** ends *&#47; here &#47;* nested second line. Flag key: `d`. Type: `bool`. */"), generated)
        val kdoc = generated.lines().first { it.startsWith("/**") }
        assertEquals(1, Regex("""/\*""").findAll(kdoc).count())
        assertEquals(1, Regex("""\*/""").findAll(kdoc).count())
    }

    // MARK: - Identifiers

    @Test
    fun hardKeywordsAreBackticked() {
        val generated = generate(
            """{"in":${flag("bool", "true")},"object/class":${flag("int", "1")},"object/when":${flag("bool", "false")}}"""
        )
        assertTrue(generated.contains("public val Bunting.`in`: Boolean"))
        assertTrue(generated.contains("public val Bunting.`object`: ObjectFlags"))
        assertTrue(generated.contains("public class ObjectFlags internal constructor()"))
        assertTrue(generated.contains("    public val `class`: Long"))
        assertTrue(generated.contains("public val `when`: Boolean"))
    }

    @Test
    fun leadingDigitsArePrefixed() {
        val generated = generate("""{"2fa_enabled":${flag("bool", "true")},"3d/mode":${flag("string", "\"x\"")}}""")
        assertTrue(generated.contains("public val Bunting._2faEnabled: Boolean"))
        assertTrue(generated.contains("public val Bunting._3d: _3dFlags"))
        assertTrue(generated.contains("public class _3dFlags internal constructor()"))
    }

    @Test
    fun keysWithoutLettersOrDigitsAreSkipped() {
        val warnings = mutableListOf<String>()
        val generated = generate("""{"__":${flag("bool", "true")},"ns/--":${flag("bool", "true")},"/x":${flag("bool", "true")}}""", warnings)
        assertFalse(generated.contains("public val"))
        assertEquals(3, warnings.size)
    }

    @Test
    fun accessorsHiddenByBuntingMembersAreSkipped() {
        val warnings = mutableListOf<String>()
        val generated = generate("""{"state":${flag("bool", "true")},"instance/x":${flag("bool", "true")},"ok":${flag("bool", "true")}}""", warnings)
        assertFalse(generated.contains("Bunting.state"))
        assertFalse(generated.contains("Bunting.instance"))
        assertTrue(generated.contains("public val Bunting.ok: Boolean"))
        assertEquals(2, warnings.size)
    }

    @Test
    fun rootPropertyCollisionFailsNamingBothKeys() {
        val error = assertThrows(GradleException::class.java) {
            generate("""{"dark_mode":${flag("bool", "true")},"dark-mode":${flag("bool", "false")}}""")
        }
        assertTrue(error.message!!.contains("\"dark-mode\""), error.message)
        assertTrue(error.message!!.contains("\"dark_mode\""), error.message)
        assertTrue(error.message!!.contains("Bunting.darkMode"), error.message)
    }

    @Test
    fun namespacePropertyCollisionFailsNamingBothKeys() {
        val error = assertThrows(GradleException::class.java) {
            generate("""{"ui/Theme":${flag("string", "\"a\"")},"ui/theme":${flag("string", "\"b\"")}}""")
        }
        assertTrue(error.message!!.contains("\"ui/Theme\"") && error.message!!.contains("\"ui/theme\""), error.message)
        assertTrue(error.message!!.contains("Bunting.ui.theme"), error.message)
    }

    @Test
    fun namespaceCollisionsFailNamingBothKeys() {
        val namespaces = assertThrows(GradleException::class.java) {
            generate("""{"my-ns/a":${flag("bool", "true")},"my_ns/b":${flag("bool", "true")}}""")
        }
        assertTrue(namespaces.message!!.contains("\"my-ns/a\"") && namespaces.message!!.contains("\"my_ns/b\""), namespaces.message)
        val rootVsNamespace = assertThrows(GradleException::class.java) {
            generate("""{"store":${flag("bool", "true")},"store/x":${flag("bool", "true")}}""")
        }
        assertTrue(rootVsNamespace.message!!.contains("\"store\"") && rootVsNamespace.message!!.contains("\"store/x\""), rootVsNamespace.message)
    }

    @Test
    fun skippedFlagsDoNotCollide() {
        val generated = generate("""{"dark_mode":${flag("bool", "true")},"dark-mode":${flag("bool", "\"no\"")}}""")
        assertTrue(generated.contains("""Bunting.bool("dark_mode", true)"""))
    }

    // MARK: - Annotations

    @Test
    fun deprecatedFlagGetsAnnotation() {
        val envs = """"development":{"default":false},"beta":{"default":false},"production":{"default":false}"""
        val generated = generate("""{"old":{"type":"bool","deprecated":true,$envs},"string_true":{"type":"bool","deprecated":"true",$envs}}""")
        assertEquals(1, Regex("""@Deprecated\("Flag archived"\)""").findAll(generated).count(), generated)
        assertTrue(generated.contains("@Deprecated(\"Flag archived\")\npublic val Bunting.old"))
    }

    // MARK: - Never-fail

    @Test
    fun missingSeedProducesFallback() {
        val generated = BuntingCodeGenerator.generate(null, "com.example.bunting")
        assertTrue(generated.contains("// Fallback:"))
        assertTrue(generated.contains("package com.example.bunting"))
        assertFalse(generated.contains("public val"))
    }

    @Test
    fun malformedSeedProducesFallbackNotException() {
        val generated = BuntingCodeGenerator.generate("{not json", "p")
        assertTrue(generated.contains("// Fallback:"))
    }

    @Test
    fun outputIsDeterministicallySorted() {
        val a = BuntingCodeGenerator.generate(resource("/golden/BuntingConfig.json"), "p")
        val b = BuntingCodeGenerator.generate(resource("/golden/BuntingConfig.json"), "p")
        assertEquals(a, b)
        // Namespaces appear in sorted order.
        val features = a.indexOf("class FeaturesFlags")
        val layout = a.indexOf("class LayoutFlags")
        val store = a.indexOf("class StoreFlags")
        val ui = a.indexOf("class UiFlags")
        assertTrue(features in 1 until layout && layout < store && store < ui)
    }
}
