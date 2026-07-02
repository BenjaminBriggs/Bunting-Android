package com.bunting.evaluation

/** Supplies the current runtime [EvaluationContext]. Plan 3's Android provider
 *  reads Build/Configuration/UiModeManager; the CLI and tests use a static one. */
fun interface ContextProvider {
    fun current(): EvaluationContext
}

/** A [ContextProvider] returning a fixed context. */
class StaticContextProvider(private val context: EvaluationContext) : ContextProvider {
    override fun current(): EvaluationContext = context
}
