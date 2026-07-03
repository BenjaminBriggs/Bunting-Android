package com.bunting.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** One flag row: name, type, effective value, and (in debug) an Override/Evaluated badge. */
@Composable
internal fun FlagRow(
    info: FlagInfo,
    showSourceBadge: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = info.displayName,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (info.deprecated) TextDecoration.LineThrough else null,
            )
            if (showSourceBadge) {
                SuggestionChip(
                    onClick = {},
                    enabled = false,
                    label = {
                        Text(if (info.source == FlagInfo.FlagSource.OVERRIDE) "Override" else "Evaluated")
                    },
                )
            }
        }
        Text(
            text = "${info.type.name.lowercase()} · ${info.effectiveValue}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        info.description?.let { description ->
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
