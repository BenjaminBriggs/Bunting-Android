package com.bunting.storage

import com.bunting.model.ArtifactRejectedException
import com.bunting.model.ConfigSource
import com.bunting.verify.PublicKeyInfo
import com.bunting.verify.SignatureTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ConfigStoreTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    private val configJson =
        """{"schema_version":1,"config_version":"2026-07-02.1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo","flags":{},"tests":{},"rollouts":{}}"""

    private fun pem(kp: KeyPair): String =
        "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(kp.public.encoded)}\n-----END PUBLIC KEY-----\n"

    private fun sign(payload: ByteArray, kid: String): String {
        val header = """{"alg":"RS256","b64":false,"crit":["b64"],"kid":"$kid"}"""
        val protected = urlEncoder.encodeToString(header.toByteArray(Charsets.UTF_8))
        val signingInput = protected.toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload
        val sig = Signature.getInstance("SHA256withRSA").run { initSign(keyPair.private); update(signingInput); sign() }
        return "$protected..${urlEncoder.encodeToString(sig)}"
    }

    @BeforeEach
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    }

    @AfterEach
    fun tearDown() { server.stop(0) }

    private fun endpoint() = "http://127.0.0.1:${server.address.port}/config.json"

    private fun bootstrap(minIntervalSeconds: Int = 0) = BootstrapConfig(
        appIdentifier = "demo",
        endpointUrl = endpoint(),
        publicKeys = listOf(PublicKeyInfo("k1", pem(keyPair))),
        fetchPolicy = FetchPolicy(minIntervalSeconds = minIntervalSeconds, hardTtlDays = 7),
    )

    /** Authentic (correctly signed) but with a header the decoder rejects. */
    private val badHeaderJson = configJson.replace("\"schema_version\":1", "\"schema_version\":2")

    private class Recorder : ConfigStoreListener {
        val events = mutableListOf<String>()
        override fun didStartFetch(url: String) { events += "start" }
        override fun didCompleteFetch(success: Boolean, error: Throwable?) { events += "complete:$success" }
        override fun didVerifySignature(success: Boolean) { events += "verify:$success" }
        override fun didLoadCachedConfig(version: String) { events += "loaded:$version" }
    }

    /** Serves [body] (signed, ETag = [etag]) and records each request's If-None-Match. */
    private inner class SwitchableEndpoint {
        @Volatile var body: String = configJson
        @Volatile var etag: String = "\"v1\""
        val ifNoneMatch = java.util.Collections.synchronizedList(mutableListOf<String?>())

        fun start() {
            server.createContext("/config.json") { ex ->
                ifNoneMatch += ex.requestHeaders.getFirst("If-None-Match")
                val bytes = body.toByteArray(Charsets.UTF_8)
                ex.responseHeaders.add("ETag", etag)
                ex.responseHeaders.add(SignatureTransport.HEADER_NAME, sign(bytes, "k1"))
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            server.start()
        }
    }

    @Test
    fun rejectedFetchKeepsTheActiveConfigAndReportsFailure(@TempDir dir: File) = runBlocking {
        val endpoint = SwitchableEndpoint().apply { start() }
        val recorder = Recorder()
        val store = ConfigStore(bootstrap(), dir, listener = recorder)
        store.refresh()

        endpoint.body = badHeaderJson
        endpoint.etag = "\"v2\""
        recorder.events.clear()
        val error = assertThrows(ArtifactRejectedException::class.java) { runBlocking { store.refresh() } }
        assertEquals("unsupported schema_version 2", error.reason)
        // The failure is reported after a successful verification.
        assertEquals(listOf("start", "verify:true", "complete:false"), recorder.events)

        val state = store.getConfigState()
        assertEquals("2026-07-02.1", state.configuration!!.configVersion)
        assertEquals(ConfigSource.FETCHED, state.source)
        assertEquals("\"v1\"", state.etag) // the rejected artifact's ETag is not adopted

        // The rejected bytes never reached the cache.
        val reopened = ConfigStore(bootstrap(), dir)
        reopened.loadCachedConfigIfNeeded()
        assertEquals(ConfigSource.CACHE, reopened.getConfigState().source)
        assertEquals("2026-07-02.1", reopened.getConfigState().configuration!!.configVersion)
    }

    @Test
    fun rejectedForcedFetchDoesNotForceEveryLaterRefresh(@TempDir dir: File) = runBlocking {
        val endpoint = SwitchableEndpoint().apply { start() }
        var clock = 1_000_000L
        val store = ConfigStore(bootstrap(minIntervalSeconds = 60), dir, nowMillis = { clock })
        store.refresh() // first fetch is forced (no TTL record yet)

        endpoint.body = badHeaderJson
        endpoint.etag = "\"v2\""
        clock += 8 * 86_400_000L // past the 7-day hard TTL: a forced, unconditional GET
        assertThrows(ArtifactRejectedException::class.java) { runBlocking { store.refresh() } }

        clock += 120_000L // past the rate limit, well inside the TTL
        assertThrows(ArtifactRejectedException::class.java) { runBlocking { store.refresh() } }

        // Before the fix every refresh after a rejection was another forced full GET.
        assertEquals(listOf(null, null, "\"v1\""), endpoint.ifNoneMatch.toList())
    }

    @Test
    fun rejectedFetchKeepsTheRateLimitAcrossRestarts(@TempDir dir: File) = runBlocking {
        val endpoint = SwitchableEndpoint().apply { start() }
        var clock = 1_000_000L
        ConfigStore(bootstrap(minIntervalSeconds = 60), dir, nowMillis = { clock }).refresh()

        endpoint.body = badHeaderJson
        clock += 3_600_000L
        val store = ConfigStore(bootstrap(minIntervalSeconds = 60), dir, nowMillis = { clock })
        store.loadCachedConfigIfNeeded()
        assertThrows(ArtifactRejectedException::class.java) { runBlocking { store.refresh() } }
        assertEquals(2, endpoint.ifNoneMatch.size)

        // A relaunch moments later must not hit the endpoint again.
        clock += 10_000L
        val relaunched = ConfigStore(bootstrap(minIntervalSeconds = 60), dir, nowMillis = { clock })
        relaunched.loadCachedConfigIfNeeded()
        relaunched.refresh()
        assertEquals(2, endpoint.ifNoneMatch.size)
        assertEquals("2026-07-02.1", relaunched.getConfigState().configuration!!.configVersion)
    }

    @Test
    fun badEntriesDoNotRejectAFetchedArtifact(@TempDir dir: File) = runBlocking {
        val endpoint = SwitchableEndpoint()
        endpoint.body = configJson.replace(
            "\"flags\":{}",
            "\"flags\":{\"ok\":{\"type\":\"bool\",\"development\":{\"default\":true},\"beta\":{\"default\":true},\"production\":{\"default\":true}}," +
                "\"bad\":{\"type\":\"color\",\"development\":{\"default\":\"red\"},\"beta\":{\"default\":\"red\"},\"production\":{\"default\":\"red\"}}}",
        )
        endpoint.start()
        val recorder = Recorder()
        val store = ConfigStore(bootstrap(), dir, listener = recorder)
        store.refresh()
        assertEquals(listOf("start", "verify:true", "complete:true"), recorder.events)
        val flags = store.getConfigState().configuration!!.flags
        assertTrue(flags.containsKey("ok"))
        assertFalse(flags.containsKey("bad"))
    }

    @Test
    fun fetchesVerifiesAndCaches(@TempDir dir: File) = runBlocking {
        val bytes = configJson.toByteArray(Charsets.UTF_8)
        val jws = sign(bytes, "k1")
        server.createContext("/config.json") { ex ->
            val inm = ex.requestHeaders.getFirst("If-None-Match")
            if (inm == "\"v1\"") { ex.sendResponseHeaders(304, -1); ex.close(); return@createContext }
            ex.responseHeaders.add("ETag", "\"v1\"")
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()

        val store = ConfigStore(bootstrap(), dir)
        store.refresh()
        var state = store.getConfigState()
        assertNotNull(state.configuration)
        assertEquals(ConfigSource.FETCHED, state.source)
        assertTrue(state.signatureVerified)
        assertEquals("\"v1\"", state.etag)

        store.refresh() // sends If-None-Match, server 304s, config stays put
        state = store.getConfigState()
        assertEquals("2026-07-02.1", state.configuration!!.configVersion)

        val reopened = ConfigStore(bootstrap(), dir)
        reopened.loadCachedConfigIfNeeded()
        assertEquals(ConfigSource.CACHE, reopened.getConfigState().source)
    }

    @Test
    fun rejectsTamperedConfig(@TempDir dir: File) {
        val bytes = configJson.toByteArray(Charsets.UTF_8)
        val jws = sign(bytes, "k1")
        val tampered = configJson.replace("demo", "evil").toByteArray(Charsets.UTF_8)
        server.createContext("/config.json") { ex ->
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, tampered.size.toLong())
            ex.responseBody.use { it.write(tampered) }
        }
        server.start()
        val store = ConfigStore(bootstrap(), dir)
        assertThrows(Exception::class.java) { runBlocking { store.refresh() } }
        runBlocking { assertNull(store.getConfigState().configuration) }
    }

    @Test
    fun serverErrorReportsAFailedFetch(@TempDir dir: File) {
        server.createContext("/config.json") { ex -> ex.sendResponseHeaders(500, -1); ex.close() }
        server.start()
        val recorder = Recorder()
        val store = ConfigStore(bootstrap(), dir, listener = recorder)
        assertThrows(ConfigStoreException::class.java) { runBlocking { store.refresh() } }
        assertEquals(listOf("start", "complete:false"), recorder.events)
    }

    @Test
    fun networkErrorReportsAFailedFetch(@TempDir dir: File) {
        // A port that was just released, so the connection is refused.
        val closedPort = java.net.ServerSocket(0).use { it.localPort }
        val unreachable = bootstrap().copy(endpointUrl = "http://127.0.0.1:$closedPort/config.json")
        val recorder = Recorder()
        val store = ConfigStore(unreachable, dir, listener = recorder)
        assertThrows(Exception::class.java) { runBlocking { store.refresh() } }
        assertEquals(listOf("start", "complete:false"), recorder.events)
    }

    @Test
    fun notModifiedReportsASuccessfulFetch(@TempDir dir: File) = runBlocking {
        val endpoint = SwitchableEndpoint().apply { start() }
        val recorder = Recorder()
        val store = ConfigStore(bootstrap(), dir, listener = recorder)
        store.refresh()
        server.removeContext("/config.json")
        server.createContext("/config.json") { ex -> ex.sendResponseHeaders(304, -1); ex.close() }
        recorder.events.clear()
        store.refresh()
        assertEquals(listOf("start", "complete:true"), recorder.events)
        assertEquals(1, endpoint.ifNoneMatch.size)
    }
}
