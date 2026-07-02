package com.bunting.evaluation

import com.bunting.model.BuntingEnvironment
import com.bunting.model.FlagValue

/** Memoizes evaluation results; invalidated when config, overrides, or context change. */
class MemoizationCache {
    data class CacheKey(
        val flagKey: String,
        val environment: BuntingEnvironment,
        val contextHash: Int,
        val overridesVersion: Int,
        val configVersion: String,
    )

    private val cache = HashMap<CacheKey, FlagValue>()

    fun get(key: CacheKey): FlagValue? = cache[key]
    fun set(key: CacheKey, value: FlagValue) { cache[key] = value }

    /** Drops every cached entry for a single flag (across environments/versions). */
    fun invalidate(flagKey: String) { cache.keys.removeAll { it.flagKey == flagKey } }

    fun invalidateAll() = cache.clear()
}
