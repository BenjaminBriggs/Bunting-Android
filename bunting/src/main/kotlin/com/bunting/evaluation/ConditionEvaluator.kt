package com.bunting.evaluation

import com.bunting.model.Condition
import com.bunting.model.ConditionOperator
import com.bunting.model.ConditionType

/** Evaluates conditions against a fixed [EvaluationContext]. */
class ConditionEvaluator(
    private val context: EvaluationContext,
    private val customAttributeResolver: CustomAttributeResolver,
) {
    fun evaluate(condition: Condition): Boolean = when (condition.type) {
        ConditionType.OS_VERSION -> evaluateVersion(condition, context.osVersion)
        ConditionType.APP_VERSION -> evaluateVersion(condition, context.appVersion)
        ConditionType.BUILD_NUMBER -> evaluateNumeric(condition, context.buildNumber.toIntOrNull() ?: 0)
        ConditionType.PLATFORM -> evaluateList(condition, context.platform)
        ConditionType.DEVICE_MODEL -> evaluateList(condition, context.deviceModel)
        ConditionType.DEVICE_CLASS -> {
            val dc = context.deviceClass
            if (dc == null) false else evaluateList(condition, dc)
        }
        ConditionType.REGION -> {
            val r = context.region
            if (r == null) false else evaluateList(condition, r)
        }
        ConditionType.LANGUAGE -> evaluateList(condition, context.language)
        ConditionType.CUSTOM_ATTRIBUTE -> {
            val name = condition.values.firstOrNull()
            when {
                name == null -> false
                EvaluationContext.reservedAttributeNames.contains(name) -> {
                    // Reserved names resolve only from the SDK dictionary; an absent
                    // value is a non-match, never a fallthrough to the app resolver.
                    val reserved = context.reservedAttributes[name]
                    if (reserved == null) false else condition.values.drop(1).contains(reserved)
                }
                else -> customAttributeResolver(name)
            }
        }
    }

    fun evaluateAll(conditions: List<Condition>): Boolean = conditions.all { evaluate(it) }

    // MARK: version comparison

    private fun evaluateVersion(condition: Condition, contextValue: String): Boolean {
        val ctx = parseVersion(contextValue)
        fun target() = parseVersion(condition.values.firstOrNull() ?: "")
        return when (condition.operator) {
            ConditionOperator.EQUALS -> compareVersions(ctx, target()) == 0
            ConditionOperator.DOES_NOT_EQUALS -> compareVersions(ctx, target()) != 0
            ConditionOperator.GREATER_THAN -> compareVersions(ctx, target()) > 0
            ConditionOperator.GREATER_THAN_OR_EQUAL -> compareVersions(ctx, target()) >= 0
            ConditionOperator.LESS_THAN -> compareVersions(ctx, target()) < 0
            ConditionOperator.LESS_THAN_OR_EQUAL -> compareVersions(ctx, target()) <= 0
            ConditionOperator.BETWEEN ->
                if (condition.values.size != 2) false
                else compareVersions(ctx, parseVersion(condition.values[0])) >= 0 &&
                    compareVersions(ctx, parseVersion(condition.values[1])) <= 0
            else -> false
        }
    }

    private fun parseVersion(v: String): List<Int> =
        v.split(Regex("[^0-9]+")).filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }

    private fun compareVersions(lhs: List<Int>, rhs: List<Int>): Int {
        val n = maxOf(lhs.size, rhs.size)
        for (i in 0 until n) {
            val l = if (i < lhs.size) lhs[i] else 0
            val r = if (i < rhs.size) rhs[i] else 0
            if (l < r) return -1
            if (l > r) return 1
        }
        return 0
    }

    // MARK: numeric comparison

    private fun evaluateNumeric(condition: Condition, contextValue: Int): Boolean {
        fun target() = condition.values.firstOrNull()?.toIntOrNull()
        return when (condition.operator) {
            ConditionOperator.EQUALS -> target()?.let { contextValue == it } ?: false
            ConditionOperator.DOES_NOT_EQUALS -> target()?.let { contextValue != it } ?: false
            ConditionOperator.GREATER_THAN -> target()?.let { contextValue > it } ?: false
            ConditionOperator.GREATER_THAN_OR_EQUAL -> target()?.let { contextValue >= it } ?: false
            ConditionOperator.LESS_THAN -> target()?.let { contextValue < it } ?: false
            ConditionOperator.LESS_THAN_OR_EQUAL -> target()?.let { contextValue <= it } ?: false
            ConditionOperator.BETWEEN -> {
                val min = condition.values.getOrNull(0)?.toIntOrNull()
                val max = condition.values.getOrNull(1)?.toIntOrNull()
                if (condition.values.size == 2 && min != null && max != null) contextValue in min..max else false
            }
            else -> false
        }
    }

    // MARK: list comparison

    private fun evaluateList(condition: Condition, contextValue: String): Boolean = when (condition.operator) {
        ConditionOperator.IN -> condition.values.contains(contextValue)
        ConditionOperator.NOT_IN -> condition.values.contains(contextValue) == false
        else -> false
    }
}
