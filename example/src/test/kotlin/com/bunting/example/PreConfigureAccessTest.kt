package com.bunting.example

import com.bunting.Bunting
import com.bunting.example.flags.features
import com.bunting.example.flags.layout
import com.bunting.example.flags.store
import com.bunting.example.flags.ui
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Generated accessors must never throw before `Bunting.configure` runs: they read
 * through the facade's default-returning path and yield the seed's development
 * defaults baked in at codegen time. Each test class runs in its own JVM
 * (`forkEvery = 1`), so no other test has configured the singleton first.
 */
class PreConfigureAccessTest {

    @Test
    fun generatedAccessorsReturnCodegenDefaultsBeforeConfigure() {
        assertFalse(Bunting.isConfigured)

        assertEquals("Welcome to Bunting!", Bunting.ui.welcomeMessage)
        assertEquals(true, Bunting.ui.enableAnimations)
        assertFalse(Bunting.store.useNewPaywallDesign)
        assertEquals(20L, Bunting.store.subscriptionDiscount)
        assertEquals(1.0, Bunting.store.priceMultiplier)
        assertEquals(Instant.parse("2025-12-31T23:59:59Z"), Bunting.store.promoEndDate)
        assertEquals(0.25, Bunting.features.discountPercentage)
        // json accessors carry no codegen default: null until a config is loaded.
        assertNull(Bunting.layout.homeSections)

        assertFalse(Bunting.isConfigured)
    }
}
