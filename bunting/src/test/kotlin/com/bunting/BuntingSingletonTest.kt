package com.bunting

import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.StaticContextProvider
import com.bunting.model.BuntingEnvironment
import com.bunting.storage.BootstrapConfig
import com.bunting.storage.FetchPolicy
import com.bunting.verify.PublicKeyInfo
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress

class BuntingSingletonTest {

    private lateinit var server: HttpServer
    private lateinit var scope: CoroutineScope

    private val seedConfig =
        """{"schema_version":1,"config_version":"seed-1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo","flags":{},"tests":{},"rollouts":{}}"""

    @BeforeEach
    fun setUp() {
        Bunting.resetForTesting()
        // Empty server: /config.json 404s, so the background refresh fails fast and the
        // seed stays active — no signing needed for these wiring tests.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop(0)
        Bunting.resetForTesting()
    }

    private fun options(dir: File) = BuntingOptions(
        environment = BuntingEnvironment.PRODUCTION,
        bootstrap = BootstrapConfig(
            appIdentifier = "demo",
            endpointUrl = "http://127.0.0.1:${server.address.port}/config.json",
            publicKeys = listOf(PublicKeyInfo("k1", "pem")),
            fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
        ),
        dataDir = dir,
        contextProvider = StaticContextProvider(
            EvaluationContext(
                platform = "android", osVersion = "15.0.0", appVersion = "2.5.0",
                buildNumber = "100", deviceModel = "Pixel 8", region = "US",
                language = "en", deviceClass = "phone", reservedAttributes = emptyMap(),
            )
        ),
        seedLoader = { seedConfig.toByteArray(Charsets.UTF_8) },
        scope = scope,
    )

    @Test
    fun unconfiguredAccessorsReturnDefaults() {
        assertFalse(Bunting.isConfigured)
        assertEquals(true, Bunting.bool("x", true))
        assertEquals(42L, Bunting.int("x", 42L))
        assertEquals(1.5, Bunting.double("x", 1.5))
        assertEquals("d", Bunting.string("x", "d"))
        assertEquals(null, Bunting.jsonData("x"))
    }

    @Test
    fun instanceThrowsUntilConfigured() {
        assertThrows(IllegalStateException::class.java) { Bunting.instance }
    }

    @Test
    fun configureInstallsAndReplaces(@TempDir dir: File) {
        assertFalse(Bunting.isConfigured)
        val first = Bunting.configure(options(dir))
        assertTrue(Bunting.isConfigured)
        assertSame(first, Bunting.instance)

        val second = Bunting.configure(options(dir))
        assertNotSame(first, second)
        assertSame(second, Bunting.instance)
    }
}
