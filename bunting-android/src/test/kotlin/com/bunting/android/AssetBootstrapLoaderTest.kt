package com.bunting.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssetBootstrapLoaderTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadsValidBootstrap() {
        val bootstrap = AssetBootstrapLoader.load(context(), "bunting_config.json")
        assertEquals("demo", bootstrap.appIdentifier)
        assertTrue(bootstrap.endpointUrl.endsWith("config.json"))
    }

    @Test
    fun badEndpointThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            AssetBootstrapLoader.load(context(), "bunting_config_bad.json")
        }
    }

    @Test
    fun seedPresentReturnsBytesAndMissingReturnsNull() {
        assertNotNull(AssetBootstrapLoader.loadSeedBytes(context(), "BuntingConfig.json"))
        assertNull(AssetBootstrapLoader.loadSeedBytes(context(), "does_not_exist.json"))
    }
}
