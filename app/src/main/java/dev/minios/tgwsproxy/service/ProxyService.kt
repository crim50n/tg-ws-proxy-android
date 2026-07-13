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
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.proxy.CfProxyDomains
import dev.minios.tgwsproxy.proxy.ProxyStats
import dev.minios.tgwsproxy.proxy.TgWsProxyServer
import dev.minios.tgwsproxy.ui.MainActivity

private const val TAG = "ProxyService"
private const val CHANNEL_ID = "tg_ws_proxy_service"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "dev.minios.tgwsproxy.STOP"

/**
 * Foreground service that runs the MTProto proxy server.
 */
class ProxyService : Service() {

    var server: TgWsProxyServer? = null
        private set
    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var serverJob: Job? = null
    private var notificationWatchdogJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var currentNetwork: Network? = null
    @Volatile private var hasSeenNetwork = false
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
            // First stop the server, then stop the service
            instance?.stopServerAndService()
                ?: context.stopService(Intent(context, ProxyService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        notificationManager = getSystemService(NotificationManager::class.java)
        TgWsProxyServer.globalVerbose = dev.minios.tgwsproxy.BuildConfig.DEBUG
        createNotificationChannel()
        Log.i(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndService()
            return START_NOT_STICKY
        }

        // Prevent duplicate starts
        if (serverJob?.isActive == true) {
            Log.w(TAG, "Server already running, ignoring start command")
            return START_STICKY
        }

        // Cancel any previous job that might still be cleaning up
        serverJob?.cancel()
        serviceScope?.cancel()

        serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        // Show notification immediately (required for foreground service)
        val notification = buildNotification("127.0.0.1", 1443)
        startForeground(NOTIFICATION_ID, notification)

        serverJob = serviceScope?.launch {
            try {
                val configRepo = ConfigRepository(applicationContext)
                val config = configRepo.getConfig()

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
                ProxyStats.startedAtMs = System.currentTimeMillis()
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
            } catch (e: Exception) {
                Log.e(TAG, "Server failed: ${e.message}", e)
            } finally {
                stopNotificationWatchdog()
                unregisterNetworkCallback()
                releaseWakeLock()
                server = null
                Log.i(TAG, "Server job completed")
            }
        }

        return START_STICKY
    }

    private fun stopServerAndService() {
        Log.i(TAG, "Stopping server and service...")
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Service destroying...")
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

    private fun buildNotification(host: String, port: Int): Notification {
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

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_running, host, port))
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(openPi)
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

    private fun updateNotification(host: String, port: Int) {
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(host, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}
