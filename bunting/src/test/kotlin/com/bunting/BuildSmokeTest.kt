package com.bunting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BuildSmokeTest {
    @Test
    fun bundleVersionIsPinned() {
        assertEquals("2026-10-17.2", BuildInfo.CONFORMANCE_BUNDLE_VERSION)
    }
}
