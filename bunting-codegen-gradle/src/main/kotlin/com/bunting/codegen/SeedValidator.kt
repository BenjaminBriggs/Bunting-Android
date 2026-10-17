package com.bunting.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * Checks that a fetched config is usable as the bundled seed before
 * `fetchBuntingConfig` writes it. It rejects exactly what the v1 reference decoder
 * (`bunting-admin/src/lib/artifact-decoder.ts`, `decodeArtifact`) rejects at header
 * level, with the same reasons: invalid JSON, a non-object root, a `schema_version`
 * other than 1, a missing or empty `config_version`, a `published_at` that isn't an
 * ISO-8601 UTC instant, a missing `app_identifier`, `flags` that isn't an object,
 * and `tests`/`rollouts` present but not objects. Below the header nothing is fatal:
 * flags the SDK (and so codegen) would skip are reported as warnings.
 */
object SeedValidator {

    /** Mirrors `SUPPORTED_SCHEMA_VERSION` in the reference decoder and the SDK's model. */
    const val SUPPORTED_SCHEMA_VERSION: Int = 1

    /** Returns the number of flags that get a typed accessor, or throws [IllegalStateException] with a user-facing reason. */
    fun validate(payload: ByteArray, warn: (String) -> Unit): Int {
        val flags = header(payload)
        return BuntingCodeGenerator.parseFlags(flags, warn).size
    }

    /** The header checks, in the reference decoder's order. Returns the `flags` object. */
    private fun header(payload: ByteArray): JsonObject {
        fun reject(reason: String): Nothing =
            throw IllegalStateException("fetched config is not a usable seed: $reason")

        val element: JsonElement = try {
            Json.parseToJsonElement(payload.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            reject("not valid JSON")
        }
        if (WireContract.hasOnlyValidLiterals(element) == false) reject("not valid JSON")
        val root = element as? JsonObject ?: reject("top level is not an object")

        if (WireContract.number(root["schema_version"]) != SUPPORTED_SCHEMA_VERSION.toDouble()) {
            reject("unsupported schema_version ${root["schema_version"] ?: "undefined"}")
        }
        if (WireContract.string(root["config_version"]).isNullOrEmpty()) reject("missing config_version")
        if (WireContract.instant(root["published_at"]) == null) reject("published_at is not an ISO-8601 UTC instant")
        if (WireContract.string(root["app_identifier"]) == null) reject("missing app_identifier")
        val flags = root["flags"] as? JsonObject ?: reject("flags must be an object")
        for (container in listOf("tests", "rollouts")) {
            val value = root[container]
            if (value != null && value !is JsonNull && value !is JsonObject) reject("$container must be an object")
        }
        return flags
    }
}
