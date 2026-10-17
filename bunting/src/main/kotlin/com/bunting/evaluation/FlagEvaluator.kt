package com.bunting.evaluation

import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.FlagValue
import com.bunting.model.Rollout
import com.bunting.model.Test
import com.bunting.model.Variant
import com.bunting.model.VariantType
import java.util.UUID

/** Evaluates flags by variant order and precedence, and (for fingerprints) records
 *  which terminal path won. Value and path come from one pass ([resolve]), so the
 *  served value and the fingerprint can never disagree. */
class FlagEvaluator(
    private val configuration: BuntingConfiguration,
    private val environment: BuntingEnvironment,
    private val context: EvaluationContext,
    private val localId: UUID,
    private val customAttributeResolver: CustomAttributeResolver,
) {
    private val conditionEvaluator = ConditionEvaluator(context, customAttributeResolver)

    /** A flag's resolved value and the index of the fingerprint path that produced it
     *  (0 is the environment default). */
    internal data class Resolution(val value: FlagValue, val pathIndex: Int)

    fun evaluate(flagKey: String): FlagValue? = resolve(flagKey)?.value

    /**
     * Walks the variants in ascending `order`; the first match wins, else the default.
     * Paths are numbered as the admin's `enumeratePaths` does: 0 is the default, then
     * per variant one path for conditional, rollout, and unrecognized placeholders, and
     * one per group of the referenced test.
     */
    internal fun resolve(flagKey: String): Resolution? {
        val flag = configuration.flags[flagKey] ?: return null
        val envConfig = flag.config(environment)
        var base = 1
        for (variant in envConfig.variants.sortedBy { it.order }) {
            when (variant.type) {
                VariantType.CONDITIONAL -> {
                    val value = variant.value
                    // Null conditions are a vacuous match, per the contract.
                    if (value != null && conditionEvaluator.evaluateAll(variant.conditions ?: emptyList())) {
                        return Resolution(value, base)
                    }
                    base += 1
                }
                VariantType.TEST -> {
                    val test = variant.test?.let { configuration.tests[it] }
                    if (test != null) {
                        matchTestGroup(test, variant)?.let { (groupIndex, value) ->
                            return Resolution(value, base + groupIndex)
                        }
                    }
                    base += test?.groups?.size ?: 0
                }
                VariantType.ROLLOUT -> {
                    val value = variant.value
                    val rollout = variant.rollout?.let { configuration.rollouts[it] }
                    if (value != null && rollout != null && evaluateRollout(rollout)) {
                        return Resolution(value, base)
                    }
                    base += 1
                }
                // A placeholder for a variant this SDK couldn't decode never matches but
                // keeps its fingerprint path.
                VariantType.UNRECOGNIZED -> base += 1
            }
        }
        return Resolution(envConfig.defaultValue, 0)
    }

    /**
     * The group index and value a test variant serves, or null to skip the variant:
     * preconditions fail, the bucket lands past the groups' total, the test has no
     * groups, or the assigned group has no value in this variant.
     */
    private fun matchTestGroup(test: Test, variant: Variant): Pair<Int, FlagValue>? {
        if (test.conditions.isNotEmpty() && conditionEvaluator.evaluateAll(test.conditions) == false) return null
        val groups = test.groups ?: return null
        val groupName = test.assignGroup(Bucketing.bucket(test.salt, localId)) ?: return null
        val value = variant.values?.get(groupName) ?: return null
        val index = groups.indexOfFirst { it.name == groupName }
        return if (index >= 0) index to value else null
    }

    private fun evaluateRollout(rollout: Rollout): Boolean {
        if (rollout.conditions.isNotEmpty() && conditionEvaluator.evaluateAll(rollout.conditions) == false) {
            return false
        }
        return Bucketing.bucket(rollout.salt, localId) <= rollout.percentage
    }

    // MARK: fingerprint path resolution

    fun pathCount(flagKey: String): Int {
        val flag = configuration.flags[flagKey] ?: return 1
        var count = 1
        for (variant in flag.config(environment).variants) count += variantPathCount(variant)
        return count
    }

    private fun variantPathCount(variant: Variant): Int = when (variant.type) {
        VariantType.CONDITIONAL, VariantType.ROLLOUT, VariantType.UNRECOGNIZED -> 1
        VariantType.TEST -> variant.test?.let { configuration.tests[it]?.groups?.size } ?: 0
    }

    /** The fingerprint path index the flag resolves to (0, the default, when absent). */
    fun resolvePathIndex(flagKey: String): Int = resolve(flagKey)?.pathIndex ?: 0
}
