package com.bunting.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.bunting.BuntingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Refreshes the config each time the app process enters the foreground. Registered
 * on `ProcessLifecycleOwner`, so `onStart` fires once per foreground transition
 * (mirroring the Swift SDK's willEnterForeground observer).
 */
class ForegroundRefreshObserver(
    private val client: BuntingClient,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        scope.launch { client.refresh() }
    }
}
