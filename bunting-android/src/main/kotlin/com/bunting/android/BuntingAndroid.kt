package com.bunting.android

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import com.bunting.Bunting
import com.bunting.BuntingClient
import com.bunting.BuntingOptions
import com.bunting.model.BuntingEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope

/**
 * Configures the [Bunting] singleton for Android: loads the bootstrap (and optional
 * seed) from assets, wires the [AndroidContextProvider], and — when enabled —
 * registers a [ForegroundRefreshObserver] on `ProcessLifecycleOwner`. Call once from
 * `Application.onCreate`.
 *
 * @param seedAssetName the bundled last-known-good seed asset, or null to skip the seed.
 * @param scope the scope the initial load and foreground refreshes run on.
 */
fun Bunting.configure(
    context: Context,
    environment: BuntingEnvironment,
    customAttributes: (String) -> Boolean = { false },
    bootstrapAssetName: String = "bunting_config.json",
    seedAssetName: String? = "BuntingConfig.json",
    autoRefreshOnForeground: Boolean = true,
    scope: CoroutineScope = MainScope(),
): BuntingClient {
    val appContext = context.applicationContext
    val bootstrap = AssetBootstrapLoader.load(appContext, bootstrapAssetName)

    val options = BuntingOptions(
        environment = environment,
        bootstrap = bootstrap,
        dataDir = appContext.filesDir,
        contextProvider = AndroidContextProvider(appContext),
        customAttributeResolver = customAttributes,
        seedLoader = seedAssetName?.let { name -> { AssetBootstrapLoader.loadSeedBytes(appContext, name) } },
        scope = scope,
    )

    val client = configure(options)
    if (autoRefreshOnForeground) {
        ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundRefreshObserver(client, scope))
    }
    return client
}
