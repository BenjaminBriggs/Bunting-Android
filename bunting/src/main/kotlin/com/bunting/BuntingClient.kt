package com.bunting

import com.bunting.evaluation.ConfigFingerprint
import com.bunting.evaluation.EvaluationContext
import com.bunting.evaluation.FlagEvaluator
import com.bunting.evaluation.MemoizationCache
import com.bunting.identity.BuntingIdentity
import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingEnvironment
import com.bunting.model.ConfigSource
import com.bunting.model.FlagType
import com.bunting.model.FlagValue
import com.bunting.model.boolValue
import com.bunting.model.dateValue
import com.bunting.model.doubleValue
import com.bunting.model.flagType
import com.bunting.model.intValue
import com.bunting.model.jsonBytes
import com.bunting.model.stringValue
import com.bunting.storage.ConfigStore
import com.bunting.storage.ConfigStoreListener
import com.bunting.storage.FileKeyValueStore
import com.bunting.storage.OverrideValue
import com.bunting.storage.OverridesStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Constructor-injectable, fully JVM-testable feature-flag facade. Mirrors the
 * Swift `Bunting` @MainActor class, with two documented divergences: reads are
 * lock-free snapshot reads (no main-thread requirement), and identity is
 * per-install. Flag reads never suspend; only [refresh], [resetIdentity], and
 * [awaitInitialLoad] do.
 */
class BuntingClient(
    private val options: BuntingOptions,
    stateFlow: MutableStateFlow<BuntingState>,
) {
    /** The single observable state snapshot. */
    val state: StateFlow<BuntingState> = stateFlow.asStateFlow()
    private val mutableState = stateFlow

    /** Observability listener. Plain nullable reference (no weak ref — documented divergence). */
    var eventsListener: BuntingEventsListener? = null

    private val identity = BuntingIdentity(FileKeyValueStore(File(options.dataDir, "identity.json")))
    private val overridesStore = OverridesStore(File(options.dataDir, "overrides.json"))
    private val memoizationCache = MemoizationCache()

    // Bridges the store's fetch/verify/load callbacks to the current eventsListener.
    private val storeListener = object : ConfigStoreListener {
        override fun didStartFetch(url: String) { eventsListener?.didStartFetch(url) }
        override fun didCompleteFetch(success: Boolean, error: Throwable?) { eventsListener?.didCompleteFetch(success, error) }
        override fun didVerifySignature(success: Boolean) { eventsListener?.didVerifySignature(success) }
        override fun didLoadCachedConfig(version: String) { eventsListener?.didLoadCachedConfig(version) }
    }

    private val configStore = ConfigStore(
        bootstrap = options.bootstrap,
        filesDir = options.dataDir,
        nowMillis = options.nowMillis,
        seedLoader = options.seedLoader,
        listener = storeListener,
    )

    /** Full in-process snapshot; accessors read this without locking. */
    private data class Snapshot(
        val environment: BuntingEnvironment,
        val configuration: BuntingConfiguration?,
        val source: ConfigSource?,
        val signatureVerified: Boolean,
        val lastFetchTimeMillis: Long?,
        val etag: String?,
        val localId: UUID,
        val overrides: Map<String, OverrideValue>,
        val overridesVersion: Int,
        val context: EvaluationContext,
    )

    private val snapshotRef: AtomicReference<Snapshot>

    // Guards the (non-thread-safe) memo cache and the deprecated-read bookkeeping.
    private val lock = Any()
    private val reportedDeprecatedReads = HashSet<String>()
    private var reportedDeprecatedConfigVersion: String? = null

    private val initialLoadJob: Job

    init {
        val ctx = options.contextProvider.current()
        val id = identity.getLocalId()
        val initial = Snapshot(
            environment = options.environment,
            configuration = null,
            source = null,
            signatureVerified = false,
            lastFetchTimeMillis = null,
            etag = null,
            localId = id,
            overrides = overridesStore.getAllOverrides(),
            overridesVersion = 0,
            context = ctx,
        )
        snapshotRef = AtomicReference(initial)
        mutableState.value = initial.toState()

        initialLoadJob = options.scope.launch {
            configStore.loadCachedConfigIfNeeded()
            applyConfigState(configStore.getConfigState())
            refresh()
        }
    }

    /** Suspends until the initial cache/seed load and first refresh complete. */
    suspend fun awaitInitialLoad() = initialLoadJob.join()

    val environment: BuntingEnvironment get() = snapshotRef.get().environment

    val localId: UUID get() = snapshotRef.get().localId

    /**
     * A `<config_version>.<HEX>` fingerprint of the resolution this client produces
     * for the active environment and identity, or null until a configuration and
     * identity both exist. Excludes local overrides.
     */
    val userFingerprint: String?
        get() {
            val snap = snapshotRef.get()
            val config = snap.configuration ?: return null
            return ConfigFingerprint.compute(config, snap.environment, snap.context, snap.localId, options.customAttributeResolver)
        }

    // MARK: - Flag accessors (synchronous, lock-free snapshot reads)

    fun bool(key: String, default: Boolean): Boolean =
        evaluateFlag(key, FlagValue.BoolValue(default)).boolValue ?: default

    fun int(key: String, default: Long): Long =
        evaluateFlag(key, FlagValue.IntValue(default)).intValue ?: default

    fun double(key: String, default: Double): Double =
        evaluateFlag(key, FlagValue.DoubleValue(default)).doubleValue ?: default

    fun string(key: String, default: String): String =
        evaluateFlag(key, FlagValue.StringValue(default)).stringValue ?: default

    fun date(key: String, default: Instant): Instant =
        evaluateFlag(key, FlagValue.DateValue(default)).dateValue ?: default

    fun jsonData(key: String): ByteArray? =
        evaluateFlag(key, FlagValue.JsonValue("{}")).jsonBytes

    /**
     * Evaluation order (mirrors Swift evaluateFlagSync): a type-matched override →
     * memo cache → [FlagEvaluator] → the caller default. An override converts by the
     * flag's declared type when the loaded config has the flag (else by the caller
     * default's type), so it reads exactly like a served value; a wrong-typed override
     * falls through. Fires didFallbackToDefault when no config/identity is loaded or
     * the flag key is absent, and didReadDeprecatedFlag once per key per config version.
     */
    private fun evaluateFlag(key: String, default: FlagValue): FlagValue {
        val snap = snapshotRef.get()
        val config = snap.configuration

        snap.overrides[key]?.let { override ->
            val type = config?.flags?.get(key)?.type ?: default.flagType
            convertOverrideToFlagValue(override, type)?.let { return it }
        }

        if (config == null) {
            eventsListener?.didFallbackToDefault(key)
            return default
        }

        val cacheKey = MemoizationCache.CacheKey(
            flagKey = key,
            environment = snap.environment,
            contextHash = snap.context.computeHash(),
            overridesVersion = snap.overridesVersion,
            configVersion = config.configVersion,
        )
        synchronized(lock) { memoizationCache.get(cacheKey) }?.let { return it }

        val evaluator = FlagEvaluator(config, snap.environment, snap.context, snap.localId, options.customAttributeResolver)
        val evaluated = evaluator.evaluate(key)
        val result = evaluated ?: default

        var fireDeprecated = false
        synchronized(lock) {
            memoizationCache.set(cacheKey, result)
            if (config.flags[key]?.deprecated == true) {
                if (reportedDeprecatedConfigVersion != config.configVersion) {
                    reportedDeprecatedConfigVersion = config.configVersion
                    reportedDeprecatedReads.clear()
                }
                if (reportedDeprecatedReads.add(key)) fireDeprecated = true
            }
        }
        if (evaluated == null) eventsListener?.didFallbackToDefault(key)
        if (fireDeprecated) eventsListener?.didReadDeprecatedFlag(key)
        return result
    }

    private fun convertOverrideToFlagValue(override: OverrideValue, type: FlagType): FlagValue? = when {
        override is OverrideValue.BoolValue && type == FlagType.BOOL -> FlagValue.BoolValue(override.value)
        override is OverrideValue.IntValue && type == FlagType.INT -> FlagValue.IntValue(override.value)
        override is OverrideValue.DoubleValue && type == FlagType.DOUBLE -> FlagValue.DoubleValue(override.value)
        override is OverrideValue.StringValue && type == FlagType.STRING -> FlagValue.StringValue(override.value)
        override is OverrideValue.StringValue && type == FlagType.JSON -> FlagValue.JsonValue(override.value)
        override is OverrideValue.DateValue && type == FlagType.DATE -> FlagValue.DateValue(Instant.ofEpochMilli(override.epochMillis))
        else -> null
    }

    // MARK: - Refresh

    /** Fetches from the endpoint (rate-limited by the store), swallowing errors and
     *  retaining the last-known-good config, then republishes state. */
    suspend fun refresh() {
        try {
            configStore.refresh()
        } catch (_: Exception) {
            // Network/HTTP/verification failures keep the previous config, per Swift.
        }
        applyConfigState(configStore.getConfigState())
    }

    private fun applyConfigState(cs: ConfigStore.ConfigState) {
        snapshotRef.updateAndGet {
            it.copy(
                configuration = cs.configuration,
                source = cs.source,
                signatureVerified = cs.signatureVerified,
                lastFetchTimeMillis = cs.lastFetchTimeMillis,
                etag = cs.etag,
            )
        }
        synchronized(lock) { memoizationCache.invalidateAll() }
        publishState()
    }

    // MARK: - Environment

    fun setEnvironment(newEnvironment: BuntingEnvironment) {
        if (snapshotRef.get().environment == newEnvironment) return
        snapshotRef.updateAndGet { it.copy(environment = newEnvironment) }
        synchronized(lock) { memoizationCache.invalidateAll() }
        publishState()
    }

    // MARK: - Overrides

    fun getAllOverrides(): Map<String, OverrideValue> = snapshotRef.get().overrides

    fun setOverride(key: String, value: OverrideValue?) {
        snapshotRef.updateAndGet {
            val overrides = if (value == null) it.overrides - key else it.overrides + (key to value)
            it.copy(overrides = overrides, overridesVersion = it.overridesVersion + 1)
        }
        eventsListener?.didChangeOverride(key, value)
        synchronized(lock) { memoizationCache.invalidate(key) }
        options.scope.launch { overridesStore.setOverride(key, value) }
        publishState()
    }

    fun clearOverride(key: String) = setOverride(key, null)

    fun clearAllOverrides() {
        val cleared = snapshotRef.get().overrides.keys.toList()
        snapshotRef.updateAndGet { it.copy(overrides = emptyMap(), overridesVersion = it.overridesVersion + 1) }
        cleared.forEach { eventsListener?.didChangeOverride(it, null) }
        synchronized(lock) { memoizationCache.invalidateAll() }
        options.scope.launch { overridesStore.clearAll() }
        publishState()
    }

    // MARK: - Identity

    suspend fun resetIdentity() {
        val fresh = identity.resetIdentity()
        snapshotRef.updateAndGet { it.copy(localId = fresh) }
        synchronized(lock) { memoizationCache.invalidateAll() }
        publishState()
    }

    // MARK: - State plumbing

    private fun publishState() { mutableState.value = snapshotRef.get().toState() }

    private fun Snapshot.toState() = BuntingState(
        environment = environment,
        configuration = configuration,
        configVersion = configuration?.configVersion,
        publishedAt = configuration?.publishedAt,
        signatureVerified = signatureVerified,
        configSource = source,
        lastFetchTimeMillis = lastFetchTimeMillis,
        etag = etag,
        localId = localId,
        overrides = overrides,
    )
}
