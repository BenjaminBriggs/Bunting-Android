package com.bunting

import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.StaticContextProvider
import com.bunting.model.BuntingEnvironment
import com.bunting.model.ConfigSource
import com.bunting.storage.BootstrapConfig
import com.bunting.storage.FetchPolicy
import com.bunting.storage.OverrideValue
import com.bunting.verify.PublicKeyInfo
import com.bunting.verify.SignatureTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow

class BuntingClientTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private lateinit var scope: CoroutineScope

    @Volatile private var currentConfig: String = ""

    private val evalConfig = """
        {"schema_version":1,"config_version":"cfg-1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo",
         "flags":{
           "f/gate":{"type":"bool","development":{"default":false},"beta":{"default":false},
             "production":{"default":false,"variants":[{"type":"conditional","order":1,"value":true,
               "conditions":[{"type":"platform","operator":"in","values":["android"]}]}]}},
           "m/d":{"type":"double","development":{"default":2},"beta":{"default":2},"production":{"default":2}},
           "s/tier":{"type":"string","development":{"default":"dev"},"beta":{"default":"beta"},"production":{"default":"prod"}},
           "j/layout":{"type":"json","development":{"default":"{}"},"beta":{"default":"{}"},"production":{"default":"{\"a\":1}"}}
         },"tests":{},"rollouts":{}}
    """.trimIndent()

    private fun deprecatedConfig(version: String) = """
        {"schema_version":1,"config_version":"$version","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo",
         "flags":{"d/x":{"type":"bool","deprecated":true,"development":{"default":false},"beta":{"default":false},"production":{"default":false}}},
         "tests":{},"rollouts":{}}
    """.trimIndent()

    private class Recorder : BuntingEventsListener {
        val events = mutableListOf<String>()
        override fun didStartFetch(url: String) { events += "start" }
        override fun didCompleteFetch(success: Boolean, error: Throwable?) { events += "complete:$success" }
        override fun didVerifySignature(success: Boolean) { events += "verify:$success" }
        override fun didLoadCachedConfig(version: String) { events += "loaded:$version" }
        override fun didFallbackToDefault(flagKey: String) { events += "fallback:$flagKey" }
        override fun didChangeOverride(flagKey: String, value: OverrideValue?) { events += "override:$flagKey:${value != null}" }
        override fun didReadDeprecatedFlag(flagKey: String) { events += "deprecated:$flagKey" }
    }

    private fun pem(kp: KeyPair) =
        "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(kp.public.encoded)}\n-----END PUBLIC KEY-----\n"

    private fun sign(payload: ByteArray): String {
        val header = """{"alg":"RS256","b64":false,"crit":["b64"],"kid":"k1"}"""
        val protected = urlEncoder.encodeToString(header.toByteArray(Charsets.UTF_8))
        val signingInput = protected.toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keyPair.private); update(signingInput); sign() }
        return "$protected..${urlEncoder.encodeToString(sig)}"
    }

    @BeforeEach
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        server.stop(0)
    }

    /** Serves a freshly signed 200 for the current config, no ETag (so every GET is a full fetch). */
    private fun serveSigned() {
        server.createContext("/config.json") { ex ->
            val bytes = currentConfig.toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, sign(bytes))
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun endpoint() = "http://127.0.0.1:${server.address.port}/config.json"

    private fun bootstrap() = BootstrapConfig(
        appIdentifier = "demo",
        endpointUrl = endpoint(),
        publicKeys = listOf(PublicKeyInfo("k1", pem(keyPair))),
        fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
    )

    private fun options(
        dir: File,
        environment: BuntingEnvironment = BuntingEnvironment.PRODUCTION,
        seedLoader: (() -> ByteArray?)? = null,
    ) = BuntingOptions(
        environment = environment,
        bootstrap = bootstrap(),
        dataDir = dir,
        contextProvider = StaticContextProvider(
            EvaluationContext(
                platform = "android", osVersion = "15.0.0", appVersion = "2.5.0",
                buildNumber = "100", deviceModel = "Pixel 8", region = "US",
                language = "en", deviceClass = "phone", reservedAttributes = emptyMap(),
            )
        ),
        seedLoader = seedLoader,
        scope = scope,
    )

    private fun client(options: BuntingOptions) = BuntingClient(options, MutableStateFlow(BuntingState.EMPTY))

    @Test
    fun evaluationOrderAndWrongTypeOverrideFallthrough(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad()

        // Conditional variant matches platform=android in production.
        assertEquals(true, c.bool("f/gate", false))
        assertEquals("prod", c.string("s/tier", "x"))

        // A correctly-typed override wins.
        c.setOverride("f/gate", OverrideValue.BoolValue(false))
        assertEquals(false, c.bool("f/gate", true))

        // A wrong-typed override is ignored — evaluation resumes.
        c.setOverride("f/gate", OverrideValue.StringValue("nope"))
        assertEquals(true, c.bool("f/gate", false))
    }

    @Test
    fun doubleCoercionEndToEnd(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad()
        // A whole-number default `2` on a double-typed flag decodes as the double 2.0.
        assertEquals(2.0, c.double("m/d", 0.0))
        // Values never decode by shape: the double flag does not read as an int.
        assertEquals(-1L, c.int("m/d", -1L))
    }

    @Test
    fun overridesFollowTheDeclaredFlagType(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad()
        assertEquals("""{"a":1}""", c.jsonData("j/layout")?.decodeToString())

        // A string override on a json flag is JSON, exactly like a served value.
        c.setOverride("j/layout", OverrideValue.StringValue("[1]"))
        assertEquals("[1]", c.jsonData("j/layout")?.decodeToString())
        assertEquals("fallback", c.string("j/layout", "fallback"))

        // A string override on a string flag stays a string, so it never reads as JSON.
        c.setOverride("s/tier", OverrideValue.StringValue("[1]"))
        assertEquals("[1]", c.string("s/tier", "x"))
        assertNull(c.jsonData("s/tier"))

        // A flag the config doesn't have falls back to the caller default's type.
        c.setOverride("not/in/config", OverrideValue.StringValue("[2]"))
        assertEquals("[2]", c.jsonData("not/in/config")?.decodeToString())
    }

    @Test
    fun overrideAndEnvironmentMutationsWithEvents(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad()
        val recorder = Recorder()
        c.eventsListener = recorder

        c.setOverride("s/tier", OverrideValue.StringValue("ovr"))
        assertEquals("ovr", c.string("s/tier", "x"))
        c.clearOverride("s/tier")
        assertEquals("prod", c.string("s/tier", "x"))

        c.setOverride("s/tier", OverrideValue.StringValue("ovr2"))
        c.clearAllOverrides()
        assertEquals("prod", c.string("s/tier", "x"))

        // Environment switch surfaces per-environment defaults (memo keyed by env).
        c.setEnvironment(BuntingEnvironment.BETA)
        assertEquals("beta", c.string("s/tier", "x"))
        c.setEnvironment(BuntingEnvironment.DEVELOPMENT)
        assertEquals("dev", c.string("s/tier", "x"))

        assertEquals(
            listOf("override:s/tier:true", "override:s/tier:false", "override:s/tier:true", "override:s/tier:false"),
            recorder.events,
        )
    }

    @Test
    fun deprecatedFiresOncePerConfigVersion(@TempDir dir: File) = runBlocking {
        currentConfig = deprecatedConfig("dep-1")
        serveSigned()
        val recorder = Recorder()
        val c = client(options(dir))
        c.eventsListener = recorder
        c.awaitInitialLoad()

        c.bool("d/x", false)
        c.bool("d/x", false) // second read hits memo — no second event for this version
        assertEquals(1, recorder.events.count { it == "deprecated:d/x" })

        // A new config version resets the once-per-version bookkeeping.
        currentConfig = deprecatedConfig("dep-2")
        c.refresh()
        c.bool("d/x", false)
        assertEquals(2, recorder.events.count { it == "deprecated:d/x" })
    }

    @Test
    fun absentFlagFiresFallback(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val recorder = Recorder()
        val c = client(options(dir))
        c.eventsListener = recorder
        c.awaitInitialLoad()
        assertEquals(true, c.bool("missing/flag", true))
        assertEquals(1, recorder.events.count { it == "fallback:missing/flag" })
    }

    @Test
    fun seedFallbackSurfacesSeedState(@TempDir dir: File) = runBlocking {
        // Empty server (404 on /config.json) → refresh fails → seed remains active.
        server.start()
        val c = client(options(dir, seedLoader = { evalConfig.toByteArray(Charsets.UTF_8) }))
        c.awaitInitialLoad()
        val s = c.state.value
        assertNotNull(s.configuration)
        assertEquals(ConfigSource.SEED, s.configSource)
        assertFalse(s.signatureVerified)
        assertEquals("cfg-1", s.configVersion)
    }

    @Test
    fun listenerFiresInOrderOnExplicitRefresh(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad() // initial-load events not recorded (listener not yet set)
        val recorder = Recorder()
        c.eventsListener = recorder
        c.refresh()
        assertEquals(listOf("start", "verify:true", "complete:true"), recorder.events)
    }

    @Test
    fun userFingerprintNullUntilConfigThenStable(@TempDir dir: File) = runBlocking {
        currentConfig = evalConfig
        serveSigned()
        val c = client(options(dir))
        c.awaitInitialLoad()
        val fp = c.userFingerprint
        assertNotNull(fp)
        assertEquals("cfg-1", fp!!.substringBeforeLast('.'))
    }
}
