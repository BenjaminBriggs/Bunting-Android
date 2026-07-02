package com.bunting

import com.bunting.storage.OverrideValue

/**
 * Observability hooks for the [BuntingClient] facade, mirroring the Swift
 * `BuntingEventsDelegate`. The first four methods forward the [com.bunting.storage.ConfigStoreListener]
 * fetch/verify/load events; the last three cover evaluation-time signals. All
 * methods default to no-ops and fire synchronously. Held as a plain nullable
 * reference on the client (documented divergence: no weak reference).
 */
interface BuntingEventsListener {
    /** A network fetch of the config endpoint is starting. */
    fun didStartFetch(url: String) {}

    /** A fetch finished: [success] true on a verified-and-cached 200, false on failure. */
    fun didCompleteFetch(success: Boolean, error: Throwable?) {}

    /** A detached-JWS verification completed with the given [success]. */
    fun didVerifySignature(success: Boolean) {}

    /** A config became active from the on-disk cache or the bundled seed. */
    fun didLoadCachedConfig(version: String) {}

    /** A flag read fell back to the caller default (no config/identity, or the key is absent). */
    fun didFallbackToDefault(flagKey: String) {}

    /** An override was set, changed, or cleared ([value] null when cleared). */
    fun didChangeOverride(flagKey: String, value: OverrideValue?) {}

    /** A deprecated (archived) flag was read; fires at most once per key per loaded config version. */
    fun didReadDeprecatedFlag(flagKey: String) {}
}
