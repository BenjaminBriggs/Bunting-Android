package com.bunting.storage

import com.bunting.model.ArtifactDecoder
import com.bunting.model.ArtifactRejectedException
import com.bunting.model.BuntingConfiguration
import com.bunting.model.BuntingJson
import com.bunting.model.ConfigSource
import com.bunting.verify.JwsVerifier
import com.bunting.verify.SignatureTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.util.logging.Logger

class ConfigStoreException(message: String) : Exception(message)

/**
 * Fetches, verifies, decodes, and caches the config artifact. A Mutex serialises
 * all state access (the coroutine analogue of the Swift actor). Ordering guarantees:
 * conditional GET, 304-as-success, verify → decode → cache, signature-first atomic
 * writes, and cache re-verification on load.
 *
 * Decoding follows the v1 wire contract ([ArtifactDecoder]): entries it skips or
 * drops are logged, and only a bad header rejects an artifact. A rejected fetch
 * keeps the active config and reports a failed fetch.
 */
class ConfigStore(
    private val bootstrap: BootstrapConfig,
    filesDir: File,
    private val httpGet: suspend (String, Map<String, String>) -> HttpResponse = Http::get,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val seedLoader: (() -> ByteArray?)? = null,
    private val listener: ConfigStoreListener? = null,
) {
    private val mutex = Mutex()
    private val logger = Logger.getLogger("com.bunting.storage.ConfigStore")

    private var activeConfig: BuntingConfiguration? = null
    private var activeSource: ConfigSource? = null
    private var activeSignatureVerified = false
    private var lastFetchTimeMillis: Long? = null
    private var cachedETag: String? = null
    private var cachedLastModified: String? = null
    private var lastTtlRefreshMillis: Long? = null
    private var lastSuccessfulFetchMillis: Long? = null

    private val cacheDir = File(filesDir, "Bunting").apply { mkdirs() }
    private val configFile = File(cacheDir, "config_v1.json")
    private val signatureFile = File(cacheDir, "config_v1.json.sig")
    private val metadataFile = File(cacheDir, "metadata.json")

    data class ConfigState(
        val configuration: BuntingConfiguration?,
        val source: ConfigSource?,
        val signatureVerified: Boolean,
        val lastFetchTimeMillis: Long?,
        val etag: String?,
    )

    suspend fun getConfigState(): ConfigState = mutex.withLock {
        ConfigState(activeConfig, activeSource, activeSignatureVerified, lastSuccessfulFetchMillis, cachedETag)
    }

    /** Loads the on-disk cache into memory if nothing is active yet, falling back to
     *  the bundled seed. The cache always wins over the seed; a corrupt seed is
     *  ignored without crashing (mirroring Swift's loadBundledSeed placement). */
    suspend fun loadCachedConfigIfNeeded() = mutex.withLock {
        if (activeConfig != null) return@withLock
        if (loadCachedConfig() == false) loadSeed()
        Unit
    }

    /** Decodes the optional bundled seed (source = SEED, unsigned). A missing or
     *  corrupt seed leaves the store empty rather than throwing; a corrupt one is logged. */
    private fun loadSeed() {
        val bytes = seedLoader?.invoke() ?: return
        val config = try {
            decodeArtifact(bytes, "Bundled seed")
        } catch (e: Exception) {
            logger.warning("Bundled seed config could not be decoded and was ignored: ${describe(e)}")
            return
        }
        activeConfig = config
        activeSource = ConfigSource.SEED
        activeSignatureVerified = false
        listener?.didLoadCachedConfig(config.configVersion)
    }

    /** Refreshes from the endpoint, honouring rate limiting and the hard TTL. */
    suspend fun refresh() = mutex.withLock {
        lastFetchTimeMillis?.let { last ->
            val elapsedSeconds = (nowMillis() - last) / 1000.0
            if (elapsedSeconds < bootstrap.fetchPolicy.minIntervalSeconds) return@withLock
        }
        val forceRefresh = lastTtlRefreshMillis?.let { last ->
            (nowMillis() - last) / 86_400_000.0 >= bootstrap.fetchPolicy.hardTtlDays
        } ?: true
        performFetch(forceRefresh)
    }

    private suspend fun performFetch(forceRefresh: Boolean) {
        val headers = HashMap<String, String>()
        if (forceRefresh == false) {
            cachedETag?.let { headers["If-None-Match"] = it }
            cachedLastModified?.let { headers["If-Modified-Since"] = it }
        }
        listener?.didStartFetch(bootstrap.endpointUrl)
        // Every didStartFetch is paired with exactly one didCompleteFetch.
        val response = try {
            httpGet(bootstrap.endpointUrl, headers)
        } catch (e: Exception) {
            lastFetchTimeMillis = nowMillis()
            listener?.didCompleteFetch(false, e)
            throw e
        }
        lastFetchTimeMillis = nowMillis()

        if (response.statusCode == 304) {
            // A 304 proves contact and confirms the cache is current, so it is a success.
            lastSuccessfulFetchMillis = nowMillis()
            if (forceRefresh) { lastTtlRefreshMillis = nowMillis(); saveMetadata() }
            listener?.didCompleteFetch(true, null)
            return
        }
        if (response.statusCode != 200) {
            val error = ConfigStoreException("HTTP ${response.statusCode}")
            listener?.didCompleteFetch(false, error)
            throw error
        }

        val data = response.body
        val jws = response.header(SignatureTransport.HEADER_NAME) ?: fetchSignatureSibling()
        if (jws == null) {
            listener?.didVerifySignature(false)
            val error = ConfigStoreException("No signature available for config")
            listener?.didCompleteFetch(false, error)
            throw error
        }

        // verify → decode → cache, in that order.
        try {
            JwsVerifier.verifyDetached(jws, data, bootstrap.publicKeys)
        } catch (e: Exception) {
            listener?.didVerifySignature(false)
            listener?.didCompleteFetch(false, e)
            throw e
        }
        listener?.didVerifySignature(true)

        val newConfig = try {
            decodeArtifact(data, "Fetched")
        } catch (e: Exception) {
            // The artifact is authentic but unusable (bad header). Keep the active config,
            // and record the attempt so the next refresh waits for the normal schedule:
            // the rate limit survives a restart, and a completed forced fetch doesn't
            // force another full GET on every refresh.
            logger.warning("Fetched config was rejected; keeping the current config: ${describe(e)}")
            if (forceRefresh) lastTtlRefreshMillis = nowMillis()
            saveMetadata()
            listener?.didCompleteFetch(false, e)
            throw e
        }
        saveToCache(data, jws)

        activeConfig = newConfig
        activeSource = ConfigSource.FETCHED
        activeSignatureVerified = true
        lastTtlRefreshMillis = nowMillis()
        cachedETag = response.header("ETag")
        cachedLastModified = response.header("Last-Modified")
        lastSuccessfulFetchMillis = nowMillis()
        saveMetadata()
        listener?.didCompleteFetch(true, null)
    }

    private suspend fun fetchSignatureSibling(): String? {
        val resp = httpGet(SignatureTransport.sigUrl(bootstrap.endpointUrl), emptyMap())
        if (resp.statusCode != 200) return null
        return resp.body.toString(Charsets.UTF_8).trim().ifEmpty { null }
    }

    /** Loads and re-verifies the cache. Returns true if it became the active config. */
    private fun loadCachedConfig(): Boolean {
        if (configFile.exists() == false) return false
        val data = configFile.readBytes()
        val jws = if (signatureFile.exists()) signatureFile.readText().trim() else null
        if (jws == null) { deleteCacheFiles(); return false }
        try {
            JwsVerifier.verifyDetached(jws, data, bootstrap.publicKeys)
        } catch (_: Exception) {
            deleteCacheFiles(); return false
        }
        val config = try {
            decodeArtifact(data, "Cached")
        } catch (e: Exception) {
            logger.warning("Cached config could not be decoded and was discarded: ${describe(e)}")
            deleteCacheFiles(); return false
        }
        activeConfig = config
        activeSource = ConfigSource.CACHE
        activeSignatureVerified = true
        loadMetadata()
        listener?.didLoadCachedConfig(config.configVersion)
        return true
    }

    /** Decodes by the v1 wire contract, logging every entry that was skipped, dropped,
     *  or neutralised. Throws [ArtifactRejectedException] for a bad header. */
    private fun decodeArtifact(data: ByteArray, origin: String): BuntingConfiguration {
        val result = ArtifactDecoder.decode(data.toString(Charsets.UTF_8))
        for (issue in result.issues) {
            logger.warning("$origin config ${result.configuration.configVersion}: $issue")
        }
        return result.configuration
    }

    private fun describe(e: Exception): String =
        (e as? ArtifactRejectedException)?.reason ?: e.message ?: e.javaClass.simpleName

    /** Signature written first: a crash mid-write yields a stale-config/new-sig pair
     *  that fails re-verification, never a silently unverified config. */
    private fun saveToCache(data: ByteArray, signature: String) {
        atomicWrite(signatureFile, signature.toByteArray(Charsets.UTF_8))
        atomicWrite(configFile, data)
    }

    private fun deleteCacheFiles() { configFile.delete(); signatureFile.delete() }

    @Serializable
    private data class CacheMetadata(
        val etag: String? = null,
        @SerialName("last_modified") val lastModified: String? = null,
        @SerialName("last_fetch_time_millis") val lastFetchTimeMillis: Long? = null,
        @SerialName("last_ttl_refresh_millis") val lastTtlRefreshMillis: Long? = null,
    )

    private fun loadMetadata() {
        if (metadataFile.exists() == false) return
        val meta = try {
            BuntingJson.decodeFromString(CacheMetadata.serializer(), metadataFile.readText())
        } catch (_: Exception) { return }
        cachedETag = meta.etag
        cachedLastModified = meta.lastModified
        lastFetchTimeMillis = meta.lastFetchTimeMillis
        lastTtlRefreshMillis = meta.lastTtlRefreshMillis
    }

    private fun saveMetadata() {
        val meta = CacheMetadata(cachedETag, cachedLastModified, lastFetchTimeMillis, lastTtlRefreshMillis)
        atomicWrite(metadataFile, BuntingJson.encodeToString(CacheMetadata.serializer(), meta).toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        if (tmp.renameTo(target) == false) { target.writeBytes(bytes); tmp.delete() }
    }
}
