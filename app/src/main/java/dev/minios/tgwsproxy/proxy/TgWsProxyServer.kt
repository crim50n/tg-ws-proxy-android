package dev.minios.tgwsproxy.proxy

import android.util.Log
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
    private var serverSocket: ServerSocket? = null
    private var wsPool: WsPool? = null
    private var scope: CoroutineScope? = null
    private var statsJob: Job? = null
    private val stoppedLatch = CountDownLatch(1)

    // Throttle invalid handshake log spam
    private val badHandshakeCount = AtomicLong(0)
    private var lastBadHandshakeLog = 0L

    // #13: Track in-flight client handler jobs for graceful shutdown
    private val clientJobs = ConcurrentHashMap.newKeySet<Job>()

    val isRunning: Boolean get() = running.get()

    var onStatusChange: ((Boolean) -> Unit)? = null

    fun updateConfig(newConfig: ProxyConfig) {
        config = newConfig
        // #20: Update pool redirects dynamically
        wsPool?.updateRedirects(newConfig.dcRedirects)
    }

    /**
     * Start the proxy server. Blocks until stopped.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (running.getAndSet(true)) {
            Log.w(TAG, "Server already running")
            return@withContext
        }

        // #16: Only reset active connections on restart, not all stats
        ProxyStats.resetActive()
        badHandshakeCount.set(0)
        lastBadHandshakeLog = 0L
        Bridge.resetState() // Clear WS blacklist and cooldown on restart
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        try {
            // Initialize WS pool
            // #20: Pool no longer takes dcRedirects in constructor
            wsPool = WsPool(
                poolSize = config.poolSize,
                bufferSize = config.bufferSize,
            ).also {
                it.start(scope!!)
                // #20: Pass dcRedirects dynamically via warmUp
                it.warmUp(config.dcRedirects)
            }

            // #23: CF domain refresh is now handled by CfProxyDomains.startBackgroundRefresh()
            // (started by ProxyService), not by an inline coroutine here.

            // #12: Stats logging includes WS blacklist summary
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

            // #25: Detailed startup banner matching Python
            logStartupBanner()

            onStatusChange?.invoke(true)

            // Accept connections
            while (running.get()) {
                try {
                    val clientSocket = ss.accept()
                    clientSocket.tcpNoDelay = true
                    clientSocket.sendBufferSize = config.bufferSize
                    clientSocket.receiveBufferSize = config.bufferSize

                    val job = scope!!.launch {
                        handleClient(clientSocket)
                    }
                    // #13: Track the job for graceful shutdown
                    clientJobs.add(job)
                    job.invokeOnCompletion { clientJobs.remove(job) }
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error: ${e.message}", e)
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
        if (!running.getAndSet(false)) return

        Log.i(TAG, "Stopping proxy server...")

        // Close server socket first to unblock accept()
        try {
            serverSocket?.close()
        } catch (_: Exception) {}

        cleanup()

        // Wait for the server loop to finish (max 3 seconds)
        try {
            stoppedLatch.await(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}

        Log.i(TAG, "Proxy server stopped. ${ProxyStats.formatStats()}")
    }

    /**
     * #13: Graceful shutdown — give in-flight connections a grace period
     * before cancelling. Python relies on asyncio.run() teardown which
     * cancels all tasks, but existing connections finish their current I/O.
     */
    private fun cleanup() {
        statsJob?.cancel()
        statsJob = null
        wsPool?.stop()
        wsPool = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        // #13: Give in-flight handlers a brief grace period, then cancel scope
        if (clientJobs.isNotEmpty()) {
            Log.i(TAG, "Waiting for ${clientJobs.size} active connection(s) to finish...")
            // Give 2 seconds grace, then cancel
            Thread.sleep(500)
        }

        scope?.cancel()
        scope = null
        clientJobs.clear()

        onStatusChange?.invoke(false)
    }

    /**
     * #25: Detailed startup banner matching Python's output.
     */
    private fun logStartupBanner() {
        val sep = "=" .repeat(60)
        Log.i(TAG, sep)
        Log.i(TAG, "  Telegram MTProto WS Bridge Proxy")
        Log.i(TAG, "  Listening on   ${config.host}:${config.port}")
        Log.i(TAG, "  Secret:        ${config.secret}")
        Log.i(TAG, "  Target DC IPs:")
        for (dc in config.dcRedirects.keys.sorted()) {
            Log.i(TAG, "    DC$dc: ${config.dcRedirects[dc]}")
        }
        if (config.cfProxyEnabled) {
            val prio = if (config.cfProxyPriority) "CF first" else "TCP first"
            val domainType = if (config.cfProxyUserDomain.isNotBlank()) "user" else "auto"
            Log.i(TAG, "  CF proxy:      enabled ($prio | $domainType)")
        }
        Log.i(TAG, sep)
        Log.i(TAG, "  Connect links:")
        Log.i(TAG, "    dd (random padding):  ${config.proxyLink()}")
        Log.i(TAG, sep)
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
                    // #17: Early disconnect before full handshake — NOT a bad handshake,
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
                // #17: Only count crypto-failed handshakes as "bad" (wrong secret/proto).
                // This is where Python increments connections_bad.
                val count = badHandshakeCount.incrementAndGet()
                val now = System.currentTimeMillis()
                if (now - lastBadHandshakeLog > 10_000) {
                    lastBadHandshakeLog = now
                    Log.w(TAG, "Invalid handshake from $clientAddr (total bad: $count)")
                }
                ProxyStats.connectionsBad.incrementAndGet()
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

            // Connect upstream (now passes protoTag and relayInit for MsgSplitter and blacklisting)
            val upstream = Bridge.connectUpstream(
                dc = absDc,
                isMedia = isMedia,
                config = config,
                pool = wsPool,
                protoTag = handshake.protoTag,
                relayInit = relayInit.initPacket,
            )

            if (globalVerbose) {
                Log.d(TAG, "Upstream connected: ${upstream.type} for DC $absDc from $clientAddr")
            }

            // Run bridge (now passes protoTag for MsgSplitter)
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

        } catch (e: java.net.SocketTimeoutException) {
            // #17: Handshake timeout — NOT a bad handshake (client just timed out).
            // Python does NOT increment connections_bad here.
            if (globalVerbose) {
                Log.d(TAG, "Handshake timeout: $clientAddr")
            }
        } catch (e: Exception) {
            if (globalVerbose) {
                Log.w(TAG, "Client handler error ($clientAddr): ${e.message}")
            }
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
