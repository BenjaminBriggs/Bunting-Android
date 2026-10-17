package com.bunting.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Checks that a fetched config is usable as the bundled seed before
 * `fetchBuntingConfig` writes it: it must be a JSON object with a
 * `schema_version` this SDK decodes and a `flags` object. Flags that codegen
 * would skip (C1: unknown type, or a development default that doesn't match the
 * declared type) and beta/production defaults that don't match are reported as
 * warnings, never errors.
 */
object SeedValidator {

    /** Mirrors the check in `com.bunting.model.BuntingConfiguration` (the plugin build cannot depend on `:bunting`). */
    val SUPPORTED_SCHEMA_VERSIONS: Set<Int> = setOf(1)

    /** Returns the number of flags that get a typed accessor, or throws [IllegalStateException] with a user-facing reason. */
    fun validate(payload: ByteArray, warn: (String) -> Unit): Int {
        val element = try {
            Json.parseToJsonElement(payload.toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IllegalStateException("fetched config is not valid JSON: ${e.message}")
        }
        val root = element as? JsonObject ?: throw IllegalStateException("fetched config is not a JSON object")

        val schema = (root["schema_version"] as? JsonPrimitive)
            ?.takeIf { it.isString == false }
            ?.content?.toIntOrNull()
            ?: throw IllegalStateException("fetched config has no integer schema_version")
        check(schema in SUPPORTED_SCHEMA_VERSIONS) {
            "fetched config has schema_version $schema, but this SDK only supports ${SUPPORTED_SCHEMA_VERSIONS.joinToString()}"
        }
        val flags = root["flags"] as? JsonObject
            ?: throw IllegalStateException("fetched config has no flags object")

        val accepted = BuntingCodeGenerator.parseFlags(flags, warn)
        for (spec in accepted) {
            val flag = flags.getValue(spec.key) as JsonObject
            for (environment in listOf("beta", "production")) {
                val default = (flag[environment] as? JsonObject)?.get("default")
                if (default == null) {
                    warn("flag \"${spec.key}\": the $environment environment has no default")
                } else if (BuntingCodeGenerator.defaultLiteral(spec.kind, default) == null) {
                    warn("flag \"${spec.key}\": $environment default $default does not match its type \"${spec.kind.wireName}\"")
                }
            }
        }
        return accepted.size
    }
}
