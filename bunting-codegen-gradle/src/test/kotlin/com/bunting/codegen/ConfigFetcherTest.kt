package com.bunting.codegen

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class ConfigFetcherTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val config = """{"schema_version":1,"config_version":"2026-07-03.1","flags":{}}"""

    @BeforeEach
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun pem() =
        "-----BEGIN PUBLIC KEY-----\n${Base64.getEncoder().encodeToString(keyPair.public.encoded)}\n-----END PUBLIC KEY-----\n"

    private fun sign(payload: ByteArray, kid: String = "k1"): String {
        val header = """{"alg":"RS256","b64":false,"crit":["b64"],"kid":"$kid"}"""
        val protected = urlEncoder.encodeToString(header.toByteArray(Charsets.UTF_8))
        val signingInput = protected.toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private); update(signingInput); sign()
        }
        return "$protected..${urlEncoder.encodeToString(sig)}"
    }

    private fun bootstrap(kid: String = "k1") = """
        {"app_identifier":"demo",
         "endpoint_url":"http://127.0.0.1:${server.address.port}/config.json",
         "public_keys":[{"kid":"$kid","pem":"${pem().replace("\n", "\\n")}"}],
         "fetch_policy":{"min_interval_seconds":0,"hard_ttl_days":7}}
    """.trimIndent()

    private fun serve(body: ByteArray, jws: String?, viaHeader: Boolean) {
        server.createContext("/config.json") { exchange ->
            if (viaHeader && jws != null) {
                exchange.responseHeaders.add(ConfigFetcher.SIGNATURE_HEADER, jws)
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        if (viaHeader == false && jws != null) {
            val sigBytes = jws.toByteArray(Charsets.UTF_8)
            server.createContext("/config.json.sig") { exchange ->
                exchange.sendResponseHeaders(200, sigBytes.size.toLong())
                exchange.responseBody.use { it.write(sigBytes) }
            }
        }
    }

    @Test
    fun fetchesAndVerifiesViaSignatureHeader() {
        val body = config.toByteArray(Charsets.UTF_8)
        serve(body, sign(body), viaHeader = true)
        assertArrayEquals(body, ConfigFetcher.fetch(bootstrap()))
    }

    @Test
    fun fallsBackToSigSibling() {
        val body = config.toByteArray(Charsets.UTF_8)
        serve(body, sign(body), viaHeader = false)
        assertArrayEquals(body, ConfigFetcher.fetch(bootstrap()))
    }

    @Test
    fun tamperedPayloadFails() {
        val body = config.toByteArray(Charsets.UTF_8)
        val tampered = config.replace("2026-07-03.1", "9999-01-01.1").toByteArray(Charsets.UTF_8)
        serve(tampered, sign(body), viaHeader = true)
        val error = assertThrows(IllegalStateException::class.java) { ConfigFetcher.fetch(bootstrap()) }
        assertTrue(error.message!!.contains("signature"))
    }

    @Test
    fun unknownKidFails() {
        val body = config.toByteArray(Charsets.UTF_8)
        serve(body, sign(body, kid = "other"), viaHeader = true)
        val error = assertThrows(IllegalStateException::class.java) { ConfigFetcher.fetch(bootstrap()) }
        assertTrue(error.message!!.contains("kid"))
    }

    @Test
    fun missingSignatureFails() {
        serve(config.toByteArray(Charsets.UTF_8), jws = null, viaHeader = true)
        assertThrows(Exception::class.java) { ConfigFetcher.fetch(bootstrap()) }
    }

    @Test
    fun http404Fails() {
        // No context registered — the JDK server answers 404.
        val error = assertThrows(Exception::class.java) { ConfigFetcher.fetch(bootstrap()) }
        assertTrue(error.message!!.contains("404"))
    }

    @Test
    fun rejectsNonConfigJsonEndpoint() {
        val bad = bootstrap().replace("/config.json", "/other.txt")
        val error = assertThrows(IllegalStateException::class.java) { ConfigFetcher.fetch(bad) }
        assertTrue(error.message!!.contains("config.json"))
    }
}
