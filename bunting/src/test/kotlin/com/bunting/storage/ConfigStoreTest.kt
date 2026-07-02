package com.bunting.storage

import com.bunting.model.ConfigSource
import com.bunting.verify.PublicKeyInfo
import com.bunting.verify.SignatureTransport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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

    private fun bootstrap() = BootstrapConfig(
        appIdentifier = "demo",
        endpointUrl = endpoint(),
        publicKeys = listOf(PublicKeyInfo("k1", pem(keyPair))),
        fetchPolicy = FetchPolicy(minIntervalSeconds = 0, hardTtlDays = 7),
    )

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
}
