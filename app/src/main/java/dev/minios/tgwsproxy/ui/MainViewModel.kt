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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.proxy.*
import dev.minios.tgwsproxy.service.ProxyService

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val configRepo = ConfigRepository(application)

    val config: StateFlow<ProxyConfig> = configRepo.configFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProxyConfig())

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow(StatsSnapshot())
    val stats: StateFlow<StatsSnapshot> = _stats.asStateFlow()

    private val _cfTestResult = MutableStateFlow<String?>(null)
    val cfTestResult: StateFlow<String?> = _cfTestResult.asStateFlow()

    // #22: Save-and-restart warning message
    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    private var statsJob: Job? = null

    init {
        // Monitor service state
        viewModelScope.launch {
            while (isActive) {
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
        viewModelScope.launch {
            val cfg = configRepo.getConfig()
            ProxyService.start(ctx, cfg)
        }
    }

    fun stopProxy() {
        val ctx = getApplication<Application>()
        ProxyService.stop(ctx)
        _isRunning.value = false
    }

    fun restartProxy() {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            ProxyService.stop(ctx)
            _isRunning.value = false
            // #21: Match Python's restart delay (0.3s, not 2s)
            delay(300)
            startProxy()
        }
    }

    fun saveConfig(newConfig: ProxyConfig) {
        viewModelScope.launch {
            configRepo.saveConfig(newConfig)
            // #22: Show restart warning if proxy is running
            if (_isRunning.value) {
                _toastMessage.tryEmit(
                    getApplication<Application>().getString(R.string.settings_restart_required)
                )
            }
        }
    }

    fun regenerateSecret(): String {
        val newSecret = ProxyConfig.generateSecret()
        viewModelScope.launch {
            val cfg = configRepo.getConfig()
            configRepo.saveConfig(cfg.copy(secret = newSecret))
        }
        return newSecret
    }

    fun copyProxyLink() {
        val ctx = getApplication<Application>()
        val cfg = config.value
        val link = cfg.proxyLink()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("TG WS Proxy Link", link))
        Toast.makeText(ctx, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    fun copySecret() {
        val ctx = getApplication<Application>()
        val cfg = config.value
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Secret", "dd${cfg.secret}"))
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
            Toast.makeText(ctx, "Telegram not found", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * #14: Test CF proxy — iterate ALL CF domains (not just active),
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
                results.appendLine("CF proxy disabled or no domains available")
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
                CfProxyDomains.setActiveDomain(bestDomain)
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
