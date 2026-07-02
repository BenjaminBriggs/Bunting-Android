package com.bunting.verify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class JwsVerifierTest {

    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

    /** Builds a valid detached JWS (b64:false, crit:["b64"], RS256) over [payload]. */
    private fun sign(payload: ByteArray, kid: String, privateKey: java.security.PrivateKey): String {
        val header = buildJsonObject {
            put("alg", JsonPrimitive("RS256"))
            put("b64", JsonPrimitive(false))
            put("crit", Json.parseToJsonElement("""["b64"]"""))
            put("kid", JsonPrimitive(kid))
        }
        val protected = urlEncoder.encodeToString(header.toString().toByteArray(Charsets.UTF_8))
        val signingInput = protected.toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload
        val sig = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey); update(signingInput); sign()
        }
        return "$protected..${urlEncoder.encodeToString(sig)}"
    }

    private fun pemOf(publicKey: java.security.PublicKey): String {
        val b64 = Base64.getEncoder().encodeToString(publicKey.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
    }

    @Test
    fun verifiesAValidDetachedJws() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = """{"hello":"world"}""".toByteArray(Charsets.UTF_8)
        val jws = sign(payload, "k1", kp.private)
        JwsVerifier.verifyDetached(jws, payload, listOf(PublicKeyInfo("k1", pemOf(kp.public))))
    }

    @Test
    fun rejectsTamperedPayload() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val jws = sign("""{"a":1}""".toByteArray(Charsets.UTF_8), "k1", kp.private)
        assertThrows(JwsVerificationException.SignatureMismatch::class.java) {
            JwsVerifier.verifyDetached(jws, """{"a":2}""".toByteArray(Charsets.UTF_8),
                listOf(PublicKeyInfo("k1", pemOf(kp.public))))
        }
    }

    @Test
    fun rejectsUnknownKid() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val payload = "x".toByteArray()
        val jws = sign(payload, "k1", kp.private)
        assertThrows(JwsVerificationException.UnknownKid::class.java) {
            JwsVerifier.verifyDetached(jws, payload, listOf(PublicKeyInfo("other", pemOf(kp.public))))
        }
    }

    @Test
    fun rejectsMalformedJws() {
        assertThrows(JwsVerificationException.MalformedJws::class.java) {
            JwsVerifier.verifyDetached("only.two", ByteArray(0), emptyList())
        }
    }
}
