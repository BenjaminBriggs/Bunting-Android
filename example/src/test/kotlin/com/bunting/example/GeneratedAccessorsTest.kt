package com.bunting.example

import com.bunting.Bunting
import com.bunting.BuntingState
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.StaticContextProvider
import com.bunting.example.flags.features
import com.bunting.example.flags.layout
import com.bunting.example.flags.store
import com.bunting.example.flags.ui
import com.bunting.model.BuntingEnvironment
import com.bunting.model.ConfigSource
import com.bunting.storage.BootstrapConfig
import com.bunting.storage.FetchPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant

/**
 * Proves the generated accessors resolve the bundled seed's development defaults
 * end-to-end through the real facade (pure JVM — no Android runtime needed).
 */
class GeneratedAccessorsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    private fun seedBytes(): ByteArray {
        // AGP unit tests run with the module directory as the working directory.
        val asset = File("src/main/assets/BuntingConfig.json")
        check(asset.isFile) { "seed asset not found at ${asset.absolutePath}" }
        return asset.readBytes()
    }

    private fun configure(dir: File) {
        Bunting.configure(
            com.bunting.BuntingOptions(
                environment = BuntingEnvironment.DEVELOPMENT,
                bootstrap = BootstrapConfig(
                    appIdentifier = "demo",
                    endpointUrl = "http://127.0.0.1:1/config.json",
                    publicKeys = emptyList(),
                    fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
                ),
                dataDir = dir,
                contextProvider = StaticContextProvider(
                    EvaluationContext(
                        platform = "android", osVersion = "15.0.0", appVersion = "1.0.0",
                        buildNumber = "1", deviceModel = "Pixel 8", region = "US",
                        language = "en", deviceClass = "phone", reservedAttributes = emptyMap(),
                    )
                ),
                seedLoader = { seedBytes() },
                scope = scope,
            )
        )
        runBlocking { Bunting.instance.awaitInitialLoad() }
    }

    @Test
    fun generatedAccessorsResolveSeedDevelopmentDefaults(@TempDir dir: File) {
        configure(dir)

        assertEquals("Welcome to Bunting!", Bunting.ui.welcomeMessage)
        assertEquals("#007AFF", Bunting.ui.themeColor)
        assertEquals(true, Bunting.ui.enableAnimations)
        assertFalse(Bunting.store.useNewPaywallDesign)
        assertEquals(20L, Bunting.store.subscriptionDiscount)
        assertEquals(1.0, Bunting.store.priceMultiplier)
        assertEquals("50% Off - Limited Time!", Bunting.store.promoBannerText)
        assertEquals(Instant.parse("2025-12-31T23:59:59Z"), Bunting.store.promoEndDate)
        assertEquals(100L, Bunting.features.maxUploadSize)
        assertEquals(50L, Bunting.features.maxItems)
        assertEquals(0.25, Bunting.features.discountPercentage)
        assertEquals(true, Bunting.features.showOnboarding)
        assertEquals(
            """{"sections":["featured","recent","recommended"]}""",
            Bunting.layout.homeSections?.decodeToString(),
        )
    }

    @Test
    fun uiStatePresenterMapsSeedState(@TempDir dir: File) {
        configure(dir)
        val uiState = ExampleUiState.fromState(Bunting.state.value)
        assertEquals("2025-01-15.1", uiState.configVersion)
        assertEquals("bundled seed", uiState.source)
        assertFalse(uiState.signatureVerified)
        assertEquals("development", uiState.environment)

        assertEquals("not loaded", ExampleUiState.fromState(BuntingState.EMPTY).configVersion)
        assertEquals("none", ExampleUiState.fromState(BuntingState.EMPTY).source)
        // Silence unused warning for ConfigSource import used above.
        assertEquals("bundled seed", ExampleUiState.fromState(Bunting.state.value.copy(configSource = ConfigSource.SEED)).source)
    }
}
