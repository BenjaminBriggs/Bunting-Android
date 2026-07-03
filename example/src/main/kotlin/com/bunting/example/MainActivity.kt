package com.bunting.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bunting.debug.BuntingDebugScreen
import com.bunting.debug.BuntingInfoScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    ExampleNavigation()
                }
            }
        }
    }
}

private enum class Screen { HOME, INFO, DEBUG }

@Composable
private fun ExampleNavigation() {
    var screen by remember { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> MainScreen(
            onOpenInfo = { screen = Screen.INFO },
            onOpenDebug = { screen = Screen.DEBUG },
        )
        Screen.INFO -> BuntingInfoScreen()
        Screen.DEBUG -> BuntingDebugScreen()
    }
}
