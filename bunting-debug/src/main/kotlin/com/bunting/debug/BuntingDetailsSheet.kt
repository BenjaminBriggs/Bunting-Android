package com.bunting.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.BuntingClient
import com.bunting.BuntingState
import com.bunting.model.BuntingEnvironment
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Sheet with the configuration/session details (Swift `BuntingDetailsSheet`).
 * When [client] is provided (the debug view), it also hosts the environment
 * switcher and the refresh / clear-overrides / reset-identity actions; the
 * info view passes no client and stays read-only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BuntingDetailsSheet(
    state: BuntingState,
    onDismiss: () -> Unit,
    client: BuntingClient? = null,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            if (client != null) {
                DebugControls(state, client)
            }
            Text("Configuration", style = MaterialTheme.typography.titleMedium)
            DetailRow("Environment", state.environment.name.lowercase())
            DetailRow("Config version", state.configVersion ?: "—")
            DetailRow("Published at", state.publishedAt ?: "—")
            DetailRow("Signature verified", state.signatureVerified.toString())
            DetailRow("Source", state.configSource?.name?.lowercase() ?: "—")
            DetailRow("Local ID", state.localId?.toString() ?: "—")
            DetailRow(
                "Last fetch",
                state.lastFetchTimeMillis?.let { Instant.ofEpochMilli(it).toString() } ?: "—",
            )
            DetailRow("ETag", state.etag ?: "—")
        }
    }
}

/** Environment switcher + debug actions, shown only when opened from the debug view. */
@Composable
private fun DebugControls(state: BuntingState, client: BuntingClient) {
    var confirmClearAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val environments = BuntingEnvironment.entries

    Text(
        "Debug",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp),
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        environments.forEachIndexed { index, environment ->
            SegmentedButton(
                selected = state.environment == environment,
                onClick = { client.setEnvironment(environment) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = environments.size),
            ) {
                Text(environment.name.lowercase())
            }
        }
    }
    Row {
        TextButton(onClick = { scope.launch { client.refresh() } }) { Text("Refresh") }
        TextButton(onClick = { confirmClearAll = true }) { Text("Clear all overrides") }
        TextButton(onClick = { scope.launch { client.resetIdentity() } }) { Text("Reset identity") }
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Clear all overrides?") },
            text = { Text("Every flag returns to its evaluated value.") },
            confirmButton = {
                TextButton(onClick = {
                    client.clearAllOverrides()
                    confirmClearAll = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
