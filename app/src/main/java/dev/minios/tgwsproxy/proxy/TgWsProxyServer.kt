package dev.minios.tgwsproxy.proxy

import android.util.Log
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "TgWsProxyServer"

/**
 * Main MTProto proxy server.
 * Accepts client connections, parses MTProto handshakes, and bridges traffic
 * through WebSocket to Telegram data centers.
 */
class TgWsProxyServer(
    private var config: ProxyConfig,
) {
    private val running = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var wsPool: WsPool? = null
    private var scope: CoroutineScope? = null
    private var statsJob: Job? = null
    private val stoppedLatch = CountDownLatch(1)

    // Throttle invalid handshake log spam
    private val badHandshakeCount = AtomicLong(0)
    private var lastBadHandshakeLog = 0L

    // Track active handlers and sockets for deterministic shutdown.
    private val clientJobs = ConcurrentHashMap.newKeySet<Job>()
    private val clientSockets = ConcurrentHashMap.newKeySet<Socket>()

    val isRunning: Boolean get() = listening.get()

    var onStatusChange: ((Boolean) -> Unit)? = null

    fun updateConfig(newConfig: ProxyConfig) {
        config = newConfig
        wsPool?.updateRedirects(newConfig.dcRedirects)
    }

    fun onNetworkChanged() {
        if (!isRunning) return
        Log.i(TAG, "Network changed, rebuilding WebSocket pool")
        Bridge.resetState()
        wsPool?.resetAndWarm(directWarmupRedirects())
    }

    /**
     * Start the proxy server. Blocks until stopped.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (stopRequested.get() || running.getAndSet(true)) {
            Log.w(TAG, "Server already running")
            return@withContext
        }
        if (stopRequested.get()) {
            running.set(false)
            cleanup()
            return@withContext
        }

        ProxyStats.resetAll()
        ProxyStats.startedAtMs = System.currentTimeMillis()
        badHandshakeCount.set(0)
        lastBadHandshakeLog = 0L
        Bridge.resetState() // Clear WS blacklist and cooldown on restart
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val startupStartedAt = System.currentTimeMillis()

        try {
            // Initialize WS pool
            wsPool = WsPool(
                poolSize = config.poolSize,
                bufferSize = config.bufferSize,
            ).also {
                it.start(scope!!)
                it.warmUp(directWarmupRedirects())
            }
            DiagnosticLogger.event(
                "pool_warmup_started",
                "poolSize" to config.poolSize,
                "dcCount" to directWarmupRedirects().size,
            )

            // CF domain refresh is owned by ProxyService.

            // Include the current direct-WS blacklist in periodic stats.
            statsJob = scope!!.launch {
                while (isActive) {
                    delay(60_000)
                    Log.i(TAG, "stats: ${ProxyStats.formatStats()} | ws_bl: ${Bridge.blacklistSummary()}")
                }
            }

            // Create server socket
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.soTimeout = 0
            ss.bind(InetSocketAddress(config.host, config.port))
            serverSocket = ss
            if (stopRequested.get() || !running.get()) {
                ss.close()
                return@withContext
            }
            listening.set(true)
            DiagnosticLogger.event(
                "listener_ready",
                "port" to config.port,
                "elapsedMs" to System.currentTimeMillis() - startupStartedAt,
            )

            logStartupBanner()

            onStatusChange?.invoke(true)

            // Accept connections
            while (running.get()) {
                try {
                    val clientSocket = ss.accept()
                    clientSocket.tcpNoDelay = true
                    clientSocket.sendBufferSize = config.bufferSize
                    clientSocket.receiveBufferSize = config.bufferSize
                    clientSockets.add(clientSocket)

                    val job = scope!!.launch {
                        handleClient(clientSocket)
                    }
                    clientJobs.add(job)
                    job.invokeOnCompletion {
                        clientJobs.remove(job)
                        clientSockets.remove(clientSocket)
                    }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}", e)
            DiagnosticLogger.failure("server_failed", e)
            throw e
        } finally {
            cleanup()
            stoppedLatch.countDown()
        }
    }

    /**
     * Stop the proxy server and wait for cleanup.
     */
    fun stop() {
        stopRequested.set(true)
        val wasRunning = running.getAndSet(false)

        Log.i(TAG, "Stopping proxy server...")

        // Close server socket first to unblock accept()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        cleanup()

        if (wasRunning) {
            // Wait for the server loop to finish (max 3 seconds)
            try {
                stoppedLatch.await(3, TimeUnit.SECONDS)
            } catch (_: Exception) {}
        }

        Log.i(TAG, "Proxy server stopped. ${ProxyStats.formatStats()}")
        val stats = ProxyStats.snapshot()
        DiagnosticLogger.event(
            "server_stopped",
            "total" to stats.connectionsTotal,
            "rejected" to stats.connectionsBad,
            "wsErrors" to stats.wsErrors,
            "bytesUp" to stats.bytesUp,
            "bytesDown" to stats.bytesDown,
        )
    }

    /**
     * Close active sockets before cancelling handlers so blocking reads unblock.
     */
    @Synchronized
    private fun cleanup() {
        val wasListening = listening.getAndSet(false)
        statsJob?.cancel()
        statsJob = null
        wsPool?.stop()
        wsPool = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // Closing sockets interrupts blocking Java reads before coroutine cancellation.
        if (clientJobs.isNotEmpty()) {
            Log.i(TAG, "Closing ${clientJobs.size} active connection(s)...")
            clientSockets.forEach { socket ->
                try { socket.close() } catch (_: Exception) {}
            }
            runBlocking {
                withTimeoutOrNull(2_000) {
                    clientJobs.toList().joinAll()
                }
            }
        }

        scope?.cancel()
        scope = null
        clientJobs.clear()
        clientSockets.clear()

        if (wasListening) onStatusChange?.invoke(false)
    }

    /**
     * Log non-sensitive startup configuration.
     */
    private fun logStartupBanner() {
        val sep = "=" .repeat(60)
        Log.i(TAG, sep)
        Log.i(TAG, "  Telegram MTProto WS Bridge Proxy")
        Log.i(TAG, "  Listening on   ${config.host}:${config.port}")
        Log.i(TAG, "  Target DC IPs:")
        for (dc in config.dcRedirects.keys.sorted()) {
            Log.i(TAG, "    DC$dc: ${config.dcRedirects[dc]}")
        }
        if (config.cfProxyEnabled) {
            val route = if (config.cfProxyFirst) {
                "all DCs via CF first"
            } else if (config.cfProxyPriority) {
                "CF before TCP fallback"
            } else {
                "TCP before CF fallback"
            }
            val domainType = if (config.cfProxyUserDomain.isNotBlank()) "user" else "auto"
            Log.i(TAG, "  CF proxy:      enabled ($route | $domainType)")
        }
        Log.i(TAG, sep)
    }

    private fun directWarmupRedirects(): Map<Int, String> {
        return if (config.cfProxyEnabled && config.cfProxyFirst) emptyMap() else config.dcRedirects
    }

    private suspend fun handleClient(clientSocket: Socket) {
        val clientAddr = clientSocket.remoteSocketAddress.toString()
        ProxyStats.connectionsTotal.incrementAndGet()
        ProxyStats.connectionsActive.incrementAndGet()

        if (globalVerbose) {
            Log.d(TAG, "New client: $clientAddr")
        }

        try {
            val input = clientSocket.getInputStream()
            val output = clientSocket.getOutputStream()

            // Set handshake read timeout (10s, matching Python asyncio.wait_for(..., timeout=10))
            clientSocket.soTimeout = 10_000

            // Read 64-byte handshake
            val initData = ByteArray(MtProtoConstants.HANDSHAKE_LEN)
            var read = 0
            while (read < initData.size) {
                val n = input.read(initData, read, initData.size - read)
                if (n <= 0) {
                    // Early disconnect before the full handshake is not a bad handshake,
                    // just log debug. Python does NOT increment connections_bad here.
                    if (globalVerbose) {
                        Log.d(TAG, "Client disconnected before handshake: $clientAddr")
                    }
                    return
                }
                read += n
            }

            // Clear handshake timeout — data phase has no timeout
            clientSocket.soTimeout = 0

            // Parse handshake
            val handshake = MtProtoCrypto.parseHandshake(initData, config.secretBytes())
            if (handshake == null) {
                // Count only crypto/protocol failures as bad handshakes.
                // This is where Python increments connections_bad.
                val count = badHandshakeCount.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastBadHandshakeLog > 10_000) {
                    lastBadHandshakeLog = now
                    Log.w(TAG, "Invalid handshake from $clientAddr (total bad: $count)")
                }
                ProxyStats.connectionsBad.incrementAndGet()
                DiagnosticLogger.event("handshake_rejected", "total" to count)
                // Drain connection (matching Python behavior to avoid connection reset)
                try {
                    val drainBuf = ByteArray(4096)
                    clientSocket.soTimeout = 5_000
                    while (input.read(drainBuf) > 0) { /* drain */ }
                } catch (_: Exception) {}
                return
            }

            val dcIndex = handshake.dcIndex.toInt()
            val absDc = if (dcIndex < 0) -dcIndex else dcIndex
            val isMedia = dcIndex < 0
            DiagnosticLogger.event("handshake_ok", "dc" to absDc, "media" to isMedia)

            if (globalVerbose) {
                Log.d(TAG, "Handshake OK: DC=$dcIndex (abs=$absDc, media=$isMedia) from $clientAddr")
            }

            // Generate relay init
            val relayInit = MtProtoCrypto.generateRelayInit(handshake.protoTag, handshake.dcIndex)

            // Build crypto context
            val cryptoCtx = CryptoCtx(
                clientDecryptor = handshake.clientDecryptor,
                clientEncryptor = handshake.clientEncryptor,
                telegramDecryptor = relayInit.telegramDecryptor,
                telegramEncryptor = relayInit.telegramEncryptor,
            )

            val upstream = Bridge.connectUpstream(
                dc = absDc,
                isMedia = isMedia,
                config = config,
                pool = wsPool,
                protoTag = handshake.protoTag,
                relayInit = relayInit.initPacket,
            )
            DiagnosticLogger.event(
                "upstream_connected",
                "dc" to absDc,
                "media" to isMedia,
                "route" to upstream.type,
            )

            if (globalVerbose) {
                Log.d(TAG, "Upstream connected: ${upstream.type} for DC $absDc from $clientAddr")
            }

            try {
                Bridge.bridgeReencrypt(
                    clientInput = input,
                    clientOutput = output,
                    upstream = upstream,
                    cryptoCtx = cryptoCtx,
                    relayInit = relayInit.initPacket,
                    bufferSize = config.bufferSize,
                    protoTag = handshake.protoTag,
                )
            } finally {
                upstream.close()
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            // A handshake timeout is not a cryptographic failure.
            // Python does NOT increment connections_bad here.
            if (globalVerbose) {
                Log.d(TAG, "Handshake timeout: $clientAddr")
            }
            DiagnosticLogger.event("handshake_timeout")
        } catch (e: Exception) {
            if (globalVerbose) {
                Log.w(TAG, "Client handler error ($clientAddr): ${e.message}")
            }
            DiagnosticLogger.failure("client_session_failed", e)
        } finally {
            // Use updateAndGet to prevent going negative (can happen on restart race)
            ProxyStats.connectionsActive.updateAndGet { if (it > 0) it - 1 else 0 }
            try { clientSocket.close() } catch (_: Exception) {}
            if (globalVerbose) {
                Log.d(TAG, "Client disconnected: $clientAddr")
            }
        }
    }

    companion object {
        // Verbose logging — set to BuildConfig.DEBUG by the service layer at startup.
        @Volatile
        var globalVerbose: Boolean = false
    }
}
