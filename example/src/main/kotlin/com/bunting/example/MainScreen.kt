package com.bunting.example

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bunting.Bunting
import com.bunting.example.flags.features
import com.bunting.example.flags.store
import com.bunting.example.flags.ui
import kotlinx.coroutines.launch

/**
 * Demonstrates the generated, strongly-typed accessors (`Bunting.ui.welcomeMessage`
 * etc. — mirroring Swift's `Bunting.shared.ui.welcomeMessage`), live state via
 * `Bunting.state`, and manual refresh.
 */
@Composable
fun MainScreen(
    onOpenInfo: () -> Unit,
    onOpenDebug: () -> Unit,
) {
    val state by Bunting.state.collectAsState()
    val uiState = ExampleUiState.fromState(state)
    val scope = rememberCoroutineScope()

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        // Generated accessors — reading these recomposes on state changes above.
        Text(Bunting.ui.welcomeMessage, style = MaterialTheme.typography.headlineSmall)
        Text("Theme color: ${Bunting.ui.themeColor}")
        Text("New paywall: ${Bunting.store.useNewPaywallDesign}")
        Text("Subscription discount: ${Bunting.store.subscriptionDiscount}%")
        Text("Max upload size: ${Bunting.features.maxUploadSize} MB")
        Text("Discount percentage: ${Bunting.features.discountPercentage}")

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium)
                Text("Version: ${uiState.configVersion}")
                Text("Source: ${uiState.source}")
                Text("Signature verified: ${uiState.signatureVerified}")
                Text("Environment: ${uiState.environment}")
            }
        }

        Button(onClick = { scope.launch { Bunting.instance.refresh() } }) {
            Text("Refresh Configuration")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenInfo) { Text("Info View") }
            OutlinedButton(onClick = onOpenDebug) { Text("Debug View") }
        }
    }
}
