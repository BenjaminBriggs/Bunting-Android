package com.bunting.android

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class AndroidContextProviderTest {

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultDeviceIsPhone() {
        val ctx = AndroidContextProvider(context()).current()
        assertEquals("android", ctx.platform)
        assertEquals("phone", ctx.deviceClass)
    }

    @Test
    @Config(qualifiers = "sw600dp")
    fun largeScreenIsTablet() {
        assertEquals("tablet", AndroidContextProvider(context()).current().deviceClass)
    }

    @Test
    fun televisionModeIsTv() {
        val context = context()
        val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        shadowOf(uiModeManager).setCurrentModeType(Configuration.UI_MODE_TYPE_TELEVISION)
        assertEquals("tv", AndroidContextProvider(context).current().deviceClass)
    }

    @Test
    fun localeYieldsLanguageAndRegion() {
        Locale.setDefault(Locale("EN", "us"))
        val ctx = AndroidContextProvider(context()).current()
        assertEquals("en", ctx.language)
        assertEquals("US", ctx.region)
    }

    @Test
    fun emptyCountryYieldsNullRegion() {
        Locale.setDefault(Locale("fr", ""))
        val ctx = AndroidContextProvider(context()).current()
        assertEquals("fr", ctx.language)
        assertNull(ctx.region)
    }

    @Test
    fun osVersionNormalizedAndManufacturerReserved() {
        val ctx = AndroidContextProvider(context()).current()
        assertEquals(3, ctx.osVersion.split('.').size)
        assertTrue(ctx.reservedAttributes.containsKey("manufacturer"))
    }
}
