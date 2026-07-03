package com.bunting.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.model.FlagType
import com.bunting.storage.OverrideValue
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Type-specific override editor for one flag (Swift `BuntingDebugView` row editors +
 * `JSONEditorView`). Emits null from `onSetOverride` to clear.
 */
@Composable
internal fun OverrideEditor(
    info: FlagInfo,
    onSetOverride: (OverrideValue?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (info.type) {
            FlagType.BOOL -> BoolEditor(info, onSetOverride)
            FlagType.INT -> TextEditor(info, "Override (integer)", onSetOverride) { text ->
                text.toLongOrNull()?.let { OverrideValue.IntValue(it) }
            }
            FlagType.DOUBLE -> TextEditor(info, "Override (double)", onSetOverride) { text ->
                text.toDoubleOrNull()?.let { OverrideValue.DoubleValue(it) }
            }
            FlagType.STRING -> TextEditor(info, "Override (string)", onSetOverride) { text ->
                OverrideValue.StringValue(text)
            }
            FlagType.DATE -> TextEditor(info, "Override (ISO-8601 instant)", onSetOverride) { text ->
                try {
                    OverrideValue.DateValue(Instant.parse(text).toEpochMilli())
                } catch (_: DateTimeParseException) {
                    null
                }
            }
            FlagType.JSON -> TextEditor(info, "Override (JSON)", onSetOverride, singleLine = false) { text ->
                val trimmed = text.trim()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    OverrideValue.StringValue(text)
                } else {
                    null
                }
            }
        }
        if (info.hasOverride) {
            TextButton(onClick = { onSetOverride(null) }) { Text("Clear override") }
        }
    }
}

@Composable
private fun BoolEditor(info: FlagInfo, onSetOverride: (OverrideValue?) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Override value", style = MaterialTheme.typography.bodyMedium)
        Switch(
            checked = info.effectiveValue.toBoolean(),
            onCheckedChange = { checked -> onSetOverride(OverrideValue.BoolValue(checked)) },
        )
    }
}

/** Shared text-field editor; `parse` returning null keeps the Apply button disabled. */
@Composable
private fun TextEditor(
    info: FlagInfo,
    label: String,
    onSetOverride: (OverrideValue?) -> Unit,
    singleLine: Boolean = true,
    parse: (String) -> OverrideValue?,
) {
    var text by remember(info.key) { mutableStateOf(info.effectiveValue) }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = singleLine,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { parse(text)?.let { onSetOverride(it) } },
            enabled = parse(text) != null,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Apply override")
        }
    }
}
