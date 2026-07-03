package com.bunting.debug

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.BuntingState
import java.time.Instant

/** Read-only sheet with the configuration/session details (Swift `BuntingDetailsSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BuntingDetailsSheet(
    state: BuntingState,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
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
