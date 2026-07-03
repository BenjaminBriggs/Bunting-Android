package com.bunting.example.local

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.Bunting
import com.bunting.debug.BuntingDebugScreen
import kotlinx.coroutines.launch
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    var showDebug by remember { mutableStateOf(false) }
                    BackHandler(enabled = showDebug) { showDebug = false }
                    if (showDebug) {
                        BuntingDebugScreen()
                    } else {
                        LocalStatusScreen(onOpenDebug = { showDebug = true })
                    }
                }
            }
        }
    }
}

/**
 * Dev harness against a locally running bunting-admin: shows exactly what the
 * SDK fetched from the local MinIO endpoint and drops into the debug panel.
 */
@Composable
private fun LocalStatusScreen(onOpenDebug: () -> Unit) {
    val state by Bunting.state.collectAsState()
    val scope = rememberCoroutineScope()
    val keysMissing = state.configuration == null && state.configVersion == null

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
    ) {
        Text("Bunting Local", style = MaterialTheme.typography.headlineSmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                Text("Version: ${state.configVersion ?: "not loaded"}")
                Text("Source: ${state.configSource?.name?.lowercase() ?: "none"}")
                Text("Signature verified: ${state.signatureVerified}")
                Text("Environment: ${state.environment.name.lowercase()}")
                Text("Flags: ${state.configuration?.flags?.size ?: 0}")
                Text(
                    "Last fetch: ${state.lastFetchTimeMillis?.let { Instant.ofEpochMilli(it) } ?: "—"}",
                )
            }
        }
        if (keysMissing) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No config loaded. Is the admin stack running, and did you generate " +
                        "assets/bunting_config.json with scripts/local-bootstrap.sh?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        Button(onClick = { scope.launch { Bunting.instance.refresh() } }) {
            Text("Refresh from local admin")
        }
        OutlinedButton(onClick = onOpenDebug) {
            Text("Debug View")
        }
    }
}
