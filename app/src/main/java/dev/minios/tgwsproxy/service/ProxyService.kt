package dev.minios.tgwsproxy.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.diagnostics.ConnectionAdvice
import dev.minios.tgwsproxy.diagnostics.analyzeConnectionMode
import dev.minios.tgwsproxy.diagnostics.withConnectionAdvice
import dev.minios.tgwsproxy.diagnostics.withAutomaticRouteDefaults
import dev.minios.tgwsproxy.diagnostics.withAutomaticPerformanceDefaults
import dev.minios.tgwsproxy.diagnostics.withRuntimeConnectionPreferences
import dev.minios.tgwsproxy.proxy.CfProxyDomains
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyStats
import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.ProxyPortInUseException
import dev.minios.tgwsproxy.proxy.RouteHealthProbe
import dev.minios.tgwsproxy.proxy.RuntimeRouteMode
import dev.minios.tgwsproxy.proxy.runtimeRouteMode
import dev.minios.tgwsproxy.proxy.TgWsProxyServer
import dev.minios.tgwsproxy.ui.MainActivity

private const val TAG = "ProxyService"
private const val CHANNEL_ID = "tg_ws_proxy_service"
private const val ADVICE_CHANNEL_ID = "connection_recommendations"
private const val NOTIFICATION_ID = 1
private const val ADVICE_NOTIFICATION_ID = 2
private const val ACTION_STOP = "dev.minios.tgwsproxy.STOP"
private const val ACTION_RESTART = "dev.minios.tgwsproxy.RESTART"
private const val ACTION_RESTORE_NOTIFICATION = "dev.minios.tgwsproxy.RESTORE_NOTIFICATION"
private const val RUNTIME_STOP_TIMEOUT_MS = 5_000L

sealed interface ProxyStartFailure {
    data class PortInUse(val port: Int) : ProxyStartFailure
}

enum class ProxyServiceState { STOPPED, STARTING, RUNNING, RESTARTING, STOPPING }

internal val ProxyServiceState.acceptsStart: Boolean get() = this == ProxyServiceState.STOPPED
internal val ProxyServiceState.acceptsRestart: Boolean
    get() = this == ProxyServiceState.STARTING || this == ProxyServiceState.RUNNING

/**
 * Foreground service that runs the MTProto proxy server.
 */
class ProxyService : Service() {

    var server: TgWsProxyServer? = null
        private set
    private var serviceScope: CoroutineScope? = null
    private val lifecycleDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "proxy-lifecycle")
    }.asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(lifecycleDispatcher + SupervisorJob())
    private val runtimeMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverJob: Job? = null
    private var restartJob: Job? = null
    private var stopJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var routeProbeJob: Job? = null
    private var networkChangeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var automaticRouteOverride: ConnectionAdvice? = null
    private var runtimeGeneration = 0L
    private var networkSignature: String? = null
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var hasSeenNetwork = false
    @Volatile private var notificationHost = "127.0.0.1"
    @Volatile private var notificationPort = 1443
    @Volatile private var notificationShowTraffic = false
    @Volatile private var stopRequested = false
    private lateinit var notificationManager: NotificationManager

    companion object {
        private val _runtimeRouteMode = MutableStateFlow<RuntimeRouteMode?>(null)
        val runtimeRouteMode: StateFlow<RuntimeRouteMode?> = _runtimeRouteMode.asStateFlow()
        private val _startFailure = MutableStateFlow<ProxyStartFailure?>(null)
        val startFailure: StateFlow<ProxyStartFailure?> = _startFailure.asStateFlow()
        private val _serviceState = MutableStateFlow(ProxyServiceState.STOPPED)
        val serviceState: StateFlow<ProxyServiceState> = _serviceState.asStateFlow()

        @Volatile
        var instance: ProxyService? = null
            private set

        val isRunning: Boolean get() = _serviceState.value == ProxyServiceState.RUNNING
        val isActive: Boolean get() = _serviceState.value != ProxyServiceState.STOPPED

        fun start(context: Context) {
            if (!_serviceState.value.acceptsStart) return
            val intent = Intent(context, ProxyService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            instance?.requestStop()
                ?: context.stopService(Intent(context, ProxyService::class.java))
        }

        fun restart(context: Context) {
            if (!_serviceState.value.acceptsRestart) return
            instance?.requestRestart() ?: start(context)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        DiagnosticLogger.initialize(applicationContext)
        DiagnosticLogger.event("service_created")
        notificationManager = getSystemService(NotificationManager::class.java)
        TgWsProxyServer.globalVerbose = dev.minios.tgwsproxy.BuildConfig.DEBUG
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DiagnosticLogger.event("notification_stop_requested")
                requestStop()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                DiagnosticLogger.event("notification_restart_requested")
                requestRestart()
                return START_STICKY
            }
            ACTION_RESTORE_NOTIFICATION -> {
                if (_serviceState.value in setOf(
                        ProxyServiceState.STARTING,
                        ProxyServiceState.RUNNING,
                        ProxyServiceState.RESTARTING,
                    )
                ) {
                    DiagnosticLogger.event("notification_restore_requested")
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(
                            notificationHost,
                            notificationPort,
                            restarting = _serviceState.value == ProxyServiceState.RESTARTING,
                            showTraffic = notificationShowTraffic,
                        ),
                    )
                    return START_STICKY
                }
                return START_NOT_STICKY
            }
        }

        if (stopRequested || _serviceState.value == ProxyServiceState.STOPPING) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (serverJob?.isActive == true) {
            Log.w(TAG, "Server already running, ignoring start command")
            return START_STICKY
        }

        stopRequested = false
        _serviceState.value = ProxyServiceState.STARTING
        val notification = buildNotification(notificationHost, notificationPort)
        startForeground(NOTIFICATION_ID, notification)
        startProxyRuntime()

        return START_STICKY
    }

    private fun startProxyRuntime() {
        if (serverJob?.isActive == true || stopRequested) return
        _startFailure.value = null

        serviceScope?.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        serverJob = serviceScope?.launch {
            try {
                val configRepo = ConfigRepository(applicationContext)
                val savedConfig = configRepo.getConfig()
                val routedConfig = if (savedConfig.autoOptimizeConnection) {
                    val automaticDefault = savedConfig.withAutomaticRouteDefaults()
                    automaticRouteOverride?.let {
                        automaticDefault.withConnectionAdvice(it).withAutomaticPerformanceDefaults()
                    } ?: automaticDefault
                } else {
                    savedConfig
                }
                val config = routedConfig.withRuntimeConnectionPreferences()
                val runtimeId = ++runtimeGeneration
                notificationManager.cancel(ADVICE_NOTIFICATION_ID)
                _runtimeRouteMode.value = config.runtimeRouteMode()
                DiagnosticLogger.event(
                    "runtime_starting",
                    "port" to config.port,
                    "poolSize" to config.poolSize,
                    "bufferKb" to config.bufferSize / 1024,
                    "cfEnabled" to config.cfProxyEnabled,
                    "cfBeforeTcp" to config.cfProxyPriority,
                    "cfFirst" to config.cfProxyFirst,
                    "autoOptimize" to config.autoOptimizeConnection,
                    "keepCpuAwake" to config.keepCpuAwake,
                    "preconnectWs" to config.preconnectWebSockets,
                    "routeProbes" to config.routeProbesEnabled,
                    "cfRefresh" to config.cfDomainRefreshEnabled,
                    "wsPingSeconds" to config.webSocketPingIntervalSeconds,
                    "notificationTraffic" to config.showTrafficInNotification,
                    "redirectCount" to config.dcRedirects.size,
                    "runtimeId" to runtimeId,
                )
                launch {
                    var notifiedForEpoch: String? = null
                    DiagnosticLogger.entries.collect { entries ->
                        val epoch = entries.lastOrNull {
                            it.event == "runtime_starting" || it.event == "network_changed"
                        }?.let { "${it.timestamp}:${it.event}" } ?: return@collect
                        val advice = analyzeConnectionMode(
                            entries,
                            configuredCfEnabled = config.cfProxyEnabled,
                            configuredCfFirst = config.cfProxyFirst,
                        )
                        if (advice == null) {
                            if (!config.autoOptimizeConnection) notificationManager.cancel(ADVICE_NOTIFICATION_ID)
                            return@collect
                        }
                        if (notifiedForEpoch == epoch) return@collect
                        notifiedForEpoch = epoch
                        if (config.autoOptimizeConnection) {
                            applyAutomaticAdvice(advice)
                        } else {
                            showConnectionAdvice(advice)
                        }
                    }
                }

                if (config.keepCpuAwake) acquireWakeLock()

                // Start background CF domain refresh.
                if (config.cfProxyEnabled && config.cfDomainRefreshEnabled) {
                    CfProxyDomains.startBackgroundRefresh()
                }

                // Update notification with actual config
                updateNotification(config.host, config.port, showTraffic = config.showTrafficInNotification)

                // Start server
                val srv = TgWsProxyServer(config)
                server = srv
                registerNetworkCallback(config, runtimeId)
                srv.onStatusChange = { running ->
                    if (running && !stopRequested) {
                        _serviceState.value = ProxyServiceState.RUNNING
                        _startFailure.value = null
                        updateNotification(config.host, config.port, showTraffic = config.showTrafficInNotification)
                        if (config.routeProbesEnabled) startRouteProbe(config, runtimeId)
                    }
                }

                Log.i(TAG, "Starting proxy server on ${config.host}:${config.port}")
                startNotificationUpdates(config.host, config.port, config.showTrafficInNotification)
                srv.start() // blocks until stopped
            } catch (e: CancellationException) {
                Log.i(TAG, "Server job cancelled")
                DiagnosticLogger.event("runtime_cancelled")
            } catch (e: ProxyPortInUseException) {
                Log.e(TAG, "Proxy port ${e.port} is already in use")
                _startFailure.value = ProxyStartFailure.PortInUse(e.port)
                DiagnosticLogger.event(
                    "listener_bind_failed",
                    "port" to e.port,
                    "reason" to "port_in_use",
                )
                if (!stopRequested) stopAfterRuntimeFailure()
            } catch (e: Exception) {
                Log.e(TAG, "Server failed: ${e.message}", e)
                DiagnosticLogger.failure("runtime_failed", e)
                if (!stopRequested) stopAfterRuntimeFailure()
            } finally {
                stopNotificationUpdates()
                stopRouteProbe()
                unregisterNetworkCallback()
                CfProxyDomains.stopBackgroundRefresh()
                releaseWakeLock()
                server = null
                _runtimeRouteMode.value = null
                if (!stopRequested && _serviceState.value == ProxyServiceState.RUNNING) {
                    _serviceState.value = ProxyServiceState.STOPPED
                }
                Log.i(TAG, "Server job completed")
                DiagnosticLogger.event("runtime_completed")
            }
        }
    }

    private fun stopAfterRuntimeFailure() {
        _serviceState.value = ProxyServiceState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun stopProxyRuntime() {
        val job = serverJob
        stopNotificationUpdates()
        stopRouteProbe()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        server?.stop()
        val stopped = withTimeoutOrNull(RUNTIME_STOP_TIMEOUT_MS) {
            job?.cancelAndJoin()
            true
        } ?: false
        if (!stopped) {
            Log.e(TAG, "Proxy runtime did not stop within ${RUNTIME_STOP_TIMEOUT_MS}ms")
            DiagnosticLogger.event("runtime_stop_timeout", "timeoutMs" to RUNTIME_STOP_TIMEOUT_MS)
        }
        if (serverJob === job) serverJob = null
        server = null
        _runtimeRouteMode.value = null
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
    }

    private fun requestRestart(resetAutomaticRoute: Boolean = true) {
        if (stopRequested || !_serviceState.value.acceptsRestart) return
        if (resetAutomaticRoute) automaticRouteOverride = null
        if (restartJob?.isActive == true) return
        restartJob = lifecycleScope.launch {
            runtimeMutex.withLock {
                if (stopRequested) return@withLock
                _serviceState.value = ProxyServiceState.RESTARTING
                Log.i(TAG, "Restarting proxy runtime...")
                DiagnosticLogger.event("runtime_restarting")
                updateNotification(notificationHost, notificationPort, restarting = true)
                stopProxyRuntime()
                delay(300)
                if (stopRequested) return@withLock
                startProxyRuntime()
            }
        }
    }

    private fun applyAutomaticAdvice(advice: ConnectionAdvice) {
        if (stopRequested) return
        automaticRouteOverride = advice
        notificationManager.cancel(ADVICE_NOTIFICATION_ID)
        DiagnosticLogger.event("connection_advice_applied", "advice" to advice.name, "source" to "automatic")
        requestRestart(resetAutomaticRoute = false)
    }

    private fun requestStop() {
        if (stopRequested) return
        stopRequested = true
        _serviceState.value = ProxyServiceState.STOPPING
        _startFailure.value = null
        DiagnosticLogger.event("service_stop_requested")
        restartJob?.cancel()
        networkChangeJob?.cancel()
        stopJob = lifecycleScope.launch {
            runtimeMutex.withLock {
                Log.i(TAG, "Stopping server and service...")
                stopProxyRuntime()
            }
            _serviceState.value = ProxyServiceState.STOPPED
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProxyRuntimeNow() {
        stopNotificationUpdates()
        stopRouteProbe()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        server?.stop()
        server = null
        _runtimeRouteMode.value = null
        serverJob?.cancel()
        serverJob = null
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying...")
        DiagnosticLogger.event("service_destroying")
        restartJob?.cancel()
        restartJob = null
        stopJob?.cancel()
        stopJob = null
        stopRequested = true
        stopProxyRuntimeNow()
        lifecycleScope.cancel()
        lifecycleDispatcher.close()
        instance = null
        _serviceState.value = ProxyServiceState.STOPPED
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TgWsProxy::ProxyServer"
        ).apply {
            acquire(10 * 60 * 60 * 1000L) // 10 hours max
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release error: ${e.message}")
        }
        wakeLock = null
    }

    private fun registerNetworkCallback(config: ProxyConfig, runtimeId: Long) {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                connectivityManager.getNetworkCapabilities(network)?.let {
                    handleNetworkCandidate(network, it, config, runtimeId)
                }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handleNetworkCandidate(network, capabilities, config, runtimeId)
            }

            override fun onLost(network: Network) {
                if (currentNetwork == network) currentNetwork = null
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Unable to monitor network changes: ${e.message}")
        }
    }

    private fun handleNetworkCandidate(
        network: Network,
        capabilities: NetworkCapabilities,
        config: ProxyConfig,
        runtimeId: Long,
    ) {
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        ) {
            return
        }
        val transports = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to "wifi",
            NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
            NetworkCapabilities.TRANSPORT_VPN to "vpn",
            NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
        ).filter { capabilities.hasTransport(it.first) }.joinToString("+") { it.second }
        val signature = "${network.networkHandle}:$transports"
        currentNetwork = network
        val previous = networkSignature
        networkSignature = signature
        if (!hasSeenNetwork || previous == null) {
            hasSeenNetwork = true
            return
        }
        if (previous == signature) return

        networkChangeJob?.cancel()
        networkChangeJob = lifecycleScope.launch {
            delay(750)
            if (stopRequested) return@launch
            DiagnosticLogger.event("network_changed", "transport" to transports)
            if (config.autoOptimizeConnection) {
                automaticRouteOverride = null
                requestRestart(resetAutomaticRoute = true)
            } else {
                server?.onNetworkChanged()
                if (config.routeProbesEnabled) startRouteProbe(config, runtimeId)
            }
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        networkCallback = null
        networkChangeJob?.cancel()
        networkChangeJob = null
        currentNetwork = null
        networkSignature = null
        hasSeenNetwork = false
    }

    private fun startRouteProbe(config: ProxyConfig, runtimeId: Long) {
        routeProbeJob?.cancel()
        routeProbeJob = serviceScope?.launch {
            delay(1_000)
            RouteHealthProbe.run(config, runtimeId)
        }
    }

    private fun stopRouteProbe() {
        routeProbeJob?.cancel()
        routeProbeJob = null
    }

    private fun startNotificationUpdates(host: String, port: Int, showTraffic: Boolean) {
        if (!showTraffic || notificationUpdateJob?.isActive == true) return
        notificationUpdateJob = serviceScope?.launch {
            while (isActive) {
                delay(5_000)
                updateNotification(host, port, showTraffic = true)
            }
        }
    }

    private fun stopNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = notificationManager
        nm.createNotificationChannel(channel)
        nm.createNotificationChannel(
            NotificationChannel(
                ADVICE_CHANNEL_ID,
                getString(R.string.notification_advice_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.notification_advice_channel_desc)
            }
        )
    }

    private fun showConnectionAdvice(advice: ConnectionAdvice) {
        val message = when (advice) {
            ConnectionAdvice.ENABLE_CLOUDFLARE -> R.string.connection_advice_enable_cf
            ConnectionAdvice.USE_CLOUDFLARE_FIRST -> R.string.connection_advice_cf_first
            ConnectionAdvice.USE_DIRECT_FIRST -> R.string.connection_advice_direct_first
        }
        val openIntent = PendingIntent.getActivity(
            this,
            3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            ADVICE_NOTIFICATION_ID,
            Notification.Builder(this, ADVICE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.connection_advice_title))
                .setContentText(getString(message))
                .setStyle(Notification.BigTextStyle().bigText(getString(message)))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build(),
        )
    }

    private fun buildNotification(
        host: String,
        port: Int,
        restarting: Boolean = false,
        showTraffic: Boolean = false,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPi = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val restartIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPi = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val restoreIntent = Intent(this, ProxyService::class.java).apply {
            action = ACTION_RESTORE_NOTIFICATION
        }
        val restorePi = PendingIntent.getService(
            this, 4, restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val contentText = when {
            restarting -> getString(R.string.notification_restarting)
            showTraffic -> {
                val stats = ProxyStats.snapshot()
                val traffic = MtProtoConstants.humanBytes(stats.bytesUp + stats.bytesDown)
                getString(R.string.notification_running, traffic)
            }
            else -> getString(R.string.notification_running_no_traffic)
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSubText("$host:$port")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openPi)
            .setDeleteIntent(restorePi)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_restart),
                    restartPi,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.notification_stop),
                    stopPi,
                ).build()
            )
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build().apply {
                flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT
            }
    }

    private fun updateNotification(
        host: String,
        port: Int,
        restarting: Boolean = false,
        showTraffic: Boolean = notificationShowTraffic,
    ) {
        try {
            if (!restarting) {
                notificationHost = host
                notificationPort = port
                notificationShowTraffic = showTraffic
            }
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(host, port, restarting, showTraffic),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}
