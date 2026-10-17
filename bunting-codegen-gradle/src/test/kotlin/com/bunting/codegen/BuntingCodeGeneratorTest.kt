package com.bunting.codegen

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

    private fun flag(type: String, default: String) = """{"type":"$type","development":{"default":$default}}"""

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

    // MARK: - C2: defaults are read by declared type

    @Test
    fun wholeNumberDoubleDefaultEmitsDoubleLiteral() {
        assertTrue(generate("""{"m":${flag("double", "2")}}""").contains("""Bunting.double("m", 2.0)"""))
        assertTrue(generate("""{"m":${flag("double", "1e-5")}}""").contains("""Bunting.double("m", 1.0E-5)"""))
        assertTrue(generate("""{"m":${flag("double", "-0.5")}}""").contains("""Bunting.double("m", -0.5)"""))
    }

    @Test
    fun intDefaultsGetLongLiterals() {
        assertTrue(generate("""{"n":${flag("int", "20")}}""").contains("""Bunting.int("n", 20L)"""))
        assertTrue(generate("""{"n":${flag("int", "-3")}}""").contains("""Bunting.int("n", -3L)"""))
        assertTrue(
            generate("""{"n":${flag("int", "-9223372036854775808")}}""").contains("""Bunting.int("n", Long.MIN_VALUE)""")
        )
    }

    @Test
    fun dateDefaultsAreNormalizedToCanonicalUtc() {
        assertTrue(
            generate("""{"d":${flag("date", "\"2025-12-31T23:59:59Z\"")}}""")
                .contains("""Bunting.date("d", Instant.parse("2025-12-31T23:59:59Z"))""")
        )
        assertTrue(
            generate("""{"d":${flag("date", "\"2025-12-31T23:59:59.250Z\"")}}""")
                .contains("""Instant.parse("2025-12-31T23:59:59.250Z")""")
        )
        assertTrue(
            generate("""{"d":${flag("date", "\"2026-01-01T01:00:00+01:00\"")}}""")
                .contains("""Instant.parse("2026-01-01T00:00:00Z")""")
        )
    }

    @Test
    fun stringAndJsonDefaultsAreVerbatimStrings() {
        // A date-shaped or JSON-shaped string on a string flag stays a string.
        val generated = generate(
            """{"s":${flag("string", "\"2025-12-31T23:59:59Z\"")},"t":${flag("string", "\"{\\\"a\\\":1}\"")},""" +
                """"layout/home":${flag("json", "\"[1,2]\"")}}"""
        )
        assertTrue(generated.contains("""Bunting.string("s", "2025-12-31T23:59:59Z")"""))
        assertTrue(generated.contains("""Bunting.string("t", "{\"a\":1}")"""))
        assertTrue(generated.contains("public val home: ByteArray?"))
        assertTrue(generated.contains("""Bunting.jsonData("layout/home")"""))
    }

    // MARK: - C1: unknown types and mismatched defaults are skipped with a warning

    @Test
    fun unknownTypeIsSkippedWithWarning() {
        val warnings = mutableListOf<String>()
        val generated = generate("""{"x":${flag("mystery", "\"v\"")},"y":${flag("bool", "true")}}""", warnings)
        assertFalse(generated.contains("Bunting.x"))
        assertTrue(generated.contains("public val Bunting.y: Boolean"))
        assertEquals(listOf("skipped flag \"x\": unknown type \"mystery\""), warnings)
    }

    @Test
    fun defaultsThatDoNotMatchTheDeclaredTypeAreSkipped() {
        val mismatches = mapOf(
            "bool_from_string" to flag("bool", "\"true\""),
            "bool_from_number" to flag("bool", "1"),
            "int_from_fraction" to flag("int", "1.5"),
            "int_from_exponent" to flag("int", "1e3"),
            "int_from_string" to flag("int", "\"7\""),
            "int_out_of_range" to flag("int", "9223372036854775808"),
            "double_from_string" to flag("double", "\"0.5\""),
            "double_overflow" to flag("double", "1e999"),
            "string_from_number" to flag("string", "5"),
            "string_from_null" to flag("string", "null"),
            "date_unparseable" to flag("date", "\"not-a-date\""),
            "date_without_offset" to flag("date", "\"2025-12-31T23:59:59\""),
            "json_from_object" to flag("json", """{"a":1}"""),
            "no_type" to """{"development":{"default":true}}""",
            "no_development" to """{"type":"bool"}""",
            "not_an_object" to "true",
        )
        val warnings = mutableListOf<String>()
        val generated = generate(mismatches.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }, warnings)
        assertFalse(generated.contains("public val"), generated)
        assertEquals(mismatches.keys.sorted(), warnings.map { it.substringAfter("skipped flag \"").substringBefore("\"") })
        assertTrue(warnings.contains("skipped flag \"bool_from_string\": development default \"true\" does not match its type \"bool\""))
    }

    @Test
    fun legacyDefaultKeysAreSkipped() {
        // C4: only `default` exists; `defaultValue` / `defaultdefault` are not read.
        val warnings = mutableListOf<String>()
        val generated = generate(
            """{"a":{"type":"bool","development":{"defaultdefault":true}},"b":{"type":"int","development":{"defaultValue":7}}}""",
            warnings,
        )
        assertFalse(generated.contains("public val"))
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.endsWith("its development environment has no default") })
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
            """{"d":{"type":"bool","description":"ends */ here /* nested\nsecond line","development":{"default":true}}}"""
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
        assertTrue(generated.contains("    @get:JvmName(\"getClass_\")\n    public val `class`: Long"))
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
        val generated = generate("""{"old":{"type":"bool","deprecated":true,"development":{"default":false}}}""")
        assertTrue(generated.contains("""@Deprecated("Flag archived")"""))
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
