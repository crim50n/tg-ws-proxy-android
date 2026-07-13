package dev.minios.tgwsproxy.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.ui.screens.MainScreen
import dev.minios.tgwsproxy.ui.screens.SettingsScreen
import dev.minios.tgwsproxy.ui.screens.AboutScreen
import dev.minios.tgwsproxy.ui.theme.TgWsProxyTheme

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(this, "Notifications needed for foreground service", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            TgWsProxyTheme {
                val viewModel: MainViewModel = viewModel()
                AppNavigation(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun AppNavigation(viewModel: MainViewModel) {
    val config by viewModel.config.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val cfTestResult by viewModel.cfTestResult.collectAsState()

    // Handle transient messages from the view model.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

    when (currentScreen) {
        Screen.Main -> {
            MainScreen(
                config = config,
                isRunning = isRunning,
                stats = stats,
                onStart = { viewModel.startProxy() },
                onStop = { viewModel.stopProxy() },
                onRestart = { viewModel.restartProxy() },
                onOpenInTelegram = { viewModel.openInTelegram() },
                onCopyLink = { viewModel.copyProxyLink() },
                onCopySecret = { viewModel.copySecret() },
                onOpenSettings = { currentScreen = Screen.Settings },
                onOpenAbout = { currentScreen = Screen.About },
            )
        }
        Screen.Settings -> {
            BackHandler { 
                viewModel.clearCfTestResult()
                currentScreen = Screen.Main
            }
            SettingsScreen(
                config = config,
                cfTestResult = cfTestResult,
                onSave = { newConfig ->
                    viewModel.saveConfig(newConfig)
                    currentScreen = Screen.Main
                },
                onBack = {
                    viewModel.clearCfTestResult()
                    currentScreen = Screen.Main
                },
                onRegenerateSecret = { viewModel.regenerateSecret() },
                onTestCfProxy = { viewModel.testCfProxy() },
                onClearCfTestResult = { viewModel.clearCfTestResult() },
            )
        }
        Screen.About -> {
            BackHandler { currentScreen = Screen.Main }
            AboutScreen(
                onBack = { currentScreen = Screen.Main },
            )
        }
    }
}

private sealed class Screen {
    data object Main : Screen()
    data object Settings : Screen()
    data object About : Screen()
}
