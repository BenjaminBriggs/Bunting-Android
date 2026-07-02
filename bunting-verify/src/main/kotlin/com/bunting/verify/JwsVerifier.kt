package com.bunting.verify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Verifies a detached JWS (RFC 7797, unencoded payload) over the exact config
 * bytes. The signing input is `ASCII(protectedHeader) + "." + <config bytes>`,
 * and the compact detached form is `<protectedHeader>..<signature>`.
 */
object JwsVerifier {

    fun verifyDetached(jws: String, payload: ByteArray, publicKeys: List<PublicKeyInfo>) {
        // Keep empty segments: detached compact form is `header..signature`.
        val parts = jws.split(".")
        if (parts.size != 3) throw JwsVerificationException.MalformedJws()
        val protectedSegment = parts[0]

        val headerBytes = base64UrlDecode(protectedSegment) ?: throw JwsVerificationException.MalformedJws()
        val header = try {
            Json.parseToJsonElement(headerBytes.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw JwsVerificationException.MalformedJws()
        }

        // RFC 7797: header validation runs before kid lookup or signature verification.
        val alg = header["alg"]?.jsonPrimitive?.contentOrNull
        if (alg != "RS256") throw JwsVerificationException.UnsupportedAlgorithm(alg)

        val b64 = header["b64"]?.jsonPrimitive?.booleanOrNull
        if (b64 != false) throw JwsVerificationException.InvalidB64Parameter()

        // RFC 7515 §4.1.11: reject a JWS whose crit names an unknown extension.
        // This verifier understands only "b64", so crit must be exactly ["b64"].
        val crit = header["crit"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
        if (crit == null || crit.isEmpty() || crit.any { it != "b64" }) {
            throw JwsVerificationException.UnsupportedCriticalParameters()
        }

        val kid = header["kid"]?.jsonPrimitive?.contentOrNull ?: throw JwsVerificationException.MalformedJws()
        val keyInfo = publicKeys.firstOrNull { it.kid == kid } ?: throw JwsVerificationException.UnknownKid(kid)
        val publicKey = parsePublicKey(keyInfo.pem)

        // Signing input binds to the EXACT fetched payload bytes.
        val signingInput = protectedSegment.toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload

        val signature = base64UrlDecode(parts[2])
        if (signature == null || signature.isEmpty()) throw JwsVerificationException.MalformedJws()

        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(signingInput)
            verify(signature)
        }
        if (verified == false) throw JwsVerificationException.SignatureMismatch()
    }

    /** Base64url decode (RFC 4648 §5), tolerating missing padding. */
    private fun base64UrlDecode(input: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(input)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parsePublicKey(pem: String): java.security.PublicKey {
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("-----END RSA PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = try {
            Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            throw JwsVerificationException.InvalidPublicKey()
        }
        return try {
            // Admin emits SPKI ("BEGIN PUBLIC KEY"); X509EncodedKeySpec consumes SPKI DER.
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        } catch (_: Exception) {
            throw JwsVerificationException.InvalidPublicKey()
        }
    }
}
