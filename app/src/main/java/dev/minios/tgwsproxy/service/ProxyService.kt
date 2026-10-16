package dev.minios.tgwsproxy.service

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
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
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.BuildConfig
import dev.minios.tgwsproxy.update.UpdateInfo
import dev.minios.tgwsproxy.update.UpdatePolicy
import dev.minios.tgwsproxy.update.UpdateRepository
import dev.minios.tgwsproxy.update.UpdateState
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.diagnostics.ConnectionAdvice
import dev.minios.tgwsproxy.diagnostics.analyzeConnectivity
import dev.minios.tgwsproxy.diagnostics.withConnectionAdvice
import dev.minios.tgwsproxy.diagnostics.withAutomaticRouteDefaults
import dev.minios.tgwsproxy.diagnostics.withAutomaticPerformanceDefaults
import dev.minios.tgwsproxy.diagnostics.withRuntimeConnectionPreferences
import dev.minios.tgwsproxy.proxy.CfProxyDomains
import dev.minios.tgwsproxy.proxy.ConnectivityTracker
import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.NetworkStatus
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
private const val UPDATE_CHANNEL_ID = "app_updates"
private const val NOTIFICATION_ID = 1
private const val ADVICE_NOTIFICATION_ID = 2
private const val UPDATE_NOTIFICATION_ID = 3
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
internal val ProxyServiceState.keepsUpdateNotification: Boolean
    get() = this == ProxyServiceState.RESTARTING

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
    private val runtimeStopExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "proxy-runtime-stop").apply { isDaemon = true }
    }
    private val runtimeMutex = Mutex()
    private val runtimeOwnershipLock = Any()
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverJob: Job? = null
    private var restartJob: Job? = null
    private var stopJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var updateCheckJob: Job? = null
    private lateinit var updateRepository: UpdateRepository
    private var routeProbeJob: Job? = null
    private var networkChangeJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private data class AutomaticAdvice(
        val advice: ConnectionAdvice,
        val tracker: ConnectivityTracker,
        val decision: NetworkDecision,
    )
    private var automaticRouteOverride: AutomaticAdvice? = null
    private val networkAccess = NetworkAccessState()
    private var runtimeGeneration = 0L
    @Volatile private var activeRuntimeId = 0L
    @Volatile private var runtimeConnectivity: ConnectivityTracker? = null
    private var requestedNetworkStatus = NetworkStatus.UNKNOWN
    @Volatile private var currentNetwork: Network? = null
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
        private val _connectivityState = MutableStateFlow(ConnectivitySnapshot(stopped = true))
        val connectivityState: StateFlow<ConnectivitySnapshot> = _connectivityState.asStateFlow()

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
        updateRepository = UpdateRepository(applicationContext)
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

        val runtimeId = synchronized(runtimeOwnershipLock) {
            (++runtimeGeneration).also { activeRuntimeId = it }
        }
        val connectivity = ConnectivityTracker()
        runtimeConnectivity = connectivity
        serverJob = serviceScope?.launch {
            var ownedServer: TgWsProxyServer? = null
            try {
                val configRepo = ConfigRepository(applicationContext)
                val savedConfig = configRepo.getConfig()
                val manager = getSystemService(ConnectivityManager::class.java)
                val initialNetwork = manager.activeNetwork
                val initialCapabilities = initialNetwork?.let(manager::getNetworkCapabilities)
                val initialStatus = classifyNetwork(
                    initialNetwork != null,
                    initialCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                    initialCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                    false,
                )
                connectivity.updateNetwork(initialNetwork?.networkHandle, initialStatus)
                val configurationEpoch = connectivity.state.value.epoch
                val carriedAdvice = synchronized(runtimeOwnershipLock) {
                    automaticRouteOverride?.takeIf {
                        it.decision.canCarryTo(initialNetwork?.networkHandle, initialStatus)
                    }.also { automaticRouteOverride = it }
                }
                val routedConfig = if (savedConfig.autoOptimizeConnection) {
                    val automaticDefault = savedConfig.withAutomaticRouteDefaults()
                    carriedAdvice?.let {
                        automaticDefault.withConnectionAdvice(it.advice).withAutomaticPerformanceDefaults()
                    } ?: automaticDefault
                } else {
                    savedConfig
                }
                val config = routedConfig.withRuntimeConnectionPreferences()
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
                // Update notification with actual config
                updateNotification(config.host, config.port, showTraffic = config.showTrafficInNotification)

                // Start server
                val srv = TgWsProxyServer(config, connectivity)
                srv.onStatusChange = { running ->
                    if (running && !stopRequested && activeRuntimeId == runtimeId) {
                        _serviceState.value = ProxyServiceState.RUNNING
                        _startFailure.value = null
                        updateNotification(config.host, config.port, showTraffic = config.showTrafficInNotification)
                        if (config.routeProbesEnabled) startRouteProbe(config, runtimeId)
                        startUpdateChecks(runtimeId)
                    }
                }

                synchronized(runtimeOwnershipLock) {
                    if (stopRequested || activeRuntimeId != runtimeId) {
                        throw CancellationException("Runtime ownership was revoked before startup")
                    }
                    if (config.keepCpuAwake) acquireWakeLock()
                    if (config.cfProxyEnabled && config.cfDomainRefreshEnabled) {
                        CfProxyDomains.startBackgroundRefresh()
                    }
                    ownedServer = srv
                    server = srv
                    registerNetworkCallback(runtimeId, connectivity)
                    startNotificationUpdates(config.host, config.port, config.showTrafficInNotification)
                }
                launch {
                    var appliedEpoch = configurationEpoch
                    var notifiedEpoch: Long? = null
                    var backgroundAllowed = false
                    connectivity.state.collect { snapshot ->
                        if (stopRequested || activeRuntimeId != runtimeId || instance !== this@ProxyService ||
                            snapshot.epoch != connectivity.state.value.epoch
                        ) return@collect
                        _connectivityState.value = snapshot
                        if (appliedEpoch != snapshot.epoch) {
                            stopRouteProbe()
                            notificationManager.cancel(ADVICE_NOTIFICATION_ID)
                            if (snapshot.allowsTraffic) {
                                appliedEpoch = snapshot.epoch
                                if (config.autoOptimizeConnection) {
                                    automaticRouteOverride = null
                                    val recovered = config.withAutomaticRouteDefaults().withRuntimeConnectionPreferences()
                                    if (recovered.poolSize != srv.configSnapshot.poolSize) {
                                        requestRestart(resetAutomaticRoute = true)
                                        return@collect
                                    }
                                    srv.updateConfig(recovered)
                                    _runtimeRouteMode.value = recovered.runtimeRouteMode()
                                }
                            }
                        }
                        srv.onConnectivityChanged()
                        if (snapshot.allowsBackgroundWork && !backgroundAllowed && srv.isRunning && config.routeProbesEnabled) {
                            startRouteProbe(srv.configSnapshot, runtimeId)
                        }
                        backgroundAllowed = snapshot.allowsBackgroundWork
                        val activeConfig = srv.configSnapshot
                        if (restartJob?.isActive == true) return@collect
                        val advice = analyzeConnectivity(snapshot, activeConfig.cfProxyEnabled, activeConfig.cfProxyFirst)
                        if (advice == null) {
                            if (!activeConfig.autoOptimizeConnection) notificationManager.cancel(ADVICE_NOTIFICATION_ID)
                            return@collect
                        }
                        if (notifiedEpoch == snapshot.epoch) return@collect
                        notifiedEpoch = snapshot.epoch
                        // An old snapshot must never change the new network's route.
                        synchronized(runtimeOwnershipLock) {
                            if (activeRuntimeId == runtimeId && connectivity.isCurrent(snapshot.epoch) &&
                                connectivity.state.value.internetConfirmed && activeConfig.autoOptimizeConnection
                            ) applyAutomaticAdvice(advice, connectivity, runtimeId, snapshot.epoch)
                        }
                    }
                }
                Log.i(TAG, "Starting proxy server on ${config.host}:${config.port}")
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
                connectivity.stop()
                synchronized(runtimeOwnershipLock) {
                    if (activeRuntimeId == runtimeId) {
                        activeRuntimeId = 0L
                        stopNotificationUpdates()
                        stopUpdateChecks()
                        stopRouteProbe()
                        unregisterNetworkCallback()
                        CfProxyDomains.stopBackgroundRefresh()
                        releaseWakeLock()
                        if (server === ownedServer) server = null
                        _runtimeRouteMode.value = null
                        runtimeConnectivity = null
                        if (instance === this@ProxyService) _connectivityState.value = connectivity.state.value
                        if (!stopRequested && _serviceState.value == ProxyServiceState.RUNNING) {
                            _serviceState.value = ProxyServiceState.STOPPED
                        }
                    }
                }
                Log.i(TAG, "Server job completed")
                DiagnosticLogger.event("runtime_completed", "runtimeId" to runtimeId)
            }
        }
    }

    private fun stopAfterRuntimeFailure() {
        stopUpdateChecks()
        _serviceState.value = ProxyServiceState.STOPPED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun stopProxyRuntime(): Boolean {
        val job = serverJob
        val (runtimeId, ownedServer) = synchronized(runtimeOwnershipLock) {
            val id = activeRuntimeId
            val owned = server
            if (activeRuntimeId == id) activeRuntimeId = 0L
            runtimeConnectivity?.stop()
            runtimeConnectivity = null
            stopUpdateChecks(clearNotification = !_serviceState.value.keepsUpdateNotification)
            id to owned
        }
        val stopDeadline = SystemClock.elapsedRealtime() + RUNTIME_STOP_TIMEOUT_MS
        stopNotificationUpdates()
        stopRouteProbe()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        val stopFuture = ownedServer?.let { runtimeStopExecutor.submit { it.stop() } }
        val serverStopped = if (stopFuture == null) {
            true
        } else {
            runCatching {
                val remaining = (stopDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                stopFuture.get(remaining, TimeUnit.MILLISECONDS)
                true
            }.getOrElse {
                stopFuture.cancel(true)
                false
            }
        }
        job?.cancel()
        val remaining = (stopDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
        val jobStopped = withTimeoutOrNull(remaining) {
            job?.join()
            true
        } ?: false
        val stopped = serverStopped && jobStopped
        if (!stopped) {
            Log.e(TAG, "Proxy runtime did not stop within ${RUNTIME_STOP_TIMEOUT_MS}ms")
            DiagnosticLogger.event(
                "runtime_stop_timeout",
                "timeoutMs" to RUNTIME_STOP_TIMEOUT_MS,
                "runtimeId" to runtimeId,
            )
        }
        if (serverJob === job) serverJob = null
        if (server === ownedServer) server = null
        _runtimeRouteMode.value = null
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        return stopped
    }

    private fun requestRestart(resetAutomaticRoute: Boolean = true, advice: AutomaticAdvice? = null) {
        if (stopRequested || !_serviceState.value.acceptsRestart) return
        if (restartJob?.isActive == true) return
        restartJob = lifecycleScope.launch {
            runtimeMutex.withLock {
                if (stopRequested) return@withLock
                synchronized(runtimeOwnershipLock) {
                    if (advice != null) {
                        if (runtimeConnectivity !== advice.tracker ||
                            !advice.decision.appliesTo(activeRuntimeId, advice.tracker.state.value)
                        ) return@withLock
                        automaticRouteOverride = advice
                        DiagnosticLogger.event("connection_advice_applied", "advice" to advice.advice.name, "source" to "automatic")
                    } else if (resetAutomaticRoute) automaticRouteOverride = null
                }
                _serviceState.value = ProxyServiceState.RESTARTING
                Log.i(TAG, "Restarting proxy runtime...")
                DiagnosticLogger.event("runtime_restarting")
                updateNotification(notificationHost, notificationPort, restarting = true)
                val stopped = stopProxyRuntime()
                if (!stopped) {
                    stopRequested = true
                    stopUpdateChecks()
                    _serviceState.value = ProxyServiceState.STOPPED
                    withContext(Dispatchers.Main.immediate) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    return@withLock
                }
                delay(300)
                if (stopRequested) return@withLock
                startProxyRuntime()
            }
        }
    }

    private fun applyAutomaticAdvice(advice: ConnectionAdvice, tracker: ConnectivityTracker, runtimeId: Long, epoch: Long) {
        if (stopRequested || restartJob?.isActive == true) return
        notificationManager.cancel(ADVICE_NOTIFICATION_ID)
        requestRestart(resetAutomaticRoute = false,
            advice = AutomaticAdvice(advice, tracker, NetworkDecision(runtimeId, epoch, tracker.state.value.networkId)))
    }

    private fun requestStop() {
        if (stopRequested) return
        stopRequested = true
        runtimeConnectivity?.stop()
        _connectivityState.value = ConnectivitySnapshot(stopped = true)
        stopUpdateChecks()
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
            DiagnosticLogger.event("service_stopped")
            withContext(Dispatchers.IO) { DiagnosticLogger.flush(timeoutMs = 1_000L) }
            _serviceState.value = ProxyServiceState.STOPPED
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProxyRuntimeNow() {
        runtimeConnectivity?.stop()
        runtimeConnectivity = null
        if (instance === this) _connectivityState.value = ConnectivitySnapshot(stopped = true)
        stopUpdateChecks()
        stopNotificationUpdates()
        stopRouteProbe()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        val ownedServer = synchronized(runtimeOwnershipLock) {
            activeRuntimeId = 0L
            server.also { server = null }
        }
        ownedServer?.let { runtimeStopExecutor.execute { runCatching { it.stop() } } }
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
        runtimeStopExecutor.shutdown()
        instance = null
        _serviceState.value = ProxyServiceState.STOPPED
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TgWsProxy::ProxyServer"
        ).apply {
            setReferenceCounted(false)
            acquire()
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

    private fun registerNetworkCallback(runtimeId: Long, connectivity: ConnectivityTracker) {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handleNetworkCandidate(network, connectivityManager.getNetworkCapabilities(network), runtimeId, connectivity)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                handleNetworkCandidate(network, capabilities, runtimeId, connectivity)
            }

            override fun onLost(network: Network) {
                synchronized(runtimeOwnershipLock) {
                    if (activeRuntimeId != runtimeId || currentNetwork != network) return
                    currentNetwork = null
                    networkAccess.selectNetwork(null)
                    automaticRouteOverride = null
                    networkChangeJob?.cancel()
                    requestedNetworkStatus = NetworkStatus.OFFLINE
                    connectivity.updateNetwork(null, NetworkStatus.OFFLINE)
                }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                synchronized(runtimeOwnershipLock) {
                    if (activeRuntimeId != runtimeId || connectivityManager.activeNetwork != network) return
                    networkAccess.selectNetwork(network.networkHandle)
                    networkAccess.setBlocked(network.networkHandle, blocked)
                    handleNetworkCandidate(network, connectivityManager.getNetworkCapabilities(network), runtimeId, connectivity)
                }
            }
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
            val network = connectivityManager.activeNetwork
            if (network == null) {
                requestedNetworkStatus = NetworkStatus.OFFLINE
                networkAccess.selectNetwork(null)
                connectivity.updateNetwork(null, NetworkStatus.OFFLINE)
            } else {
                handleNetworkCandidate(network, connectivityManager.getNetworkCapabilities(network), runtimeId, connectivity, stabilize = false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to monitor network changes: ${e.message}")
        }
    }

    private fun handleNetworkCandidate(
        network: Network,
        capabilities: NetworkCapabilities?,
        runtimeId: Long,
        connectivity: ConnectivityTracker,
        stabilize: Boolean = true,
    ) {
        synchronized(runtimeOwnershipLock) {
            if (stopRequested || activeRuntimeId != runtimeId || runtimeConnectivity !== connectivity) return
            // Ignore late capabilities from a default network that has already been replaced.
            val active = getSystemService(ConnectivityManager::class.java).activeNetwork
            if (active != network) return
            networkAccess.selectNetwork(network.networkHandle)
            val status = classifyNetwork(
                hasNetwork = true,
                validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
                captivePortal = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
                blocked = networkAccess.blocked,
            )
            if (currentNetwork == network && requestedNetworkStatus == status) return
            currentNetwork = network
            requestedNetworkStatus = status
            automaticRouteOverride = null
            networkChangeJob?.cancel()
            if (!stabilize || status == NetworkStatus.CAPTIVE_PORTAL || status == NetworkStatus.BLOCKED) {
                connectivity.updateNetwork(network.networkHandle, status)
            } else {
                connectivity.updateNetwork(network.networkHandle, NetworkStatus.CHECKING)
                networkChangeJob = lifecycleScope.launch {
                    delay(750)
                    synchronized(runtimeOwnershipLock) {
                        if (stopRequested || activeRuntimeId != runtimeId || currentNetwork != network ||
                            requestedNetworkStatus != status || runtimeConnectivity !== connectivity
                        ) return@synchronized
                        connectivity.updateNetwork(network.networkHandle, status)
                        DiagnosticLogger.event("network_changed", "state" to status.name, "epoch" to connectivity.state.value.epoch)
                    }
                }
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
        networkAccess.selectNetwork(null)
        requestedNetworkStatus = NetworkStatus.UNKNOWN
    }

    private fun startRouteProbe(config: ProxyConfig, runtimeId: Long) {
        val connectivity = runtimeConnectivity ?: return
        val snapshot = connectivity.state.value
        if (stopRequested || activeRuntimeId != runtimeId ||
            !snapshot.allowsBackgroundWork
        ) return
        routeProbeJob?.cancel()
        routeProbeJob = serviceScope?.launch {
            delay(1_000)
            if (activeRuntimeId != runtimeId || !connectivity.isCurrent(snapshot.epoch)) return@launch
            RouteHealthProbe.run(config, runtimeId, connectivity, snapshot.epoch)
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

    private fun startUpdateChecks(runtimeId: Long) = synchronized(runtimeOwnershipLock) {
        if (stopRequested || activeRuntimeId != runtimeId || updateCheckJob?.isActive == true) return@synchronized
        updateCheckJob = serviceScope?.launch {
            while (isActive) {
                updateRepository.checkIfDue()?.onFailure {
                    DiagnosticLogger.failure("service_update_check_failed", it)
                }
                ensureActive()
                val release = (updateRepository.cachedState() as? UpdateState.Available)?.release
                release?.let { available ->
                    withContext(Dispatchers.Main.immediate) {
                        synchronized(runtimeOwnershipLock) {
                            if (isActive && !stopRequested && activeRuntimeId == runtimeId && instance === this@ProxyService) {
                                runCatching { showUpdateNotification(available) }.onFailure {
                                    DiagnosticLogger.failure("update_notification_failed", it)
                                }
                            }
                        }
                    }
                }
                delay(updateRepository.timeUntilNextCheckMs().coerceAtLeast(60_000L))
            }
        }
    }

    private fun stopUpdateChecks(clearNotification: Boolean = true) = synchronized(runtimeOwnershipLock) {
        updateCheckJob?.cancel()
        updateCheckJob = null
        if (clearNotification && instance === this) notificationManager.cancel(UPDATE_NOTIFICATION_ID)
    }

    private fun showUpdateNotification(release: UpdateInfo) {
        if (!UpdatePolicy.shouldNotify(
                _serviceState.value == ProxyServiceState.RUNNING,
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_NAME,
                release,
                updateRepository.lastNotifiedVersion(),
            ) || !notificationManager.areNotificationsEnabled() ||
            notificationManager.getNotificationChannel(UPDATE_CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val openIntent = PendingIntent.getActivity(
            this, 5,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_SHOW_UPDATE
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        notificationManager.notify(
            UPDATE_NOTIFICATION_ID,
            Notification.Builder(this, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(getString(R.string.update_available_title))
                .setContentText(getString(R.string.update_available_version, release.versionName))
                .setContentIntent(openIntent)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build(),
        )
        updateRepository.markNotified(release)
        DiagnosticLogger.event("update_notification_shown", "version" to release.versionName)
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
        nm.createNotificationChannel(
            NotificationChannel(
                UPDATE_CHANNEL_ID,
                getString(R.string.notification_update_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_update_channel_desc)
                setShowBadge(false)
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
