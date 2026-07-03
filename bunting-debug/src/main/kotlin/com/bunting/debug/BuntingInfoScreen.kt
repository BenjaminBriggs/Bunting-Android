package com.bunting.debug

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.Bunting
import com.bunting.BuntingClient
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

/**
 * Read-only flag inspector (Swift `BuntingInfoView`): searchable, namespace-grouped
 * flag list with per-flag effective values, pull-to-refresh, and a details sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
public fun BuntingInfoScreen(
    client: BuntingClient = Bunting.instance,
    modifier: Modifier = Modifier,
) {
    val state by client.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var showDetails by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val flags = FlagInfo.fromState(state, client)
    val groups = FlagInfo.grouped(flags, query)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Bunting") },
                actions = {
                    TextButton(onClick = { showDetails = true }) { Text("Details") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    client.refresh()
                    refreshing = false
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search flags") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (flags.isEmpty()) {
                    item {
                        Text(
                            "No configuration loaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
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
                            FlagRow(info, showSourceBadge = false)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }

    if (showDetails) {
        BuntingDetailsSheet(
            state = state,
            onDismiss = { showDetails = false },
            userFingerprint = client.userFingerprint,
        )
    }
}
