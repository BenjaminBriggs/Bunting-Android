package com.bunting.conformance

import com.bunting.BuildInfo
import com.bunting.evaluation.Bucketing
import com.bunting.evaluation.ConfigFingerprint
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.FlagEvaluator
import com.bunting.model.ArtifactDecoder
import com.bunting.model.ArtifactRejectedException
import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.BuntingJson
import com.bunting.model.VariantType
import com.bunting.storage.BootstrapConfig
import com.bunting.verify.JwsVerifier
import com.bunting.verify.PublicKeyInfo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
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

    /** A set added to the bundle must get a runner here before the SDK can claim it. */
    @Test
    fun everyManifestSetHasARunner() {
        assertEquals(
            setOf("bucketing", "evaluation", "fingerprint", "signature", "bootstrap", "decoding"),
            manifest().sets.keys,
        )
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

    private fun environment(raw: String): BuntingEnvironment =
        requireNotNull(BuntingEnvironment.fromWire(raw)) { "bad env $raw" }

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

    @Serializable data class EvaluationCase(
        val context: VectorContext,
        @SerialName("flag_key") val flagKey: String,
        val environment: String,
        @SerialName("expected_value") val expectedValue: JsonElement,
        @SerialName("expected_source") val expectedSource: String,
    )
    @Serializable data class EvaluationSet(val artifact: JsonObject, val cases: List<EvaluationCase>)

    /** Decodes a set's fixture artifact with the real decoder; the generator guarantees
     *  it decodes losslessly, so any issue here is an SDK decoding bug. */
    private fun decodeFixture(artifact: JsonObject): BuntingConfiguration {
        val result = ArtifactDecoder.decode(artifact.toString())
        assertTrue(result.isLossless, "fixture artifact did not decode losslessly: ${result.issues}")
        return result.configuration
    }

    /**
     * Checks one evaluation case: the value (decoded by the flag's declared type, so
     * dates compare as instants and numbers numerically) and the source, read as the
     * label of the fingerprint path the evaluator selected.
     */
    private fun assertEvaluation(config: BuntingConfiguration, c: EvaluationCase, label: String) {
        val env = environment(c.environment)
        val flag = requireNotNull(config.flags[c.flagKey]) { "$label: flag ${c.flagKey} missing from the decoded artifact" }
        val expected = requireNotNull(ArtifactDecoder.decodeValue(flag.type, c.expectedValue)) {
            "$label: expected_value ${c.expectedValue} is not a valid ${flag.type.wireName}"
        }
        val evaluator = FlagEvaluator(config, env, context(c.context), UUID.fromString(c.context.localId)) { false }
        val actual = evaluator.evaluate(c.flagKey)
        assertNotNull(actual, "$label: no value for flag ${c.flagKey}")
        assertEquals(expected, actual, "$label: flag ${c.flagKey} [${c.environment}] value")

        val labels = PathLabels.enumerate(config, c.flagKey, env)
        assertEquals(labels.size, evaluator.pathCount(c.flagKey), "$label: flag ${c.flagKey} path count")
        val source = labels[evaluator.resolvePathIndex(c.flagKey)]
        assertEquals(c.expectedSource, source, "$label: flag ${c.flagKey} [${c.environment}] source")
    }

    @Test
    fun evaluationVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(EvaluationSet.serializer(), load("evaluation"))
        assertEquals(expectedCount(m, "evaluation"), set.cases.size)
        val config = decodeFixture(set.artifact)
        for ((index, c) in set.cases.withIndex()) assertEvaluation(config, c, "evaluation[$index]")
    }

    // MARK: fingerprint (encode direction; the SDK has no fingerprint decoder, so the
    // bundle's decode_cases are exercised by decoder-bearing implementers only)

    @Serializable data class FingerprintSet(val artifact: JsonObject, val cases: List<FingerprintCase>)
    @Serializable data class FingerprintCase(val context: VectorContext, val environment: String, val code: String)

    private fun assertFingerprint(config: BuntingConfiguration, c: FingerprintCase, label: String) {
        val env = environment(c.environment)
        val localId = UUID.fromString(c.context.localId)
        val code = ConfigFingerprint.compute(config, env, context(c.context), localId) { false }
        assertEquals(c.code, code, "$label: fingerprint [${c.environment}]")
    }

    @Test
    fun fingerprintVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(FingerprintSet.serializer(), load("fingerprint"))
        assertEquals(expectedCount(m, "fingerprint"), set.cases.size)
        val config = decodeFixture(set.artifact)
        for ((index, c) in set.cases.withIndex()) assertFingerprint(config, c, "fingerprint[$index]")
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

    // MARK: bootstrap (contract-level: app_identifier present + endpoint ends with config.json;
    // valid documents must also decode with the SDK's BootstrapConfig, keys in order)

    @Serializable data class BootstrapSet(val cases: List<BootstrapCase>)
    @Serializable data class BootstrapCase(
        val name: String,
        val valid: Boolean,
        val document: JsonObject,
        @SerialName("expected_kids") val expectedKids: List<String>? = null,
    )
    @Serializable data class BootstrapContract(
        @SerialName("app_identifier") val appIdentifier: String? = null,
        @SerialName("endpoint_url") val endpointUrl: String? = null,
    )

    @Test
    fun bootstrapVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(BootstrapSet.serializer(), load("bootstrap"))
        assertEquals(expectedCount(m, "bootstrap"), set.cases.size)
        var checkedKids = 0
        for (c in set.cases) {
            val contract = BuntingJson.decodeFromJsonElement(BootstrapContract.serializer(), c.document)
            val isValid = contract.appIdentifier != null &&
                (contract.endpointUrl?.endsWith("config.json") ?: false)
            assertEquals(c.valid, isValid, "bootstrap ${c.name}")
            if (c.valid == false) continue

            val bootstrap = BuntingJson.decodeFromJsonElement(BootstrapConfig.serializer(), c.document)
            c.expectedKids?.let { kids ->
                assertEquals(kids, bootstrap.publicKeys.map { it.kid }, "bootstrap ${c.name} kids")
                checkedKids += 1
            }
        }
        assertTrue(checkedKids > 0, "no bootstrap case carried expected_kids")
    }

    // MARK: decoding

    @Serializable data class DecodingSet(val cases: List<DecodingCase>)
    @Serializable data class DecodingCase(
        val name: String,
        @SerialName("artifact_b64") val artifactB64: String,
        val expected: DecodingExpected,
        val evaluations: List<EvaluationCase> = emptyList(),
        val fingerprint: FingerprintCase? = null,
    )
    @Serializable data class DecodingExpected(
        val accepted: Boolean,
        val reason: String? = null,
        val flags: Map<String, ExpectedFlag>? = null,
        @SerialName("skipped_flags") val skippedFlags: List<String>? = null,
        val tests: List<String>? = null,
        val rollouts: List<String>? = null,
    )
    @Serializable data class ExpectedFlag(val type: String, val environments: Map<String, ExpectedEnvironment>)
    @Serializable data class ExpectedEnvironment(val default: JsonElement, val variants: List<ExpectedVariant>)
    @Serializable data class ExpectedVariant(val order: Int, val kind: String)

    @Test
    fun decodingVectors() {
        val m = manifest()
        val set = BuntingJson.decodeFromString(DecodingSet.serializer(), load("decoding"))
        assertEquals(expectedCount(m, "decoding"), set.cases.size)
        for (c in set.cases) {
            val text = Base64.getDecoder().decode(c.artifactB64).decodeToString()
            val expected = c.expected
            if (expected.accepted == false) {
                val rejection = assertThrows(ArtifactRejectedException::class.java, { ArtifactDecoder.decode(text) },
                    "decoding ${c.name} must be rejected")
                assertEquals(expected.reason, rejection.reason, "decoding ${c.name} rejection reason")
                continue
            }

            val result = ArtifactDecoder.decode(text)
            val config = result.configuration
            val expectedFlags = requireNotNull(expected.flags) { "decoding ${c.name} has no expected flags" }
            assertEquals(expectedFlags.keys.sorted(), config.flags.keys.sorted(), "decoding ${c.name} flag keys")
            for ((key, expectedFlag) in expectedFlags) {
                val flag = config.flags.getValue(key)
                assertEquals(expectedFlag.type, flag.type.wireName, "decoding ${c.name} $key type")
                assertEquals(BuntingEnvironment.entries.map { it.wireName }.toSet(), expectedFlag.environments.keys,
                    "decoding ${c.name} $key environments")
                for ((envName, expectedEnv) in expectedFlag.environments) {
                    val env = flag.config(environment(envName))
                    val expectedDefault = ArtifactDecoder.decodeValue(flag.type, expectedEnv.default)
                    assertEquals(expectedDefault, env.defaultValue, "decoding ${c.name} $key [$envName] default")
                    assertEquals(
                        expectedEnv.variants.map { it.order to it.kind },
                        env.variants.map { it.order to kind(it.type) },
                        "decoding ${c.name} $key [$envName] variants",
                    )
                }
            }
            assertEquals(expected.skippedFlags, result.skippedFlagKeys.sorted(), "decoding ${c.name} skipped flags")
            assertEquals(expected.tests, config.tests.keys.sorted(), "decoding ${c.name} tests")
            assertEquals(expected.rollouts, config.rollouts.keys.sorted(), "decoding ${c.name} rollouts")

            // Every surviving flag must be evaluated by the generator; this keeps the
            // decoded model and the evaluations from drifting apart.
            assertTrue(c.evaluations.map { it.flagKey }.toSet().containsAll(config.flags.keys),
                "decoding ${c.name} evaluations miss a decoded flag")
            for ((index, e) in c.evaluations.withIndex()) assertEvaluation(config, e, "decoding ${c.name} evaluation[$index]")
            val fingerprint = requireNotNull(c.fingerprint) { "decoding ${c.name} has no fingerprint" }
            assertFingerprint(config, fingerprint, "decoding ${c.name}")
        }
    }

    /** The bundle's `kind` for a decoded variant; placeholders are `unrecognized`. */
    private fun kind(type: VariantType): String = when (type) {
        VariantType.CONDITIONAL -> "conditional"
        VariantType.TEST -> "test"
        VariantType.ROLLOUT -> "rollout"
        VariantType.UNRECOGNIZED -> "unrecognized"
    }

    /**
     * Fingerprint path labels in the admin's `enumeratePaths` phrasing, built from the
     * decoded configuration alone (not the evaluator), so a vector's `expected_source`
     * checks both which value won and which fingerprint path the evaluator recorded.
     */
    private object PathLabels {
        fun enumerate(config: BuntingConfiguration, flagKey: String, env: BuntingEnvironment): List<String> {
            val envConfig = config.flags.getValue(flagKey).config(env)
            val labels = mutableListOf("${env.wireName} default")
            for (variant in envConfig.variants.sortedBy { it.order }) {
                when (variant.type) {
                    VariantType.UNRECOGNIZED -> labels += "unrecognized variant (order ${variant.order})"
                    VariantType.CONDITIONAL -> labels += "conditional variant (order ${variant.order})"
                    VariantType.TEST -> {
                        val groups = variant.test?.let { config.tests[it]?.groups } ?: emptyList()
                        for (group in groups) labels += "test \"${variant.test}\" group \"${group.name}\""
                    }
                    VariantType.ROLLOUT -> labels += "rollout \"${variant.rollout}\""
                }
            }
            return labels
        }
    }
}
