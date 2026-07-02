package com.bunting.verify

/**
 * Errors thrown by [JwsVerifier]. Granular cases let the CLI explain *why*
 * verification failed; the SDK collapses all of them into a fetch failure.
 */
sealed class JwsVerificationException(message: String) : Exception(message) {
    /** Not a well-formed detached compact serialization (`header..signature`). */
    class MalformedJws : JwsVerificationException("Malformed detached JWS (expected compact form header..signature)")

    /** The header `alg` is missing or not `RS256`. */
    class UnsupportedAlgorithm(val alg: String?) :
        JwsVerificationException(alg?.let { "Unsupported JWS alg: $it (expected RS256)" }
            ?: "JWS header is missing the required alg parameter")

    /** The header `b64` is missing or not `false` (RFC 7797 detached payload). */
    class InvalidB64Parameter : JwsVerificationException("JWS header must set b64: false (RFC 7797 detached payload)")

    /** The header `crit` is missing, empty, or names an extension other than `b64`. */
    class UnsupportedCriticalParameters : JwsVerificationException("""JWS header crit must be exactly ["b64"]""")

    /** The header names a `kid` not present in the provided key set. */
    class UnknownKid(val kid: String) : JwsVerificationException("No public key found for kid: $kid")

    /** A public key PEM could not be parsed into an RSA key. */
    class InvalidPublicKey : JwsVerificationException("Public key PEM could not be parsed as an RSA key")

    /** The signature does not verify over the given payload bytes. */
    class SignatureMismatch : JwsVerificationException("Signature does not match the payload bytes")
}
