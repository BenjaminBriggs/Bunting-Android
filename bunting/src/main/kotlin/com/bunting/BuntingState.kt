package com.bunting

import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.ConfigSource
import com.bunting.storage.OverrideValue
import java.util.UUID

/**
 * An immutable snapshot of the client's observable state, published on a single
 * [kotlinx.coroutines.flow.StateFlow]. Mirrors the Swift `Bunting` @Observable
 * properties collapsed into one value.
 */
data class BuntingState(
    val environment: BuntingEnvironment,
    val configuration: BuntingConfiguration?,
    val configVersion: String?,
    val publishedAt: String?,
    val signatureVerified: Boolean,
    val configSource: ConfigSource?,
    val lastFetchTimeMillis: Long?,
    val etag: String?,
    val localId: UUID?,
    val overrides: Map<String, OverrideValue>,
) {
    companion object {
        /** The state before any configuration has loaded (or before [Bunting.configure]). */
        val EMPTY = BuntingState(
            environment = BuntingEnvironment.PRODUCTION,
            configuration = null,
            configVersion = null,
            publishedAt = null,
            signatureVerified = false,
            configSource = null,
            lastFetchTimeMillis = null,
            etag = null,
            localId = null,
            overrides = emptyMap(),
        )
    }
}
