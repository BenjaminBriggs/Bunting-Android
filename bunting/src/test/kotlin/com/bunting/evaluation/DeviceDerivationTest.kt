package com.bunting.evaluation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceDerivationTest {

    @Test
    fun deviceClassFollowsContract() {
        assertEquals("tablet", DeviceDerivation.deviceClass(smallestScreenWidthDp = 720, isTelevision = false))
        assertEquals("tablet", DeviceDerivation.deviceClass(smallestScreenWidthDp = 600, isTelevision = false))
        assertEquals("phone", DeviceDerivation.deviceClass(smallestScreenWidthDp = 411, isTelevision = false))
        assertEquals("tv", DeviceDerivation.deviceClass(smallestScreenWidthDp = 960, isTelevision = true))
    }

    @Test
    fun osVersionNormalizesToThreeSegments() {
        assertEquals("15.0.0", DeviceDerivation.normalizeOsVersion("15"))
        assertEquals("15.1.0", DeviceDerivation.normalizeOsVersion("15.1"))
        assertEquals("15.1.2", DeviceDerivation.normalizeOsVersion("15.1.2"))
        assertEquals("0.0.0", DeviceDerivation.normalizeOsVersion(""))
    }

    @Test
    fun manufacturerIsLowercased() {
        assertEquals("samsung", DeviceDerivation.normalizeManufacturer("Samsung"))
        assertEquals("google", DeviceDerivation.normalizeManufacturer("Google"))
    }

    @Test
    fun staticProviderReturnsTheGivenContext() {
        val ctx = EvaluationContext(
            platform = "android", osVersion = "15.0.0", appVersion = "1.0.0",
            buildNumber = "1", deviceModel = "Pixel 8", region = "US",
            language = "en", deviceClass = "phone", reservedAttributes = mapOf("manufacturer" to "google"),
        )
        assertEquals(ctx, StaticContextProvider(ctx).current())
    }
}
