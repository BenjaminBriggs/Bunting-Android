package com.bunting.debug

import com.bunting.BuntingClient
import com.bunting.BuntingOptions
import com.bunting.BuntingState
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.StaticContextProvider
import com.bunting.model.BuntingEnvironment
import com.bunting.model.FlagType
import com.bunting.model.FlagValue
import com.bunting.storage.BootstrapConfig
import com.bunting.storage.FetchPolicy
import com.bunting.storage.OverrideValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FlagInfoTest {

    private lateinit var scope: CoroutineScope

    /** Seed with a root flag, three namespaces, a whole-number double, and a deprecated flag. */
    private val seed = """
        {"schema_version":1,"config_version":"seed-1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo",
         "flags":{
           "kill_switch":{"type":"bool","development":{"default":false},"beta":{"default":false},"production":{"default":false}},
           "store/promo":{"type":"string","description":"Promo banner","development":{"default":"dev promo"},"beta":{"default":"beta promo"},"production":{"default":"prod promo"}},
           "store/multiplier":{"type":"double","development":{"default":2},"beta":{"default":2},"production":{"default":2}},
           "ui/old_toggle":{"type":"bool","deprecated":true,"development":{"default":true},"beta":{"default":true},"production":{"default":true}},
           "layout/home":{"type":"json","development":{"default":"{\"a\":1}"},"beta":{"default":"{\"a\":1}"},"production":{"default":"{\"a\":1}"}}
         },"tests":{},"rollouts":{}}
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /** Seed-only client: the endpoint refuses connections, so refresh retains the seed. */
    private fun client(dir: File): BuntingClient = BuntingClient(
        BuntingOptions(
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
            seedLoader = { seed.toByteArray(Charsets.UTF_8) },
            scope = scope,
        ),
        MutableStateFlow(BuntingState.EMPTY),
    )

    private fun loadedFlags(dir: File): Pair<BuntingClient, List<FlagInfo>> {
        val client = client(dir)
        runBlocking { client.awaitInitialLoad() }
        return client to FlagInfo.fromState(client.state.value, client)
    }

    @Test
    fun enumeratesAllFlagsSortedWithNamespaces(@TempDir dir: File) {
        val (_, flags) = loadedFlags(dir)
        assertEquals(listOf("kill_switch", "layout/home", "store/multiplier", "store/promo", "ui/old_toggle"), flags.map { it.key })
        assertEquals(listOf("", "layout", "store", "store", "ui"), flags.map { it.namespace })
        assertEquals("kill_switch", flags.first { it.key == "kill_switch" }.displayName)
        assertEquals("promo", flags.first { it.key == "store/promo" }.displayName)
    }

    @Test
    fun wholeNumberDoubleRendersAsDouble(@TempDir dir: File) {
        val (_, flags) = loadedFlags(dir)
        val multiplier = flags.first { it.key == "store/multiplier" }
        assertEquals(FlagType.DOUBLE, multiplier.type)
        assertEquals("2.0", multiplier.effectiveValue)
        assertEquals("2", multiplier.defaultValue)
    }

    @Test
    fun overrideFlipsSourceBadgeAndValue(@TempDir dir: File) {
        val (client, _) = loadedFlags(dir)
        client.setOverride("store/promo", OverrideValue.StringValue("overridden!"))
        awaitOverridesPersisted(dir)
        val flags = FlagInfo.fromState(client.state.value, client)
        val promo = flags.first { it.key == "store/promo" }
        assertEquals(FlagInfo.FlagSource.OVERRIDE, promo.source)
        assertTrue(promo.hasOverride)
        assertEquals("overridden!", promo.effectiveValue)
        assertEquals("dev promo", promo.defaultValue)
        val untouched = flags.first { it.key == "kill_switch" }
        assertEquals(FlagInfo.FlagSource.EVALUATED, untouched.source)
    }

    @Test
    fun deprecatedAndJsonFlagsSurface(@TempDir dir: File) {
        val (_, flags) = loadedFlags(dir)
        assertTrue(flags.first { it.key == "ui/old_toggle" }.deprecated)
        assertEquals("""{"a":1}""", flags.first { it.key == "layout/home" }.effectiveValue)
    }

    @Test
    fun groupedFiltersByKeyAndDescriptionCaseInsensitive(@TempDir dir: File) {
        val (_, flags) = loadedFlags(dir)
        val all = FlagInfo.grouped(flags, "")
        assertEquals(listOf("", "layout", "store", "ui"), all.keys.toList())
        assertEquals(2, all.getValue("store").size)

        val byKey = FlagInfo.grouped(flags, "MULTIPLIER")
        assertEquals(setOf("store"), byKey.keys)
        val byDescription = FlagInfo.grouped(flags, "banner")
        assertEquals(listOf("store/promo"), byDescription.getValue("store").map { it.key })
        val none = FlagInfo.grouped(flags, "zzz")
        assertTrue(none.isEmpty())
    }

    /** Drains the async override persist so @TempDir cleanup does not race the write. */
    private fun awaitOverridesPersisted(dir: File) {
        val file = File(dir, "overrides.json")
        val deadline = System.currentTimeMillis() + 5_000
        while (file.exists() == false && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
    }

    @Test
    fun emptyStateYieldsNoFlags(@TempDir dir: File) {
        val flags = FlagInfo.fromState(BuntingState.EMPTY, client(dir))
        assertTrue(flags.isEmpty())
    }
}
