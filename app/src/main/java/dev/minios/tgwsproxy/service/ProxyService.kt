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
import android.os.IBinder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.proxy.CfProxyDomains
import dev.minios.tgwsproxy.proxy.MtProtoConstants
import dev.minios.tgwsproxy.proxy.ProxyStats
import dev.minios.tgwsproxy.proxy.TgWsProxyServer
import dev.minios.tgwsproxy.ui.MainActivity

private const val TAG = "ProxyService"
private const val CHANNEL_ID = "tg_ws_proxy_service"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "dev.minios.tgwsproxy.STOP"
private const val ACTION_RESTART = "dev.minios.tgwsproxy.RESTART"

/**
 * Foreground service that runs the MTProto proxy server.
 */
class ProxyService : Service() {

    var server: TgWsProxyServer? = null
        private set
    private var serviceScope: CoroutineScope? = null
    private val lifecycleScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val runtimeMutex = Mutex()
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverJob: Job? = null
    private var restartJob: Job? = null
    private var notificationWatchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var hasSeenNetwork = false
    @Volatile private var notificationHost = "127.0.0.1"
    @Volatile private var notificationPort = 1443
    private lateinit var notificationManager: NotificationManager

    companion object {
        @Volatile
        var instance: ProxyService? = null
            private set

        val isRunning: Boolean get() = instance?.server?.isRunning == true

        fun start(context: Context) {
            val intent = Intent(context, ProxyService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            instance?.requestStop()
                ?: context.stopService(Intent(context, ProxyService::class.java))
        }

        fun restart(context: Context) {
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
        }

        if (serverJob?.isActive == true) {
            Log.w(TAG, "Server already running, ignoring start command")
            return START_STICKY
        }

        val notification = buildNotification(notificationHost, notificationPort)
        startForeground(NOTIFICATION_ID, notification)
        startProxyRuntime()

        return START_STICKY
    }

    private fun startProxyRuntime() {
        if (serverJob?.isActive == true) return

        serviceScope?.cancel()
        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        serverJob = serviceScope?.launch {
            try {
                val configRepo = ConfigRepository(applicationContext)
                val config = configRepo.getConfig()
                DiagnosticLogger.event(
                    "runtime_starting",
                    "port" to config.port,
                    "poolSize" to config.poolSize,
                    "bufferKb" to config.bufferSize / 1024,
                    "cfEnabled" to config.cfProxyEnabled,
                    "cfFirst" to config.cfProxyPriority,
                    "redirectCount" to config.dcRedirects.size,
                )

                // Acquire wake lock
                acquireWakeLock()

                // Start background CF domain refresh.
                if (config.cfProxyEnabled) {
                    CfProxyDomains.startBackgroundRefresh()
                }

                // Update notification with actual config
                updateNotification(config.host, config.port)

                // Start server
                val srv = TgWsProxyServer(config)
                server = srv
                registerNetworkCallback()
                srv.onStatusChange = { running ->
                    if (running) {
                        updateNotification(config.host, config.port)
                    }
                }

                Log.i(TAG, "Starting proxy server on ${config.host}:${config.port}")
                startNotificationWatchdog(config.host, config.port)
                srv.start() // blocks until stopped
            } catch (e: CancellationException) {
                Log.i(TAG, "Server job cancelled")
                DiagnosticLogger.event("runtime_cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Server failed: ${e.message}", e)
                DiagnosticLogger.failure("runtime_failed", e)
            } finally {
                stopNotificationWatchdog()
                unregisterNetworkCallback()
                CfProxyDomains.stopBackgroundRefresh()
                releaseWakeLock()
                server = null
                Log.i(TAG, "Server job completed")
                DiagnosticLogger.event("runtime_completed")
            }
        }
    }

    private suspend fun stopProxyRuntime() {
        val job = serverJob
        stopNotificationWatchdog()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        server?.stop()
        job?.cancelAndJoin()
        if (serverJob === job) serverJob = null
        server = null
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
    }

    private fun requestRestart() {
        if (restartJob?.isActive == true) return
        restartJob = lifecycleScope.launch {
            runtimeMutex.withLock {
                Log.i(TAG, "Restarting proxy runtime...")
                DiagnosticLogger.event("runtime_restarting")
                updateNotification(notificationHost, notificationPort, restarting = true)
                stopProxyRuntime()
                delay(300)
                startProxyRuntime()
            }
        }
    }

    private fun requestStop() {
        if (restartJob?.isActive == true) restartJob?.cancel()
        lifecycleScope.launch {
            runtimeMutex.withLock {
                Log.i(TAG, "Stopping server and service...")
                DiagnosticLogger.event("service_stop_requested")
                stopProxyRuntime()
            }
            withContext(Dispatchers.Main.immediate) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopProxyRuntimeNow() {
        stopNotificationWatchdog()
        unregisterNetworkCallback()
        CfProxyDomains.stopBackgroundRefresh()
        server?.stop()
        server = null
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
        stopProxyRuntimeNow()
        lifecycleScope.cancel()
        instance = null
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

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = currentNetwork
                currentNetwork = network
                if (hasSeenNetwork && previous != network) {
                    DiagnosticLogger.event("network_changed")
                    server?.onNetworkChanged()
                }
                hasSeenNetwork = true
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

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
        networkCallback = null
        currentNetwork = null
        hasSeenNetwork = false
    }

    private fun startNotificationWatchdog(host: String, port: Int) {
        if (notificationWatchdogJob?.isActive == true) return
        notificationWatchdogJob = serviceScope?.launch {
            while (isActive) {
                delay(5_000)
                if (!isNotificationVisible()) {
                    Log.i(TAG, "Foreground notification was dismissed, restoring it")
                    startForeground(NOTIFICATION_ID, buildNotification(host, port))
                } else {
                    updateNotification(host, port)
                }
            }
        }
    }

    private fun stopNotificationWatchdog() {
        notificationWatchdogJob?.cancel()
        notificationWatchdogJob = null
    }

    private fun isNotificationVisible(): Boolean {
        return notificationManager.activeNotifications.any { it.id == NOTIFICATION_ID }
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
    }

    private fun buildNotification(host: String, port: Int, restarting: Boolean = false): Notification {
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

        val stats = ProxyStats.snapshot()
        val traffic = MtProtoConstants.humanBytes(stats.bytesUp + stats.bytesDown)
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(
                if (restarting) getString(R.string.notification_restarting)
                else getString(R.string.notification_running, traffic)
            )
            .setSubText("$host:$port")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openPi)
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

    private fun updateNotification(host: String, port: Int, restarting: Boolean = false) {
        try {
            if (!restarting) {
                notificationHost = host
                notificationPort = port
            }
            notificationManager.notify(
                NOTIFICATION_ID,
                buildNotification(host, port, restarting),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}
