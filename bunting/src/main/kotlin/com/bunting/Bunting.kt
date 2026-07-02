package com.bunting

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/**
 * Process-wide singleton holder over a [BuntingClient]. Configure once at startup;
 * the delegating flag accessors return the caller default (with a one-time warning)
 * until then, so app code can read flags safely before [configure] runs.
 */
object Bunting {
    private val mutableState = MutableStateFlow(BuntingState.EMPTY)

    /** The observable state; [BuntingState.EMPTY] until [configure] is called. */
    val state: StateFlow<BuntingState> = mutableState.asStateFlow()

    @Volatile
    private var client: BuntingClient? = null

    private val warnedUnconfigured = AtomicBoolean(false)
    private val logger = Logger.getLogger("com.bunting.Bunting")

    val isConfigured: Boolean get() = client != null

    /** Builds and installs the singleton client. Calling again replaces the previous one. */
    fun configure(options: BuntingOptions): BuntingClient {
        val configured = BuntingClient(options, mutableState)
        client = configured
        return configured
    }

    /** The configured client, or throws with actionable guidance if [configure] hasn't run. */
    val instance: BuntingClient
        get() = client ?: throw IllegalStateException(
            "Bunting is not configured. Call Bunting.configure(options) at startup before accessing Bunting.instance."
        )

    fun bool(key: String, default: Boolean): Boolean =
        client?.bool(key, default) ?: default.also { warnUnconfigured() }

    fun int(key: String, default: Long): Long =
        client?.int(key, default) ?: default.also { warnUnconfigured() }

    fun double(key: String, default: Double): Double =
        client?.double(key, default) ?: default.also { warnUnconfigured() }

    fun string(key: String, default: String): String =
        client?.string(key, default) ?: default.also { warnUnconfigured() }

    fun date(key: String, default: Instant): Instant =
        client?.date(key, default) ?: default.also { warnUnconfigured() }

    fun jsonData(key: String): ByteArray? {
        val c = client ?: run { warnUnconfigured(); return null }
        return c.jsonData(key)
    }

    private fun warnUnconfigured() {
        if (warnedUnconfigured.compareAndSet(false, true)) {
            logger.warning(
                "Bunting is not configured; returning the caller-supplied default. " +
                    "Call Bunting.configure(options) at startup."
            )
        }
    }

    /** Test hook: drops the configured client and resets published state. */
    internal fun resetForTesting() {
        client = null
        warnedUnconfigured.set(false)
        mutableState.value = BuntingState.EMPTY
    }
}
