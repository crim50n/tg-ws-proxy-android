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
            Toast.makeText(this, R.string.notification_permission_required, Toast.LENGTH_LONG).show()
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
    val diagnosticState by viewModel.diagnosticState.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    // Handle transient messages from the view model.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.toastMessage.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }
    val closeSettings = {
        viewModel.clearCfTestResult()
        currentScreen = Screen.Main
    }

    when (currentScreen) {
        Screen.Main -> {
            MainScreen(
                config = config,
                isRunning = isRunning,
                stats = stats,
                updateState = updateState,
                onStart = { viewModel.startProxy() },
                onStop = { viewModel.stopProxy() },
                onOpenInTelegram = { viewModel.openInTelegram() },
                onCopyLink = { viewModel.copyProxyLink() },
                onOpenSettings = { currentScreen = Screen.Settings },
                onOpenAbout = { currentScreen = Screen.About },
                onOpenUpdate = { viewModel.openAvailableUpdate() },
            )
        }
        Screen.Settings -> {
            BackHandler(onBack = closeSettings)
            SettingsScreen(
                config = config,
                cfTestResult = cfTestResult,
                diagnosticState = diagnosticState,
                onSave = { newConfig ->
                    viewModel.saveConfig(newConfig)
                    closeSettings()
                },
                onBack = closeSettings,
                onRegenerateSecret = { viewModel.regenerateSecret() },
                onCopySecret = { viewModel.copySecret(it) },
                onTestCfProxy = { viewModel.testCfProxy() },
                onStartDiagnostics = { viewModel.startDiagnostics() },
                onStopDiagnostics = { viewModel.stopDiagnostics() },
                onExportDiagnostics = { viewModel.exportDiagnostics() },
                onClearDiagnostics = { viewModel.clearDiagnostics() },
            )
        }
        Screen.About -> {
            BackHandler { currentScreen = Screen.Main }
            AboutScreen(
                updateState = updateState,
                onCheckForUpdates = { viewModel.checkForUpdates() },
                onOpenUpdate = { viewModel.openAvailableUpdate() },
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
