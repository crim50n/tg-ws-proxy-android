package dev.minios.tgwsproxy.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.minios.tgwsproxy.BuildConfig
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.diagnostics.analyzeConnectionMode
import dev.minios.tgwsproxy.diagnostics.requiresProxyRestart
import dev.minios.tgwsproxy.proxy.*
import dev.minios.tgwsproxy.service.ProxyService
import dev.minios.tgwsproxy.update.UpdateRepository
import dev.minios.tgwsproxy.update.UpdatePolicy
import dev.minios.tgwsproxy.update.UpdateState

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepo = ConfigRepository(application)
    private val updateRepository = UpdateRepository(application)
    private val _configLoaded = MutableStateFlow(false)

    val config: StateFlow<ProxyConfig> = configRepo.configFlow
        .onEach { _configLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProxyConfig())
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    val runtimeRouteMode = ProxyService.runtimeRouteMode

    private val _stats = MutableStateFlow(StatsSnapshot())
    val stats: StateFlow<StatsSnapshot> = _stats.asStateFlow()

    private val _cfTestResult = MutableStateFlow<String?>(null)
    val cfTestResult: StateFlow<String?> = _cfTestResult.asStateFlow()
    val diagnosticState = DiagnosticLogger.state
    val diagnosticEntries = DiagnosticLogger.entries
    val connectionAdvice = combine(config, diagnosticEntries) { currentConfig, entries ->
        if (currentConfig.autoOptimizeConnection) {
            null
        } else {
            analyzeConnectionMode(entries, currentConfig.cfProxyEnabled, currentConfig.cfProxyFirst)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val _updateState = MutableStateFlow(updateRepository.cachedState())
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    // Save-and-restart warning message.
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var statsJob: Job? = null
    private var settingsStartConfig: ProxyConfig? = null

    init {
        DiagnosticLogger.initialize(application)
        checkForUpdates(manual = false)
        // Monitor service state
        viewModelScope.launch {
            while (isActive) {
                DiagnosticLogger.refresh()
                _isRunning.value = ProxyService.isRunning
                if (_isRunning.value) {
                    _stats.value = ProxyStats.snapshot()
                }
                delay(1000)
            }
        }
    }

    fun startProxy() {
        if (_isRunning.value || ProxyService.isRunning) return
        val ctx = getApplication<Application>()
        ProxyService.start(ctx)
    }

    fun stopProxy() {
        val ctx = getApplication<Application>()
        ProxyService.stop(ctx)
        _isRunning.value = false
    }

    fun saveConfig(newConfig: ProxyConfig) {
        viewModelScope.launch {
            configRepo.saveConfig(newConfig)
        }
    }

    fun beginSettings() {
        if (settingsStartConfig == null) settingsStartConfig = config.value
    }

    fun finishSettings(latestConfig: ProxyConfig) {
        val initialConfig = settingsStartConfig ?: config.value
        settingsStartConfig = null
        val runtimeChanged = requiresProxyRestart(initialConfig, latestConfig)
        viewModelScope.launch {
            configRepo.saveConfig(latestConfig)
            if (runtimeChanged && (_isRunning.value || ProxyService.isRunning)) {
                ProxyService.restart(getApplication<Application>())
            }
        }
    }

    fun updateAppearance(appTheme: String, dynamicColor: Boolean) {
        viewModelScope.launch {
            configRepo.saveAppearance(appTheme, dynamicColor)
        }
    }

    fun regenerateSecret(): String {
        return ProxyConfig.generateSecret()
    }

    fun checkForUpdates(manual: Boolean = true) {
        if (_updateState.value is UpdateState.Checking) return
        if (!manual && !updateRepository.shouldCheck()) return
        val cachedAvailable = _updateState.value as? UpdateState.Available
        if (manual) _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            updateRepository.check().fold(
                onSuccess = { manifest ->
                    val updateAvailable = UpdatePolicy.isAvailable(
                        BuildConfig.VERSION_CODE,
                        BuildConfig.VERSION_NAME,
                        manifest,
                    )
                    _updateState.value = if (updateAvailable) {
                        UpdateState.Available(manifest)
                    } else if (manual) {
                        UpdateState.UpToDate
                    } else {
                        UpdateState.Idle
                    }
                    DiagnosticLogger.event(
                        "update_check_completed",
                        "available" to updateAvailable,
                        "remoteVersion" to manifest.versionName,
                    )
                },
                onFailure = { error ->
                    _updateState.value = cachedAvailable ?: if (manual) UpdateState.Error else UpdateState.Idle
                    DiagnosticLogger.failure("update_check_failed", error)
                },
            )
        }
    }

    fun openAvailableUpdate() {
        val manifest = (_updateState.value as? UpdateState.Available)?.release ?: return
        val ctx = getApplication<Application>()
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(manifest.releaseUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (_: Exception) {
            _toastMessage.tryEmit(ctx.getString(R.string.update_open_failed))
        }
    }

    fun startDiagnostics() = DiagnosticLogger.start()

    fun stopDiagnostics() = DiagnosticLogger.stop()

    fun clearDiagnostics() = DiagnosticLogger.clear()

    fun exportDiagnostics() {
        val ctx = getApplication<Application>()
        val file = DiagnosticLogger.createExportFile() ?: return
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(
                Intent.createChooser(shareIntent, ctx.getString(R.string.diagnostics_export)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        } catch (_: Exception) {
            _toastMessage.tryEmit(ctx.getString(R.string.diagnostics_export_failed))
        }
    }

    fun copyProxyLink() {
        val ctx = getApplication<Application>()
        val cfg = config.value
        val link = cfg.proxyLink()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Proxy Link", link))
        Toast.makeText(ctx, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    fun copySecret(secret: String) {
        val ctx = getApplication<Application>()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Secret", "dd$secret"))
        Toast.makeText(ctx, R.string.secret_copied, Toast.LENGTH_SHORT).show()
    }

    fun openInTelegram() {
        val ctx = getApplication<Application>()
        val cfg = config.value
        val link = cfg.proxyLink()
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.telegram_not_found, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Test every CF domain rather than only the active one,
     * test each one, find the best. Matches Python behavior where all domains
     * are tested to find working ones.
     */
    fun testCfProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            _cfTestResult.value = getApplication<Application>().getString(R.string.settings_cf_testing)
            val results = StringBuilder()
            val cfg = config.value

            val allDomains = cfg.getAllCfDomains()
            if (allDomains.isEmpty()) {
                results.appendLine(getApplication<Application>().getString(R.string.cf_test_no_domains))
                _cfTestResult.value = results.toString()
                return@launch
            }

            // Test each domain against each DC
            var bestDomain: String? = null
            var bestTime = Long.MAX_VALUE

            for (domain in allDomains) {
                results.appendLine("--- $domain ---")
                var domainWorking = false
                var domainTotalMs = 0L

                for (dc in listOf(1, 2, 3, 4, 5)) {
                    try {
                        val start = System.currentTimeMillis()
                        val ws = RawWebSocket.connectToDc(
                            dc = dc,
                            isMedia = false,
                            targetIp = domain,
                            cfProxyDomain = domain,
                            connectTimeoutMs = 8000,
                        )
                        val elapsed = System.currentTimeMillis() - start
                        ws.close()
                        results.appendLine(
                            getApplication<Application>().getString(R.string.cf_test_success, dc, elapsed.toInt())
                        )
                        domainWorking = true
                        domainTotalMs += elapsed
                    } catch (e: Exception) {
                        results.appendLine(
                            getApplication<Application>().getString(R.string.cf_test_fail, dc)
                        )
                    }
                }

                if (domainWorking && domainTotalMs < bestTime) {
                    bestTime = domainTotalMs
                    bestDomain = domain
                }
            }

            // Update active domain to the best one found
            if (bestDomain != null) {
                for (dc in listOf(1, 2, 3, 4, 5)) {
                    CfProxyDomains.setActiveDomain(dc, bestDomain)
                }
                results.appendLine()
                results.appendLine(
                    getApplication<Application>().getString(R.string.cf_test_best, bestDomain)
                )
            }

            results.appendLine(getApplication<Application>().getString(R.string.cf_test_done))
            _cfTestResult.value = results.toString()
        }
    }

    fun clearCfTestResult() {
        _cfTestResult.value = null
    }
}
