package com.bunting.codegen

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeedValidatorTest {

    private fun validate(json: String, warnings: MutableList<String> = mutableListOf()): Int =
        SeedValidator.validate(json.toByteArray(Charsets.UTF_8)) { warnings.add(it) }

    private fun reason(json: String): String =
        assertThrows(IllegalStateException::class.java) { validate(json) }.message!!

    private fun env(default: String) = """{"default":$default,"variants":[]}"""

    @Test
    fun goldenSeedIsUsable() {
        val seed = checkNotNull(javaClass.getResourceAsStream("/golden/BuntingConfig.json")).readBytes().decodeToString()
        val warnings = mutableListOf<String>()
        assertEquals(13, validate(seed, warnings))
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun rejectsInvalidJson() {
        assertTrue(reason("{not json").contains("not valid JSON"))
        assertTrue(reason("[1,2]").contains("not a JSON object"))
    }

    @Test
    fun rejectsMissingOrUnsupportedSchemaVersion() {
        assertTrue(reason("""{"flags":{}}""").contains("schema_version"))
        assertTrue(reason("""{"schema_version":"1","flags":{}}""").contains("schema_version"))
        val unsupported = reason("""{"schema_version":2,"flags":{}}""")
        assertTrue(unsupported.contains("schema_version 2") && unsupported.contains("supports 1"), unsupported)
    }

    @Test
    fun rejectsMissingFlagsObject() {
        assertTrue(reason("""{"schema_version":1}""").contains("flags"))
        assertTrue(reason("""{"schema_version":1,"flags":[]}""").contains("flags"))
    }

    @Test
    fun skippedFlagsAndMismatchedEnvironmentDefaultsAreWarningsOnly() {
        val warnings = mutableListOf<String>()
        val count = validate(
            """{"schema_version":1,"flags":{
                "good":{"type":"bool","development":${env("true")},"beta":${env("false")},"production":${env("false")}},
                "mystery":{"type":"color","development":${env("\"red\"")},"beta":${env("\"red\"")},"production":${env("\"red\"")}},
                "bad_dev":{"type":"int","development":${env("\"5\"")},"beta":${env("5")},"production":${env("5")}},
                "bad_prod":{"type":"int","development":${env("5")},"beta":${env("5")},"production":${env("5.5")}},
                "no_beta":{"type":"string","development":${env("\"a\"")},"production":${env("\"a\"")}}
            }}""",
            warnings,
        )
        assertEquals(3, count)
        assertEquals(
            listOf(
                "skipped flag \"bad_dev\": development default \"5\" does not match its type \"int\"",
                "skipped flag \"mystery\": unknown type \"color\"",
                "flag \"bad_prod\": production default 5.5 does not match its type \"int\"",
                "flag \"no_beta\": the beta environment has no default",
            ),
            warnings,
        )
    }
}
