package com.bunting.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bunting.Bunting
import com.bunting.model.BuntingEnvironment
import com.bunting.model.ConfigSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BuntingAndroidConfigureTest {

    @Test
    fun configuresSeedOnlyWithoutNetwork() = runBlocking {
        // The bootstrap asset points at a dead endpoint, so the initial refresh fails
        // and the bundled seed (BuntingConfig.json) stays active.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = Bunting.configure(
            context = ApplicationProvider.getApplicationContext<Context>(),
            environment = BuntingEnvironment.PRODUCTION,
            autoRefreshOnForeground = false,
            scope = scope,
        )
        client.awaitInitialLoad()

        assertTrue(Bunting.isConfigured)
        val state = client.state.value
        assertNotNull(state.configuration)
        assertEquals(ConfigSource.SEED, state.configSource)
        assertEquals("seed-1", state.configVersion)
        assertFalse(state.signatureVerified)
        // The seed flag resolves through the full stack.
        assertEquals(true, client.bool("f/enabled", false))
        scope.cancel()
    }
}
