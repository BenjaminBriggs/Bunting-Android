package com.bunting.evaluation

/**
 * Pure, headless-testable derivation logic shared with Plan 3's Android binding.
 * The Android provider reads primitives off Build/Configuration/UiModeManager and
 * delegates the contract-sensitive parts here so they are unit-tested without a device.
 */
object DeviceDerivation {
    /** Android `device_class`: tv when television-mode, tablet at sw600dp+, else phone. */
    fun deviceClass(smallestScreenWidthDp: Int, isTelevision: Boolean): String = when {
        isTelevision -> "tv"
        smallestScreenWidthDp >= 600 -> "tablet"
        else -> "phone"
    }

    /** Normalizes `Build.VERSION.RELEASE` (e.g. "15", "15.1") to `major.minor.patch`. */
    fun normalizeOsVersion(release: String): String {
        val digits = release.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }
        val major = digits.getOrNull(0) ?: "0"
        val minor = digits.getOrNull(1) ?: "0"
        val patch = digits.getOrNull(2) ?: "0"
        return "$major.$minor.$patch"
    }

    /** `Build.MANUFACTURER` lowercased (e.g. "Samsung" → "samsung"). */
    fun normalizeManufacturer(raw: String): String = raw.lowercase()
}
