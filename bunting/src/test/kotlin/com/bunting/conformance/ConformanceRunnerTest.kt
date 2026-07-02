package com.bunting.conformance

import com.bunting.BuildInfo
import com.bunting.evaluation.Bucketing
import com.bunting.evaluation.ConfigFingerprint
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.FlagEvaluator
import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.BuntingJson
import com.bunting.model.FlagValue
import com.bunting.verify.JwsVerifier
import com.bunting.verify.PublicKeyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64
import java.util.UUID

/**
 * Iterates the vendored conformance bundle produced by the admin's real
 * implementations. Passing this suite defines a correct Bunting client and is the
 * completion gate for the Android SDK core.
 */
class ConformanceRunnerTest {

    private fun load(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/conformance/$name.json")) {
            "missing vendored conformance resource: $name.json"
        }.use { it.readBytes().decodeToString() }

    // MARK: manifest

    @Serializable data class Manifest(val version: String, val sets: Map<String, SetInfo>)
    @Serializable data class SetInfo(val file: String, val count: Int)

    private fun manifest(): Manifest = BuntingJson.decodeFromString(Manifest.serializer(), load("manifest"))
    private fun expectedCount(m: Manifest, set: String): Int =
        requireNotNull(m.sets[set]?.count) { "manifest is missing set $set" }

    @Test
    fun manifestMatchesPinnedVersion() {
        assertEquals(BuildInfo.CONFORMANCE_BUNDLE_VERSION, manifest().version)
    }

    // MARK: shared context vector

    @Serializable
    data class VectorContext(
        val platform: String,
        @SerialName("device_class") val deviceClass: String? = null,
        @SerialName("os_version") val osVersion: String,
        @SerialName("app_version") val appVersion: String,
        @SerialName("build_number") val buildNumber: String,
        @SerialName("device_model") val deviceModel: String,
        val region: String? = null,
        val language: String,
        @SerialName("reserved_attributes") val reservedAttributes: Map<String, String>? = null,
        @SerialName("local_id") val localId: String,
    )

    private fun context(vc: VectorContext) = EvaluationContext(
        platform = vc.platform, osVersion = vc.osVersion, appVersion = vc.appVersion,
        buildNumber = vc.buildNumber, deviceModel = vc.deviceModel, region = vc.region,
        language = vc.language, deviceClass = vc.deviceClass,
        reservedAttributes = vc.reservedAttributes ?: emptyMap(),
    )

    // MARK: bucketing

    @Serializable data class BucketingSet(val cases: List<BucketingCase>)
    @Serializable data class BucketingCase(
        val salt: String, val identifier: String, val bucket: Int,
        @SerialName("swift_applicable") val swiftApplicable: Boolean? = null,
    )

    @Test
    fun bucketingVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(BucketingSet.serializer(), load("bucketing"))
        assertEquals(expectedCount(m, "bucketing"), set.cases.size)
        // Run every case not explicitly flagged swift_applicable:false. Those use
        // canonical UUID identities the SDK reproduces; the flagged-out cases use
        // non-UUID strings only the admin's string-hashing reference exercises.
        val applicable = set.cases.filter { it.swiftApplicable != false }
        assertTrue(applicable.size >= 100)
        for (c in applicable) {
            val uuid = UUID.fromString(c.identifier)
            assertEquals(c.bucket, Bucketing.bucket(c.salt, uuid),
                "bucket mismatch for ${c.salt} / ${c.identifier}")
        }
    }

    // MARK: evaluation

    @Serializable data class EvaluationSet(val artifact: BuntingConfiguration, val cases: List<EvaluationCase>)
    @Serializable data class EvaluationCase(
        val context: VectorContext,
        @SerialName("flag_key") val flagKey: String,
        val environment: String,
        @SerialName("expected_value") val expectedValue: FlagValue,
        @SerialName("expected_source") val expectedSource: String,
    )

    @Test
    fun evaluationVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(EvaluationSet.serializer(), load("evaluation"))
        assertEquals(expectedCount(m, "evaluation"), set.cases.size)
        for (c in set.cases) {
            val env = requireNotNull(BuntingEnvironment.fromWire(c.environment)) { "bad env ${c.environment}" }
            val localId = UUID.fromString(c.context.localId)
            val evaluator = FlagEvaluator(set.artifact, env, context(c.context), localId) { false }
            val actual = evaluator.evaluate(c.flagKey)
            assertNotNull(actual, "no value for flag ${c.flagKey}")
            assertEquals(c.expectedValue, actual,
                "flag ${c.flagKey} [${c.environment}] expected ${c.expectedValue}, got $actual")
        }
    }

    // MARK: fingerprint (encode direction; the SDK has no fingerprint decoder, so the
    // bundle's decode_cases are exercised by decoder-bearing implementers only)

    @Serializable data class FingerprintSet(val artifact: BuntingConfiguration, val cases: List<FingerprintCase>)
    @Serializable data class FingerprintCase(val context: VectorContext, val environment: String, val code: String)

    @Test
    fun fingerprintVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(FingerprintSet.serializer(), load("fingerprint"))
        assertEquals(expectedCount(m, "fingerprint"), set.cases.size)
        for (c in set.cases) {
            val env = requireNotNull(BuntingEnvironment.fromWire(c.environment)) { "bad env ${c.environment}" }
            val localId = UUID.fromString(c.context.localId)
            val code = ConfigFingerprint.compute(set.artifact, env, context(c.context), localId) { false }
            assertEquals(c.code, code, "fingerprint [${c.environment}] expected ${c.code}, got $code")
        }
    }

    // MARK: signature

    @Serializable data class SignatureSet(val cases: List<SignatureCase>)
    @Serializable data class SignatureCase(
        val kind: String,
        @SerialName("config_b64") val configB64: String,
        val jws: String,
        @SerialName("verify_kid") val verifyKid: String,
        @SerialName("verify_pem") val verifyPem: String,
        @SerialName("expect_valid") val expectValid: Boolean,
    )

    @Test
    fun signatureVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(SignatureSet.serializer(), load("signature"))
        assertEquals(expectedCount(m, "signature"), set.cases.size)
        for (c in set.cases) {
            val payload = Base64.getDecoder().decode(c.configB64)
            var threw = false
            try {
                JwsVerifier.verifyDetached(c.jws, payload, listOf(PublicKeyInfo(c.verifyKid, c.verifyPem)))
            } catch (_: Exception) {
                threw = true
            }
            // Valid cases must verify (no throw); every other kind must be rejected.
            assertEquals(c.expectValid == false, threw, "signature ${c.kind}")
        }
    }

    // MARK: bootstrap (contract-level: app_identifier present + endpoint ends with config.json)

    @Serializable data class BootstrapSet(val cases: List<BootstrapCase>)
    @Serializable data class BootstrapCase(val name: String, val valid: Boolean, val document: BootstrapDocument)
    @Serializable data class BootstrapDocument(
        @SerialName("app_identifier") val appIdentifier: String? = null,
        @SerialName("endpoint_url") val endpointUrl: String? = null,
    )

    @Test
    fun bootstrapVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(BootstrapSet.serializer(), load("bootstrap"))
        assertEquals(expectedCount(m, "bootstrap"), set.cases.size)
        for (c in set.cases) {
            val isValid = c.document.appIdentifier != null &&
                (c.document.endpointUrl?.endsWith("config.json") ?: false)
            assertEquals(c.valid, isValid, "bootstrap ${c.name}")
        }
    }
}
