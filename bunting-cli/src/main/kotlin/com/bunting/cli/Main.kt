package com.bunting.cli

import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingJson
import com.bunting.storage.BootstrapConfig
import com.bunting.verify.JwsVerifier
import com.bunting.verify.SignatureTransport
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.exitProcess

/** Exit codes mirror the Swift `bunting-cli`. */
object ExitCode {
    const val USAGE_OR_BOOTSTRAP_ERROR: Int = 1
    const val NETWORK_ERROR: Int = 2
    const val SIGNATURE_ERROR: Int = 3
    const val DECODE_ERROR: Int = 4
}

/**
 * Fetches a Bunting config from the endpoint named in a bootstrap JSON document,
 * verifies its detached JWS over the exact fetched bytes, decodes it, and saves it.
 * Returns the process exit code (testable; `main` calls exitProcess with it).
 */
fun run(args: Array<String>): Int {
    if (args.isEmpty()) {
        printUsage()
        return ExitCode.USAGE_OR_BOOTSTRAP_ERROR
    }
    val bootstrapPath = args[0]
    val outputPath = if (args.size >= 2) args[1] else "BuntingConfig.json"

    println("Bunting Config Fetcher")

    val bootstrap = try {
        BuntingJson.decodeFromString(BootstrapConfig.serializer(), File(bootstrapPath).readText())
    } catch (e: Exception) {
        System.err.println("Error: could not load bootstrap $bootstrapPath: ${e.message}")
        return ExitCode.USAGE_OR_BOOTSTRAP_ERROR
    }
    println("Loaded endpoint: ${bootstrap.endpointUrl}")
    println("Found ${bootstrap.publicKeys.size} public key(s)")

    val fetched = try {
        httpGet(bootstrap.endpointUrl)
    } catch (e: Exception) {
        System.err.println("Error: fetching config failed: ${e.message}")
        return ExitCode.NETWORK_ERROR
    }
    if (fetched.statusCode != 200) {
        System.err.println("Error: HTTP ${fetched.statusCode} fetching ${bootstrap.endpointUrl}")
        return ExitCode.NETWORK_ERROR
    }
    val data = fetched.body
    println("Downloaded ${data.size} bytes")

    val jws = fetched.signatureHeader ?: run {
        val sigUrl = SignatureTransport.sigUrl(bootstrap.endpointUrl)
        val sigResponse = try {
            httpGet(sigUrl)
        } catch (e: Exception) {
            System.err.println("Error: no ${SignatureTransport.HEADER_NAME} header and .sig fetch failed: ${e.message}")
            return ExitCode.SIGNATURE_ERROR
        }
        val sig = sigResponse.body.toString(Charsets.UTF_8).trim()
        if (sigResponse.statusCode != 200 || sig.isEmpty()) {
            System.err.println("Error: no signature available (header absent, $sigUrl returned ${sigResponse.statusCode})")
            return ExitCode.SIGNATURE_ERROR
        }
        sig
    }

    try {
        JwsVerifier.verifyDetached(jws, data, bootstrap.publicKeys)
    } catch (e: Exception) {
        System.err.println("Error: signature verification failed: ${e.message}")
        return ExitCode.SIGNATURE_ERROR
    }
    println("Signature verified")

    val config = try {
        BuntingJson.decodeFromString(BuntingConfiguration.serializer(), data.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        System.err.println("Error: config decode failed: ${e.message}")
        return ExitCode.DECODE_ERROR
    }
    println("Schema version: ${config.schemaVersion}")
    println("Config version: ${config.configVersion}")
    println("App identifier: ${config.appIdentifier}")
    println("Published at: ${config.publishedAt}")

    try {
        File(outputPath).writeBytes(data)
    } catch (e: Exception) {
        System.err.println("Error: could not save to $outputPath: ${e.message}")
        return ExitCode.USAGE_OR_BOOTSTRAP_ERROR
    }
    println("Saved to $outputPath")
    return 0
}

private class Fetched(val statusCode: Int, val body: ByteArray, val signatureHeader: String?)

/** Synchronous GET, mirroring the Swift CLI's self-contained transport. */
private fun httpGet(url: String): Fetched {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes() } ?: ByteArray(0)
        Fetched(code, body, conn.getHeaderField(SignatureTransport.HEADER_NAME))
    } finally {
        conn.disconnect()
    }
}

private fun printUsage() {
    println(
        """
        Bunting CLI - fetch and verify a Bunting configuration

        USAGE:
            bunting-cli <bootstrap-json-path> [output-path]

        ARGUMENTS:
            bootstrap-json-path   Path to bunting_config.json (endpoint + public keys)
            output-path           Where to save the verified config (default: BuntingConfig.json)

        EXIT CODES:
            0   Success
            1   Usage, bootstrap, or file-system error
            2   Network or HTTP error fetching config.json
            3   Signature missing (no header, no .sig) or verification failed
            4   Config JSON failed to decode
        """.trimIndent()
    )
}

fun main(args: Array<String>) {
    exitProcess(run(args))
}
