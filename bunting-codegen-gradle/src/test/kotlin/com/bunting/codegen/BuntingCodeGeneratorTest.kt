package com.bunting.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // MARK: - Key-mapping rules

    @Test
    fun singleSegmentKeyBecomesRootProperty() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"kill_switch":{"type":"bool","development":{"default":true}}}}""",
            "p",
        )
        assertTrue(generated.contains("public val Bunting.killSwitch: Boolean"))
        assertTrue(generated.contains("""Bunting.instance.bool("kill_switch", true)"""))
    }

    @Test
    fun onlyFirstSlashCreatesNamespaceDeeperSegmentsJoin() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"a/b/c_d":{"type":"string","development":{"default":"x"}}}}""",
            "p",
        )
        assertTrue(generated.contains("public val Bunting.a: AFlags"))
        assertTrue(generated.contains("public val bCD: String"))
        assertTrue(generated.contains("""bunting.string("a/b/c_d", "x")"""))
    }

    @Test
    fun camelCaseHandlesDigitsAndRuns() {
        assertEquals("useNewPaywallDesign", BuntingCodeGenerator.camelCase("use_new_paywall_design"))
        assertEquals("maxItems2", BuntingCodeGenerator.camelCase("max_items_2"))
        assertEquals("a1B2", BuntingCodeGenerator.camelCase("a1__b2"))
    }

    // MARK: - Type rendering

    @Test
    fun wholeNumberDoubleDefaultEmitsDoubleLiteral() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"m":{"type":"double","development":{"default":2}}}}""",
            "p",
        )
        assertTrue(generated.contains("""Bunting.instance.double("m", 2.0)"""))
    }

    @Test
    fun intDefaultsGetLongLiterals() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"n":{"type":"int","development":{"default":20}}}}""",
            "p",
        )
        assertTrue(generated.contains("""Bunting.instance.int("n", 20L)"""))
    }

    @Test
    fun dateDefaultParsesToInstantElseEpoch() {
        val parsed = BuntingCodeGenerator.generate(
            """{"flags":{"d":{"type":"date","development":{"default":"2025-12-31T23:59:59Z"}}}}""",
            "p",
        )
        assertTrue(parsed.contains("""Instant.parse("2025-12-31T23:59:59Z")"""))
        val invalid = BuntingCodeGenerator.generate(
            """{"flags":{"d":{"type":"date","development":{"default":"not-a-date"}}}}""",
            "p",
        )
        assertTrue(invalid.contains("Instant.EPOCH"))
    }

    @Test
    fun jsonFlagsUseJsonDataWithoutDefault() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"layout/home":{"type":"json","development":{"default":"{}"}}}}""",
            "p",
        )
        assertTrue(generated.contains("public val home: ByteArray?"))
        assertTrue(generated.contains("""bunting.jsonData("layout/home")"""))
    }

    @Test
    fun stringDefaultsAreEscaped() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"s":{"type":"string","development":{"default":"say \"hi\" for ${'$'}5"}}}}""",
            "p",
        )
        assertTrue(generated.contains("\\\"hi\\\""))
        assertTrue(generated.contains("\\$5"))
    }

    @Test
    fun unknownTypeFallsBackToStringAccessor() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"x":{"type":"mystery","development":{"default":"v"}}}}""",
            "p",
        )
        assertTrue(generated.contains("public val Bunting.x: String"))
    }

    @Test
    fun deprecatedFlagGetsAnnotation() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"old":{"type":"bool","deprecated":true,"development":{"default":false}}}}""",
            "p",
        )
        assertTrue(generated.contains("""@Deprecated("Flag archived")"""))
    }

    @Test
    fun legacyDefaultKeysAreTolerated() {
        val generated = BuntingCodeGenerator.generate(
            """{"flags":{"a":{"type":"bool","development":{"defaultdefault":true}},"b":{"type":"int","development":{"defaultValue":7}}}}""",
            "p",
        )
        assertTrue(generated.contains("""Bunting.instance.bool("a", true)"""))
        assertTrue(generated.contains("""Bunting.instance.int("b", 7L)"""))
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
