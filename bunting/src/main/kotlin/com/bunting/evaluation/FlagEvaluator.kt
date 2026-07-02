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
 *  which terminal path won. */
class FlagEvaluator(
    private val configuration: BuntingConfiguration,
    private val environment: BuntingEnvironment,
    private val context: EvaluationContext,
    private val localId: UUID,
    private val customAttributeResolver: CustomAttributeResolver,
) {
    private val conditionEvaluator = ConditionEvaluator(context, customAttributeResolver)

    fun evaluate(flagKey: String): FlagValue? {
        val flag = configuration.flags[flagKey] ?: return null
        val envConfig = flag.config(environment)
        for (variant in envConfig.variants.sortedBy { it.order }) {
            when (variant.type) {
                VariantType.CONDITIONAL ->
                    // Null conditions are a vacuous match, per the contract.
                    if (conditionEvaluator.evaluateAll(variant.conditions ?: emptyList())) {
                        variant.value?.let { return it }
                    }
                VariantType.TEST -> {
                    val test = variant.test?.let { configuration.tests[it] }
                    if (test != null) evaluateTest(test, variant)?.let { return it }
                }
                VariantType.ROLLOUT -> {
                    val rollout = variant.rollout?.let { configuration.rollouts[it] }
                    if (rollout != null && evaluateRollout(rollout)) {
                        variant.value?.let { return it }
                    }
                }
            }
        }
        return envConfig.defaultValue
    }

    private fun evaluateTest(test: Test, variant: Variant): FlagValue? {
        if (test.conditions.isNotEmpty() && conditionEvaluator.evaluateAll(test.conditions) == false) {
            return null
        }
        val bucket = Bucketing.bucket(test.salt, localId)
        test.assignGroup(bucket)?.let { groupName ->
            variant.values?.get(groupName)?.let { return it }
        }
        variant.values?.values?.firstOrNull()?.let { return it }
        return variant.value
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
        VariantType.CONDITIONAL, VariantType.ROLLOUT -> 1
        VariantType.TEST -> variant.test?.let { configuration.tests[it]?.groups?.size } ?: 0
    }

    fun resolvePathIndex(flagKey: String): Int {
        val flag = configuration.flags[flagKey] ?: return 0
        var base = 1
        for (variant in flag.config(environment).variants.sortedBy { it.order }) {
            when (variant.type) {
                VariantType.CONDITIONAL -> {
                    if (conditionEvaluator.evaluateAll(variant.conditions ?: emptyList()) && variant.value != null) {
                        return base
                    }
                    base += 1
                }
                VariantType.ROLLOUT -> {
                    val rollout = variant.rollout?.let { configuration.rollouts[it] }
                    if (rollout != null && evaluateRollout(rollout) && variant.value != null) {
                        return base
                    }
                    base += 1
                }
                VariantType.TEST -> {
                    val groupCount = variant.test?.let { configuration.tests[it]?.groups?.size } ?: 0
                    val test = variant.test?.let { configuration.tests[it] }
                    val groupIndex = test?.let { matchedTestGroupIndex(it) }
                    if (groupIndex != null) return base + groupIndex
                    base += groupCount
                }
            }
        }
        return 0
    }

    private fun matchedTestGroupIndex(test: Test): Int? {
        if (test.conditions.isNotEmpty() && conditionEvaluator.evaluateAll(test.conditions) == false) return null
        val groups = test.groups ?: return null
        if (groups.isEmpty()) return null
        val bucket = Bucketing.bucket(test.salt, localId)
        val groupName = test.assignGroup(bucket) ?: return null
        val idx = groups.indexOfFirst { it.name == groupName }
        return if (idx >= 0) idx else null
    }
}
