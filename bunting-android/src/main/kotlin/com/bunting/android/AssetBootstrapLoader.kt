package com.bunting.android

import android.content.Context
import com.bunting.model.BuntingJson
import com.bunting.storage.BootstrapConfig
import java.io.FileNotFoundException

/**
 * Loads the bootstrap document and optional seed from the app's `assets/`.
 * The bootstrap's `endpoint_url` must end with `config.json` (the same contract
 * the conformance bootstrap set validates).
 */
object AssetBootstrapLoader {

    fun load(context: Context, assetName: String = "bunting_config.json"): BootstrapConfig {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        return parse(bytes, assetName)
    }

    /** Seed bytes, or null when the asset is absent (the seed is optional). */
    fun loadSeedBytes(context: Context, assetName: String): ByteArray? = try {
        context.assets.open(assetName).use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    internal fun parse(bytes: ByteArray, assetName: String): BootstrapConfig {
        val bootstrap = BuntingJson.decodeFromString(BootstrapConfig.serializer(), bytes.decodeToString())
        require(bootstrap.endpointUrl.endsWith("config.json")) {
            "Bootstrap asset '$assetName' endpoint_url must end with config.json, was '${bootstrap.endpointUrl}'"
        }
        return bootstrap
    }
}
