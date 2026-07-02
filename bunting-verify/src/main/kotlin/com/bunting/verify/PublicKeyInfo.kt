package com.bunting.verify

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Public key material for detached-JWS verification. */
@Serializable
data class PublicKeyInfo(
    @SerialName("kid") val kid: String,
    @SerialName("pem") val pem: String,
)

/**
 * The two supported signature-delivery transports, shared by the SDK and the CLI:
 * the `x-bunting-signature` response header on the `config.json` response
 * (preferred, saves a request), falling back to a sibling `config.json.sig` object.
 */
object SignatureTransport {
    /** Response header carrying the compact detached JWS on `config.json` responses. */
    const val HEADER_NAME: String = "x-bunting-signature"

    /** Derives the detached-signature URL (`<config-url>.sig`) for a config URL. */
    fun sigUrl(configUrl: String): String = "$configUrl.sig"
}
