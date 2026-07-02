package com.bunting.storage

/**
 * Observability hooks for [ConfigStore], mirroring the Swift SDK's
 * `BuntingEventsDelegate` fetch/verify/load callbacks. All methods default to
 * no-ops so implementers override only what they need. Fired synchronously at the
 * same semantic points as the Swift `ConfigStore` delegate calls.
 */
interface ConfigStoreListener {
    /** A network fetch of the config endpoint is starting. */
    fun didStartFetch(url: String) {}

    /** A fetch finished: [success] true on a verified-and-cached 200, false on failure. */
    fun didCompleteFetch(success: Boolean, error: Throwable?) {}

    /** A detached-JWS verification completed with the given [success]. */
    fun didVerifySignature(success: Boolean) {}

    /** A config became active from the on-disk cache or the bundled seed. */
    fun didLoadCachedConfig(version: String) {}
}
