package com.bunting

import com.bunting.evaluation.ContextProvider
import com.bunting.model.BuntingEnvironment
import com.bunting.storage.BootstrapConfig
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * Everything the [BuntingClient] needs, injected for full JVM testability. The
 * Android artifact (Plan 3) builds this from `Context` + assets; tests build it
 * from a [com.bunting.evaluation.StaticContextProvider] and a `@TempDir`.
 *
 * @property scope the scope initial load, refresh persistence, and override
 *   persistence launch on; the caller owns its lifecycle.
 */
class BuntingOptions(
    val environment: BuntingEnvironment,
    val bootstrap: BootstrapConfig,
    val dataDir: File,
    val contextProvider: ContextProvider,
    val customAttributeResolver: (String) -> Boolean = { false },
    val seedLoader: (() -> ByteArray?)? = null,
    val scope: CoroutineScope,
    val nowMillis: () -> Long = System::currentTimeMillis,
)
