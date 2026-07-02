package com.bunting.storage

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ConfigStoreSeedTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    private val configJson =
        """{"schema_version":1,"config_version":"2026-07-02.1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo","flags":{},"tests":{},"rollouts":{}}"""
    private val seedJson =
        """{"schema_version":1,"config_version":"seed-1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo","flags":{},"tests":{},"rollouts":{}}"""

    private class Recorder : ConfigStoreListener {
        val events = mutableListOf<String>()
        override fun didStartFetch(url: String) { events += "start" }
        override fun didCompleteFetch(success: Boolean, error: Throwable?) { events += "complete:$success" }
        override fun didVerifySignature(success: Boolean) { events += "verify:$success" }
        override fun didLoadCachedConfig(version: String) { events += "loaded:$version" }
    }

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

    private fun bootstrap() = BootstrapConfig(
        appIdentifier = "demo",
        endpointUrl = endpoint(),
        publicKeys = listOf(PublicKeyInfo("k1", pem(keyPair))),
        fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
    )

    private fun serve200(bytes: ByteArray, jws: String) {
        server.createContext("/config.json") { ex ->
            ex.responseHeaders.add("ETag", "\"v1\"")
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @Test
    fun seedLoadsWhenNoCache(@TempDir dir: File) = runBlocking {
        val recorder = Recorder()
        val store = ConfigStore(
            bootstrap(), dir,
            seedLoader = { seedJson.toByteArray(Charsets.UTF_8) },
            listener = recorder,
        )
        store.loadCachedConfigIfNeeded()
        val state = store.getConfigState()
        assertNotNull(state.configuration)
        assertEquals("seed-1", state.configuration!!.configVersion)
        assertEquals(ConfigSource.SEED, state.source)
        assertFalse(state.signatureVerified) // the seed is unsigned
        assertEquals(listOf("loaded:seed-1"), recorder.events)
    }

    @Test
    fun cacheWinsOverSeed(@TempDir dir: File) = runBlocking {
        val bytes = configJson.toByteArray(Charsets.UTF_8)
        serve200(bytes, sign(bytes, "k1"))

        // First store fetches and persists to the cache directory.
        ConfigStore(bootstrap(), dir).refresh()

        // A fresh store over the same dir, offered a seed, must prefer the cache.
        val store = ConfigStore(bootstrap(), dir, seedLoader = { seedJson.toByteArray(Charsets.UTF_8) })
        store.loadCachedConfigIfNeeded()
        val state = store.getConfigState()
        assertEquals(ConfigSource.CACHE, state.source)
        assertEquals("2026-07-02.1", state.configuration!!.configVersion)
    }

    @Test
    fun corruptSeedIsIgnored(@TempDir dir: File) = runBlocking {
        val store = ConfigStore(
            bootstrap(), dir,
            seedLoader = { "{not valid json".toByteArray(Charsets.UTF_8) },
        )
        store.loadCachedConfigIfNeeded() // must not throw
        val state = store.getConfigState()
        assertNull(state.configuration)
        assertNull(state.source)
    }

    @Test
    fun listenerFiresInOrderOnHappyFetch(@TempDir dir: File) = runBlocking {
        val bytes = configJson.toByteArray(Charsets.UTF_8)
        serve200(bytes, sign(bytes, "k1"))
        val recorder = Recorder()
        val store = ConfigStore(bootstrap(), dir, listener = recorder)
        store.refresh()
        assertEquals(listOf("start", "verify:true", "complete:true"), recorder.events)
    }
}
