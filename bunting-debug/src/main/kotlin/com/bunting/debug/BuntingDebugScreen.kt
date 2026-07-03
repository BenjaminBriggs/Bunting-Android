package com.bunting.debug

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.bunting.BuntingClient
import com.bunting.model.BuntingEnvironment
import kotlinx.coroutines.launch

/**
 * Interactive debug panel (Swift `BuntingDebugView`): the info list plus per-flag
 * override editing, clear-all, manual refresh, reset identity, and an environment
 * switcher.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BuntingDebugScreen(
    client: BuntingClient = Bunting.instance,
    modifier: Modifier = Modifier,
) {
    val state by client.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    val flags = FlagInfo.fromState(state, client)
    val groups = FlagInfo.grouped(flags, query)
    val environments = BuntingEnvironment.entries

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Bunting Debug") },
                actions = {
                    TextButton(onClick = { showDetails = true }) { Text("Details") }
                },
            )
        },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
            item {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            }
            item {
                Row(modifier = Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = { scope.launch { client.refresh() } }) { Text("Refresh") }
                    TextButton(onClick = { confirmClearAll = true }) { Text("Clear all overrides") }
                    TextButton(onClick = { scope.launch { client.resetIdentity() } }) { Text("Reset identity") }
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search flags") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            groups.forEach { (namespace, infos) ->
                item(key = "header-$namespace") {
                    Text(
                        text = namespace.ifEmpty { "flags" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                    )
                }
                infos.forEach { info ->
                    item(key = info.key) {
                        Column {
                            FlagRow(
                                info = info,
                                showSourceBadge = true,
                                modifier = Modifier.clickable {
                                    expandedKey = if (expandedKey == info.key) null else info.key
                                },
                            )
                            if (expandedKey == info.key) {
                                OverrideEditor(
                                    info = info,
                                    onSetOverride = { value -> client.setOverride(info.key, value) },
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
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

    if (showDetails) {
        BuntingDetailsSheet(state = state, onDismiss = { showDetails = false })
    }
}
