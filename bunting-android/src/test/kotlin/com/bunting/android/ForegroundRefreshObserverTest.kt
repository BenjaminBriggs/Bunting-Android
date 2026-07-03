package com.bunting.android

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.bunting.BuntingClient
import com.bunting.BuntingEventsListener
import com.bunting.BuntingOptions
import com.bunting.BuntingState
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.StaticContextProvider
import com.bunting.model.BuntingEnvironment
import com.bunting.storage.BootstrapConfig
import com.bunting.storage.FetchPolicy
import com.bunting.verify.PublicKeyInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Pure-JVM test (no Robolectric): onStart must launch a refresh on the client. */
class ForegroundRefreshObserverTest {

    @get:Rule val tmp = TemporaryFolder()

    private val fakeOwner = object : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw UnsupportedOperationException("unused")
    }

    @Test
    fun onStartTriggersRefresh() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val options = BuntingOptions(
            environment = BuntingEnvironment.PRODUCTION,
            bootstrap = BootstrapConfig(
                appIdentifier = "demo",
                endpointUrl = "http://127.0.0.1:1/config.json", // dead → refresh fails fast
                publicKeys = listOf(PublicKeyInfo("k1", "pem")),
                fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
            ),
            dataDir = tmp.newFolder("data"),
            contextProvider = StaticContextProvider(
                EvaluationContext(
                    platform = "android", osVersion = "15.0.0", appVersion = "1.0.0",
                    buildNumber = "1", deviceModel = "Pixel", region = "US",
                    language = "en", deviceClass = "phone", reservedAttributes = emptyMap(),
                )
            ),
            scope = scope,
        )
        val client = BuntingClient(options, MutableStateFlow(BuntingState.EMPTY))
        runBlocking { client.awaitInitialLoad() }

        val started = CountDownLatch(1)
        client.eventsListener = object : BuntingEventsListener {
            override fun didStartFetch(url: String) { started.countDown() }
        }

        ForegroundRefreshObserver(client, scope).onStart(fakeOwner)

        assertTrue("onStart should launch a refresh", started.await(5, TimeUnit.SECONDS))
        scope.cancel()
    }
}
