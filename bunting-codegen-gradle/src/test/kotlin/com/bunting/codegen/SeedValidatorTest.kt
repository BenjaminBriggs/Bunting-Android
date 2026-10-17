package com.bunting.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Header rules and reasons mirror decodeArtifact() in bunting-admin/src/lib/artifact-decoder.ts. */
class SeedValidatorTest {

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream(name)) { "missing test resource $name" }.readBytes().decodeToString()

    private fun validate(json: String, warnings: MutableList<String> = mutableListOf()): Int =
        SeedValidator.validate(json.toByteArray(Charsets.UTF_8)) { warnings.add(it) }

    private fun rejection(json: String): String =
        assertThrows(IllegalStateException::class.java) { validate(json) }.message!!
            .removePrefix("fetched config is not a usable seed: ")

    private val validHeader = linkedMapOf(
        "schema_version" to "1",
        "config_version" to "\"2026-10-17.1\"",
        "published_at" to "\"2026-07-02T00:00:00.000Z\"",
        "app_identifier" to "\"demo\"",
        "flags" to "{}",
    )

    /** The valid header with [changes] applied; a null value removes the field. */
    private fun artifact(vararg changes: Pair<String, String?>): String {
        val fields = LinkedHashMap(validHeader)
        for ((key, value) in changes) if (value == null) fields.remove(key) else fields[key] = value
        return fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    @Test
    fun goldenSeedIsUsable() {
        val warnings = mutableListOf<String>()
        assertEquals(13, validate(resource("/golden/BuntingConfig.json"), warnings))
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun tolerantDecodingVectorIsAcceptedWithOneWarningPerSkippedFlag() {
        val vector = Json.parseToJsonElement(resource("/decoding/tolerant.json")).jsonObject
        val artifact = vector.getValue("artifact").jsonObject
        val skipped = vector.getValue("skipped_flags").jsonArray.map { it.jsonPrimitive.content }
        val warnings = mutableListOf<String>()
        assertEquals(artifact.getValue("flags").jsonObject.size - skipped.size, validate(artifact.toString(), warnings))
        assertEquals(skipped, warnings.map { it.substringAfter("skipped flag \"").substringBefore("\"") })
    }

    @Test
    fun rejectsInvalidJson() {
        assertEquals("not valid JSON", rejection("""{"schema_version":1,"flags":{"""))
        // kotlinx keeps bare words and malformed numbers as raw tokens; JSON.parse rejects them.
        assertEquals("not valid JSON", rejection(artifact("flags" to """{"a":tru}""")))
        assertEquals("not valid JSON", rejection(artifact("flags" to """{"a":{"type":"int","development":{"default":01}}}""")))
        assertEquals("not valid JSON", rejection(artifact("app_identifier" to "NaN")))
    }

    @Test
    fun rejectsANonObjectRoot() {
        assertEquals("top level is not an object", rejection("[1,2]"))
        assertEquals("top level is not an object", rejection("\"config\""))
    }

    @Test
    fun schemaVersionMustBeTheNumberOne() {
        assertEquals("unsupported schema_version 2", rejection(artifact("schema_version" to "2")))
        assertEquals("unsupported schema_version \"1\"", rejection(artifact("schema_version" to "\"1\"")))
        assertEquals("unsupported schema_version undefined", rejection(artifact("schema_version" to null)))
        // JSON.parse reads 1.0 as 1.
        assertDoesNotThrow { validate(artifact("schema_version" to "1.0")) }
    }

    @Test
    fun configVersionMustBeANonEmptyString() {
        assertEquals("missing config_version", rejection(artifact("config_version" to null)))
        assertEquals("missing config_version", rejection(artifact("config_version" to "\"\"")))
        assertEquals("missing config_version", rejection(artifact("config_version" to "7")))
    }

    @Test
    fun publishedAtMustBeAUtcInstant() {
        val reason = "published_at is not an ISO-8601 UTC instant"
        assertEquals(reason, rejection(artifact("published_at" to null)))
        assertEquals(reason, rejection(artifact("published_at" to "\"2026-07-02\"")))
        assertEquals(reason, rejection(artifact("published_at" to "\"2026-07-02T00:00:00+01:00\"")))
        assertEquals(reason, rejection(artifact("published_at" to "\"2026-07-02T00:00:00\"")))
        assertEquals(reason, rejection(artifact("published_at" to "\"2026-01-32T00:00:00Z\"")))
        assertEquals(reason, rejection(artifact("published_at" to "1751414400000")))
        assertDoesNotThrow { validate(artifact("published_at" to "\"2026-07-02T00:00:00Z\"")) }
        // Date.parse rolls 30 February over to 2 March rather than rejecting it.
        assertDoesNotThrow { validate(artifact("published_at" to "\"2026-02-30T00:00:00Z\"")) }
    }

    @Test
    fun appIdentifierMustBeAString() {
        assertEquals("missing app_identifier", rejection(artifact("app_identifier" to null)))
        assertEquals("missing app_identifier", rejection(artifact("app_identifier" to "null")))
        assertDoesNotThrow { validate(artifact("app_identifier" to "\"\"")) }
    }

    @Test
    fun containersMustBeObjects() {
        assertEquals("flags must be an object", rejection(artifact("flags" to null)))
        assertEquals("flags must be an object", rejection(artifact("flags" to "[]")))
        assertEquals("tests must be an object", rejection(artifact("tests" to "[]")))
        assertEquals("rollouts must be an object", rejection(artifact("rollouts" to "\"none\"")))
        assertDoesNotThrow { validate(artifact("tests" to "null", "rollouts" to "{}")) }
    }

    @Test
    fun problemsBelowTheHeaderAreWarningsOnly() {
        val envs = """"development":{"default":5},"beta":{"default":5},"production":{"default":5}"""
        val warnings = mutableListOf<String>()
        val count = validate(
            artifact(
                "flags" to """{"good":{"type":"int",$envs},"bad":{"type":"int","development":{"default":"5"}},"odd":"x"}""",
                "tests" to """{"broken":{"salt":7}}""",
                "rollouts" to """{"broken":{"salt":"s","percentage":150}}""",
            ),
            warnings,
        )
        assertEquals(1, count)
        assertEquals(
            listOf(
                "skipped flag \"bad\": development default \"5\" is not a valid int",
                "skipped flag \"odd\": it is not a JSON object",
            ),
            warnings,
        )
    }
}
