package com.bunting.codegen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Fetches `config.json` from the bootstrap's endpoint and verifies its detached
 * RS256 JWS before returning the exact payload bytes — the Gradle-side mirror of
 * Swift's `FetchConfigPlugin`/`bunting-cli` flow. Self-contained (the plugin
 * build cannot depend on `:bunting-verify` without a build cycle); the strict
 * header checks match `JwsVerifier` order-for-order and the runtime SDK
 * re-verifies everything it loads, so a divergence here cannot ship bad config.
 */
object ConfigFetcher {

    const val SIGNATURE_HEADER = "x-bunting-signature"

    /** Returns the verified config bytes, or throws [IllegalStateException] with a user-facing reason. */
    fun fetch(bootstrapJson: String): ByteArray {
        val bootstrap = try {
            Json.parseToJsonElement(bootstrapJson).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("bootstrap is not valid JSON: ${e.message}")
        }
        val endpoint = (bootstrap["endpoint_url"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("bootstrap has no endpoint_url")
        check(endpoint.endsWith("config.json")) { "endpoint_url must end with config.json: $endpoint" }
        val publicKeys = (bootstrap["public_keys"] as? JsonArray)?.mapNotNull { element ->
            val key = element as? JsonObject ?: return@mapNotNull null
            val kid = (key["kid"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val pem = (key["pem"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            kid to pem
        }?.toMap().orEmpty()
        check(publicKeys.isEmpty() == false) { "bootstrap has no public_keys" }

        val (payload, headerSignature) = get(endpoint)
        val jws = headerSignature
            ?: get("$endpoint.sig").first.toString(Charsets.UTF_8).trim().ifEmpty {
                throw IllegalStateException("no $SIGNATURE_HEADER header and empty $endpoint.sig")
            }

        verifyDetached(jws, payload, publicKeys)
        return payload
    }

    /** GET returning (body, signature-header). Throws on non-200. */
    private fun get(url: String): Pair<ByteArray, String?> {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val code = connection.responseCode
            if (code != 200) throw IOException("GET $url returned HTTP $code")
            val body = connection.inputStream.use { it.readBytes() }
            return body to connection.getHeaderField(SIGNATURE_HEADER)
        } finally {
            connection.disconnect()
        }
    }

    /** Strict detached-JWS RS256 verification; check order mirrors com.bunting.verify.JwsVerifier. */
    private fun verifyDetached(jws: String, payload: ByteArray, publicKeys: Map<String, String>) {
        val parts = jws.split(".")
        check(parts.size == 3 && parts[1].isEmpty()) { "malformed detached JWS" }
        val headerBytes = Base64.getUrlDecoder().decode(parts[0])
        val header = Json.parseToJsonElement(headerBytes.toString(Charsets.UTF_8)).jsonObject
        check((header["alg"] as? JsonPrimitive)?.content == "RS256") { "unsupported alg" }
        check((header["b64"] as? JsonPrimitive)?.booleanOrNull == false) { "b64 must be false" }
        val crit = header["crit"] as? JsonArray
        check(crit != null && crit.isEmpty() == false && crit.all { (it as? JsonPrimitive)?.content == "b64" }) {
            "crit must be exactly [\"b64\"]"
        }
        val kid = (header["kid"] as? JsonPrimitive)?.content
            ?: throw IllegalStateException("header has no kid")
        val pem = publicKeys[kid]
            ?: throw IllegalStateException("no public key for kid $kid")

        val der = Base64.getMimeDecoder().decode(
            pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
        )
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val signingInput = parts[0].toByteArray(Charsets.US_ASCII) + 0x2E.toByte() + payload
        val valid = Signature.getInstance("SHA256withRSA").run {
            initVerify(publicKey)
            update(signingInput)
            verify(Base64.getUrlDecoder().decode(parts[2]))
        }
        check(valid) { "signature verification failed" }
    }
}
