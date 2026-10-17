package com.bunting.cli

import com.bunting.verify.SignatureTransport
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

class CliTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val configJson =
        """{"schema_version":1,"config_version":"2026-07-02.1","published_at":"2026-07-02T00:00:00.000Z","app_identifier":"demo","flags":{},"tests":{},"rollouts":{}}"""

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
    }

    @AfterEach
    fun tearDown() { server.stop(0) }

    private fun writeBootstrap(dir: File): File {
        val endpoint = "http://127.0.0.1:${server.address.port}/config.json"
        val doc = """
            {"app_identifier":"demo","endpoint_url":"$endpoint",
             "public_keys":[{"kid":"k1","pem":${quote(pem(keyPair))}}],
             "fetch_policy":{"min_interval_seconds":0,"hard_ttl_days":7}}
        """.trimIndent()
        return File(dir, "bunting_config.json").apply { writeText(doc) }
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test
    fun fetchesVerifiesDecodesAndSaves(@TempDir dir: File) {
        val bytes = configJson.toByteArray(Charsets.UTF_8)
        val jws = sign(bytes)
        server.createContext("/config.json") { ex ->
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        val bootstrap = writeBootstrap(dir)
        val out = File(dir, "out.json")
        val code = run(arrayOf(bootstrap.absolutePath, out.absolutePath))
        assertEquals(0, code)
        assertTrue(out.exists())
        assertEquals(configJson, out.readText())
    }

    @Test
    fun rejectsTamperedConfigWithSignatureExit(@TempDir dir: File) {
        val jws = sign(configJson.toByteArray(Charsets.UTF_8))
        val tampered = configJson.replace("demo", "evil").toByteArray(Charsets.UTF_8)
        server.createContext("/config.json") { ex ->
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, tampered.size.toLong())
            ex.responseBody.use { it.write(tampered) }
        }
        server.start()
        val bootstrap = writeBootstrap(dir)
        assertEquals(ExitCode.SIGNATURE_ERROR, run(arrayOf(bootstrap.absolutePath, File(dir, "o.json").absolutePath)))
    }

    private fun serveSigned(body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val jws = sign(bytes)
        server.createContext("/config.json") { ex ->
            ex.responseHeaders.add(SignatureTransport.HEADER_NAME, jws)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @Test
    fun rejectedHeaderReturnsDecodeExit(@TempDir dir: File) {
        serveSigned(configJson.replace("\"schema_version\":1", "\"schema_version\":2"))
        val out = File(dir, "out.json")
        assertEquals(ExitCode.DECODE_ERROR, run(arrayOf(writeBootstrap(dir).absolutePath, out.absolutePath)))
        assertFalse(out.exists())
    }

    @Test
    fun badEntriesStillDecodeAndSave(@TempDir dir: File) {
        // One flag of an unknown type is skipped, not fatal: the verified bytes are saved as-is.
        val body = configJson.replace(
            "\"flags\":{}",
            "\"flags\":{\"x\":{\"type\":\"color\",\"development\":{\"default\":\"red\"},\"beta\":{\"default\":\"red\"},\"production\":{\"default\":\"red\"}}}",
        )
        serveSigned(body)
        val out = File(dir, "out.json")
        assertEquals(0, run(arrayOf(writeBootstrap(dir).absolutePath, out.absolutePath)))
        assertEquals(body, out.readText())
    }

    @Test
    fun missingArgsReturnsUsageExit() {
        assertEquals(ExitCode.USAGE_OR_BOOTSTRAP_ERROR, run(emptyArray()))
    }

    @Test
    fun unreadableBootstrapReturnsBootstrapExit(@TempDir dir: File) {
        assertEquals(ExitCode.USAGE_OR_BOOTSTRAP_ERROR,
            run(arrayOf(File(dir, "does-not-exist.json").absolutePath)))
    }
}
