package com.bunting.identity

import com.bunting.storage.KeyValueStore
import java.util.UUID

/**
 * Persistent per-install identity for deterministic bucketing. Documented divergence
 * from Swift: Android has no iCloud-sync equivalent, so identity is per-install.
 */
class BuntingIdentity(private val store: KeyValueStore) {
    private var cached: UUID? = null

    fun getLocalId(): UUID {
        cached?.let { return it }
        store.getString(KEY)?.let { raw ->
            runCatching { UUID.fromString(raw) }.getOrNull()?.let { cached = it; return it }
        }
        val fresh = UUID.randomUUID()
        store.putString(KEY, fresh.toString())
        cached = fresh
        return fresh
    }

    fun resetIdentity(): UUID {
        val fresh = UUID.randomUUID()
        store.putString(KEY, fresh.toString())
        cached = fresh
        return fresh
    }

    companion object {
        private const val KEY = "local_id"
    }
}
