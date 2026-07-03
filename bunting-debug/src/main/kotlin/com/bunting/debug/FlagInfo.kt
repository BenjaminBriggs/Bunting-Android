package com.bunting.debug

import com.bunting.BuntingClient
import com.bunting.BuntingState
import com.bunting.model.FlagType
import com.bunting.model.FlagValue
import java.time.Instant

/**
 * Pure presenter model for the debug screens: one row per flag, derived from a
 * [BuntingState] snapshot plus the client's typed accessors (so the rendered
 * effective value honors the override → memo → evaluator → default order).
 * Runtime enumeration of `configuration.flags` replaces Swift's generated
 * `allFlags` list — a documented divergence.
 */
data class FlagInfo(
    val key: String,
    val namespace: String,
    val displayName: String,
    val type: FlagType,
    val description: String?,
    val effectiveValue: String,
    val defaultValue: String,
    val hasOverride: Boolean,
    val source: FlagSource,
    val deprecated: Boolean,
) {
    enum class FlagSource { OVERRIDE, EVALUATED }

    companion object {
        /** One [FlagInfo] per flag in the loaded configuration, sorted by key. */
        fun fromState(state: BuntingState, client: BuntingClient): List<FlagInfo> {
            val configuration = state.configuration ?: return emptyList()
            return configuration.flags.keys.sorted().map { key ->
                val flag = configuration.flags.getValue(key)
                val default = flag.config(state.environment).defaultValue
                val hasOverride = state.overrides.containsKey(key)
                FlagInfo(
                    key = key,
                    namespace = namespaceOf(key),
                    displayName = if (key.contains('/')) key.substringAfter('/') else key,
                    type = flag.type,
                    description = flag.description,
                    effectiveValue = effectiveValue(flag.type, key, default, client),
                    defaultValue = render(default),
                    hasOverride = hasOverride,
                    source = if (hasOverride) FlagSource.OVERRIDE else FlagSource.EVALUATED,
                    deprecated = flag.deprecated,
                )
            }
        }

        /** Case-insensitive filter over key/description, grouped by namespace (sorted). */
        fun grouped(flags: List<FlagInfo>, query: String): Map<String, List<FlagInfo>> {
            val trimmed = query.trim()
            val filtered = if (trimmed.isEmpty()) flags else flags.filter { info ->
                info.key.contains(trimmed, ignoreCase = true) ||
                    info.description?.contains(trimmed, ignoreCase = true) == true
            }
            return filtered.groupBy { it.namespace }.toSortedMap()
        }

        internal fun namespaceOf(key: String): String =
            if (key.contains('/')) key.substringBefore('/') else ""

        /** Reads through the client so overrides and memoization apply. */
        private fun effectiveValue(type: FlagType, key: String, default: FlagValue, client: BuntingClient): String =
            when (type) {
                FlagType.BOOL -> client.bool(key, boolOf(default)).toString()
                FlagType.INT -> client.int(key, longOf(default)).toString()
                FlagType.DOUBLE -> client.double(key, doubleOf(default)).toString()
                FlagType.STRING -> client.string(key, stringOf(default))
                FlagType.DATE -> client.date(key, instantOf(default)).toString()
                FlagType.JSON -> client.jsonData(key)?.decodeToString() ?: render(default)
            }

        internal fun render(value: FlagValue): String = when (value) {
            is FlagValue.BoolValue -> value.value.toString()
            is FlagValue.IntValue -> value.value.toString()
            is FlagValue.DoubleValue -> value.value.toString()
            is FlagValue.StringValue -> value.value
            is FlagValue.DateValue -> value.value.toString()
            is FlagValue.JsonValue -> value.value
        }

        private fun boolOf(value: FlagValue) = (value as? FlagValue.BoolValue)?.value ?: false
        private fun longOf(value: FlagValue) = (value as? FlagValue.IntValue)?.value ?: 0L
        private fun doubleOf(value: FlagValue) = when (value) {
            is FlagValue.DoubleValue -> value.value
            is FlagValue.IntValue -> value.value.toDouble()
            else -> 0.0
        }
        private fun stringOf(value: FlagValue) = (value as? FlagValue.StringValue)?.value ?: ""
        private fun instantOf(value: FlagValue) = (value as? FlagValue.DateValue)?.value ?: Instant.EPOCH
    }
}
