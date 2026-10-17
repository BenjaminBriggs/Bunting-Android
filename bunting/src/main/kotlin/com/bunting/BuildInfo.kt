package com.bunting

/** Build-time constants for the Bunting Android SDK core. */
object BuildInfo {
    // The conformance-vector bundle version this SDK is validated against.
    // The conformance runner asserts the vendored `manifest.json` matches it,
    // so a stale vendored bundle fails loudly. Sync with:
    // `cp -R ../bunting-admin/conformance/. bunting/src/test/resources/conformance/`.
    const val CONFORMANCE_BUNDLE_VERSION: String = "2026-07-02.2"
}
