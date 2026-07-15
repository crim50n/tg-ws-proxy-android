package dev.minios.tgwsproxy.proxy

import android.util.Log
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Bridge"

internal class CfCooldownTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val strikes: Int, val blockedUntil: Long, val lastFailureAt: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun isCoolingDown(domain: String): Boolean = (states[domain]?.blockedUntil ?: 0L) > clock()

    fun markRateLimited(domain: String): Long {
        val now = clock()
        val state = states.compute(domain) { _, previous ->
            val strikes = if (previous != null && now - previous.lastFailureAt <= MAX_COOLDOWN_MS) {
                previous.strikes + 1
            } else {
                1
            }
            val delay = cooldownDelayMs(strikes)
            State(strikes, now + delay, now)
        }!!
        return state.blockedUntil - now
    }

    fun clear(domain: String) {
        states.remove(domain)
    }

    fun reset() {
        states.clear()
    }

    companion object {
        private const val BASE_COOLDOWN_MS = 45_000L
        private const val MAX_COOLDOWN_MS = 300_000L

        internal fun cooldownDelayMs(strikes: Int): Long {
            var delay = BASE_COOLDOWN_MS
            repeat((strikes - 1).coerceIn(0, 3)) {
                delay = (delay * 2).coerceAtMost(MAX_COOLDOWN_MS)
            }
            return delay
        }
    }
}

/**
 * Bidirectional bridge between client TCP and upstream WebSocket/TCP.
 * Handles re-encryption of MTProto traffic.
 *
 * Matches the Python bridge.py + tg_ws_proxy.py logic including:
 * - WS blacklisting for DCs that always return HTTP redirects
 * - DC fail cooldown (30s) with reduced WS timeout (2s)
 * - MsgSplitter for splitting TCP stream into individual WS frames
 * - CF proxy multi-domain rotation with sticky active domain
 * - TCP fallback using standard DC IPs (not WS redirect IPs)
 * - Per-session stats (up/down bytes/packets, duration)
 */
object Bridge {

    private const val DC_FAIL_COOLDOWN_MS = 30_000L
    private const val WS_FAIL_TIMEOUT_MS = 2_000
    private const val WS_NORMAL_TIMEOUT_MS = 10_000
    private const val TCP_CONNECT_TIMEOUT_MS = 10_000
    private const val DIRECT_IP_FAIL_COOLDOWN_MS = 60 * 60_000L
    private const val WS_PING_INTERVAL_MS = 30_000L
    private const val CF_FALLBACK_PARALLELISM = 2

    enum class UpstreamType { WEBSOCKET, CFPROXY, TCP }

    // WS blacklist: DCs that always return redirects (permanent, like Python ws_blacklist)
    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()

    // DC fail cooldown: timestamp until which WS timeout is reduced
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val directIpFailUntil = ConcurrentHashMap<String, Long>()
    private val cfCooldowns = CfCooldownTracker()

    /**
     * Reset blacklist and cooldown state (called on server restart).
     */
    fun resetState() {
        wsBlacklist.clear()
        dcFailUntil.clear()
        directIpFailUntil.clear()
        cfCooldowns.reset()
    }

    /**
     * Get a summary of currently blacklisted DCs (for stats logging, #12).
     */
    fun blacklistSummary(): String {
        val bl = wsBlacklist.toList()
        return if (bl.isEmpty()) "none" else bl.sorted().joinToString(", ") { "DC$it" }
    }

    data class UpstreamConnection(
        val type: UpstreamType,
        val ws: RawWebSocket? = null,
        val tcpSocket: Socket? = null,
    ) : AutoCloseable {
        override fun close() {
            ws?.close()
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Establish upstream connection with fallback chain matching Python:
     * 1. Optionally try CF before every direct route
     * 2. Check WS blacklist / DC not in config -> skip WS, go to fallback
     * 3. Try WS pool hit, then direct WS with an adaptive timeout
     * 4. On WS failure -> update blacklist/cooldown -> fallback (CF proxy / TCP)
     *
     * #1 FIX: Removed outer domain loop — connectToDc() handles domain iteration internally.
     */
    suspend fun connectUpstream(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
        protoTag: Int,
        relayInit: ByteArray,
    ): UpstreamConnection = withContext(Dispatchers.IO) {
        val attemptStartedAt = System.currentTimeMillis()
        val mediaTag = if (isMedia) " media" else ""
        val dcKey = "$dc${if (isMedia) "m" else ""}"
        val cfAttempted = config.cfProxyEnabled && config.cfProxyFirst

        if (cfAttempted) {
            DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
            val conn = tryCfProxyFallback(dc, isMedia, config)
            if (conn != null) return@withContext conn
        }

        // If DC not in config or WS blacklisted -> skip WS, go straight to fallback
        val targetIp = config.dcRedirects[dc]
        if (targetIp == null || dcKey in wsBlacklist) {
            if (targetIp == null) {
                Log.i(TAG, "DC$dc not in config -> fallback")
            } else {
                Log.i(TAG, "DC$dc$mediaTag WS blacklisted -> fallback")
            }
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
            throw ProxyException("DC$dc$mediaTag no fallback available")
        }

        // Determine WS timeout (adaptive, like Python)
        val now = System.currentTimeMillis()
        val ipFailUntil = directIpFailUntil[targetIp] ?: 0L
        if (config.cfProxyEnabled && now < ipFailUntil) {
            Log.i(TAG, "Direct WS IP $targetIp is cooling down -> fallback")
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
        }
        val failUntil = dcFailUntil[dcKey] ?: 0L
        val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT_MS else WS_NORMAL_TIMEOUT_MS

        // 1. Try WS pool
        val pooledWs = pool?.get(dc, isMedia)
        if (pooledWs != null) {
            ProxyStats.poolHits.incrementAndGet()
            ProxyStats.connectionsWs.incrementAndGet()
            if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Pool hit for DC$dc$mediaTag")
            dcFailUntil.remove(dcKey)
            directIpFailUntil.remove(targetIp)
            DiagnosticLogger.event(
                "upstream_route_ready",
                "dc" to dc,
                "media" to isMedia,
                "route" to "ws_pool",
                "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
            )
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = pooledWs)
        }
        ProxyStats.poolMisses.incrementAndGet()

        // 2. Try WS direct — single call to connectToDc which handles domain iteration
        var ws: RawWebSocket? = null
        var anyRedirect = false
        var allRedirects = true

        Log.i(TAG, "DC$dc$mediaTag -> WS direct via $targetIp (timeout=${wsTimeout}ms)")
        DiagnosticLogger.event(
            "upstream_attempt",
            "dc" to dc,
            "media" to isMedia,
            "route" to "ws_direct",
            "timeoutMs" to wsTimeout,
        )
        try {
            ws = RawWebSocket.connectToDc(
                dc = dc,
                isMedia = isMedia,
                targetIp = targetIp,
                bufferSize = config.bufferSize,
                connectTimeoutMs = wsTimeout,
            )
            allRedirects = false
        } catch (e: WsHandshakeError) {
            ProxyStats.wsErrors.incrementAndGet()
            if (e.isRedirect) {
                anyRedirect = true
                Log.w(TAG, "DC$dc$mediaTag got ${e.statusCode} -> ${e.location ?: "?"}")
            } else {
                allRedirects = false
                Log.w(TAG, "DC$dc$mediaTag WS handshake: ${e.statusLine}")
            }
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "handshake",
                "status" to e.statusCode,
            )
        } catch (e: SocketTimeoutException) {
            ProxyStats.wsErrors.incrementAndGet()
            allRedirects = false
            directIpFailUntil[targetIp] = now + DIRECT_IP_FAIL_COOLDOWN_MS
            Log.w(TAG, "DC$dc$mediaTag WS timeout via $targetIp; IP cooldown enabled")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "timeout",
            )
        } catch (e: Exception) {
            ProxyStats.wsErrors.incrementAndGet()
            allRedirects = false
            Log.w(TAG, "DC$dc$mediaTag WS connect failed: ${e.message}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "connect",
            )
        }

        // WS success
        if (ws != null) {
            dcFailUntil.remove(dcKey)
            directIpFailUntil.remove(targetIp)
            ProxyStats.connectionsWs.incrementAndGet()
            DiagnosticLogger.event(
                "upstream_route_ready",
                "dc" to dc,
                "media" to isMedia,
                "route" to "ws_direct",
                "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
            )
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = ws)
        }

        // WS failed -> update blacklist/cooldown
        if (anyRedirect && allRedirects) {
            wsBlacklist.add(dcKey)
            Log.w(TAG, "DC$dc$mediaTag blacklisted for WS (all redirects)")
        } else {
            dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
            Log.i(TAG, "DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN_MS / 1000}s")
        }

        // 3. Fallback (CF proxy / TCP)
        val conn = doFallback(dc, isMedia, config, protoTag, relayInit, allowCf = !cfAttempted)
        if (conn != null) return@withContext conn

        throw ProxyException("All upstream connection attempts failed for DC $dc")
    }

    /**
     * Fallback chain: CF proxy / TCP direct (order based on cfProxyPriority).
     * Matches Python do_fallback().
     */
    private suspend fun doFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        protoTag: Int,
        relayInit: ByteArray,
        allowCf: Boolean = true,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val methods = mutableListOf<String>()
        methods.add("tcp")

        if (config.cfProxyEnabled && allowCf) {
            if (config.cfProxyPriority) {
                methods.add(0, "cf")  // CF before TCP
            } else {
                methods.add("cf")     // CF after TCP
            }
        }

        for (method in methods) {
            when (method) {
                "cf" -> {
                    DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
                    val conn = tryCfProxyFallback(dc, isMedia, config)
                    if (conn != null) return conn
                }
                "tcp" -> {
                    // TCP fallback uses standard DC IPs (like Python DC_DEFAULT_IPS),
                    // NOT the WS redirect IPs
                    val fallbackIp = MtProtoConstants.DC_IPS[dc]
                    if (fallbackIp != null) {
                        DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "tcp")
                        Log.i(TAG, "DC$dc$mediaTag -> TCP fallback to $fallbackIp:443")
                        val conn = tryTcpDirect(dc, fallbackIp, config.bufferSize)
                        if (conn != null) return conn
                    }
                }
            }
        }
        return null
    }

    /**
     * CF proxy fallback with multi-domain rotation (matches Python _cfproxy_fallback).
     * Tries active domain first, then others. Updates active domain on success.
     */
    private suspend fun tryCfProxyFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val allDomains = if (config.cfProxyUserDomain.isNotBlank()) {
            listOf(config.cfProxyUserDomain)
        } else {
            CfProxyDomains.getDomainsForDc(dc)
        }
        if (allDomains.isEmpty()) return null

        val availableDomains = allDomains.filterNot { cfCooldowns.isCoolingDown(it) }
        if (availableDomains.isEmpty()) {
            Log.i(TAG, "DC$dc$mediaTag -> all CF domains are cooling down")
            return null
        }

        Log.i(TAG, "DC$dc$mediaTag -> trying CF proxy (${availableDomains.size} available)")

        // Keep the sticky domain fast, then cap the remaining connection pressure at two.
        val first = tryCfDomain(dc, isMedia, config.bufferSize, availableDomains.first())
        if (first != null) return cfConnectionReady(dc, isMedia, first.first, first.second)

        for (batch in availableDomains.drop(1).chunked(CF_FALLBACK_PARALLELISM)) {
            val results = coroutineScope {
                batch.map { baseDomain ->
                    async(Dispatchers.IO) {
                        tryCfDomain(dc, isMedia, config.bufferSize, baseDomain)
                    }
                }.awaitAll().filterNotNull()
            }
            val winner = results.firstOrNull() ?: continue
            results.drop(1).forEach { (_, ws) -> ws.close() }
            return cfConnectionReady(dc, isMedia, winner.first, winner.second)
        }
        return null
    }

    private fun tryCfDomain(
        dc: Int,
        isMedia: Boolean,
        bufferSize: Int,
        baseDomain: String,
    ): Pair<String, RawWebSocket>? {
        val mediaTag = if (isMedia) " media" else ""
        return try {
            val ws = RawWebSocket.connectToDc(
                dc = dc,
                isMedia = isMedia,
                targetIp = baseDomain,
                bufferSize = bufferSize,
                cfProxyDomain = baseDomain,
                connectTimeoutMs = WS_NORMAL_TIMEOUT_MS,
            )
            cfCooldowns.clear(baseDomain)
            baseDomain to ws
        } catch (e: Exception) {
            if (e is WsHandshakeError && e.statusCode == 429) {
                val delay = cfCooldowns.markRateLimited(baseDomain)
                Log.w(TAG, "CF proxy $baseDomain cooling down for ${delay / 1000}s after HTTP 429")
            }
            Log.w(TAG, "DC$dc$mediaTag CF proxy via $baseDomain failed: ${e.message}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "media" to isMedia,
                "route" to "cloudflare",
            )
            null
        }
    }

    private fun cfConnectionReady(
        dc: Int,
        isMedia: Boolean,
        baseDomain: String,
        ws: RawWebSocket,
    ): UpstreamConnection {
        CfProxyDomains.setActiveDomain(dc, baseDomain)
        ProxyStats.connectionsCfProxy.incrementAndGet()
        DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
        return UpstreamConnection(UpstreamType.CFPROXY, ws = ws)
    }

    private fun tryTcpDirect(dc: Int, ip: String, bufferSize: Int): UpstreamConnection? {
        return try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.connect(InetSocketAddress(ip, 443), TCP_CONNECT_TIMEOUT_MS)
            ProxyStats.connectionsTcpFallback.incrementAndGet()
            DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "route" to "tcp")
            UpstreamConnection(UpstreamType.TCP, tcpSocket = socket)
        } catch (e: Exception) {
            Log.w(TAG, "TCP direct to DC $dc ($ip) failed: ${e.message}")
            DiagnosticLogger.failure("upstream_attempt_failed", e, "dc" to dc, "route" to "tcp")
            null
        }
    }

    /**
     * Run bidirectional bridge with re-encryption.
     * Client <-> Proxy <-> Telegram
     *
     * For WS/CF upstream: uses MsgSplitter to split TCP data into individual
     * MTProto transport packets, each sent as a separate WS frame (matching Python).
     *
     * Tracks per-session traffic and duration and logs a summary on close.
     * on close (matching Python bridge.py:298-303).
     */
    suspend fun bridgeReencrypt(
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstream: UpstreamConnection,
        cryptoCtx: CryptoCtx,
        relayInit: ByteArray,
        bufferSize: Int,
        protoTag: Int,
    ) = coroutineScope {
        // Per-session traffic statistics.
        val startTime = System.currentTimeMillis()
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val lastActivityAt = AtomicLong(System.currentTimeMillis())

        // Send relay init to upstream
        when (upstream.type) {
            UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                upstream.ws!!.sendBinary(relayInit)
            }
            UpstreamType.TCP -> {
                upstream.tcpSocket!!.getOutputStream().apply {
                    write(relayInit)
                    flush()
                }
            }
        }

        // Create MsgSplitter for WS upstream (matching Python behavior)
        val splitter: MsgSplitter? = if (upstream.type != UpstreamType.TCP) {
            try {
                MsgSplitter(protoTag, relayInit)
            } catch (e: Exception) {
                Log.w(TAG, "MsgSplitter init failed: ${e.message}")
                null
            }
        } else null

        // Cache the TCP output stream for the session.
        val tcpOutput = if (upstream.type == UpstreamType.TCP) {
            upstream.tcpSocket!!.getOutputStream()
        } else null

        val pingJob = upstream.ws?.let { ws ->
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(WS_PING_INTERVAL_MS)
                    if (System.currentTimeMillis() - lastActivityAt.get() < WS_PING_INTERVAL_MS) continue
                    try {
                        ws.sendPing()
                    } catch (_: Exception) {
                        ws.close()
                        break
                    }
                }
            }
        }

        val clientToUpstream = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(65536) // Match Python's reader.read(65536)
                while (isActive) {
                    val read = clientInput.read(buf)
                    if (read <= 0) {
                        // Flush splitter on EOF (matching Python)
                        if (splitter != null && upstream.ws != null) {
                            val tail = splitter.flush()
                            if (tail.isNotEmpty()) {
                                upstream.ws.sendBinary(tail.first())
                            }
                        }
                        break
                    }

                    val data = buf.copyOf(read)
                    ProxyStats.bytesUp.addAndGet(read.toLong())
                    lastActivityAt.set(System.currentTimeMillis())
                    upBytes += read
                    upPackets++

                    // Decrypt from client, re-encrypt for telegram
                    val decrypted = cryptoCtx.clientDecryptor.update(data)
                    val reencrypted = cryptoCtx.telegramEncryptor.update(decrypted)

                    when (upstream.type) {
                        UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                            if (splitter != null) {
                                val parts = splitter.split(reencrypted)
                                if (parts.isEmpty()) continue
                                if (parts.size > 1) {
                                    upstream.ws!!.sendBatch(parts)
                                } else {
                                    upstream.ws!!.sendBinary(parts[0])
                                }
                            } else {
                                upstream.ws!!.sendBinary(reencrypted)
                            }
                        }
                        UpstreamType.TCP -> {
                            tcpOutput!!.write(reencrypted)
                            tcpOutput.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Client->upstream ended: ${e.message}")
            }
        }

        val upstreamToClient = launch(Dispatchers.IO) {
            try {
                when (upstream.type) {
                    UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                        while (isActive) {
                            val data = upstream.ws!!.readBinary() ?: break
                            ProxyStats.bytesDown.addAndGet(data.size.toLong())
                            lastActivityAt.set(System.currentTimeMillis())
                            downBytes += data.size
                            downPackets++

                            // Decrypt from telegram, re-encrypt for client
                            val decrypted = cryptoCtx.telegramDecryptor.update(data)
                            val reencrypted = cryptoCtx.clientEncryptor.update(decrypted)

                            clientOutput.write(reencrypted)
                            clientOutput.flush()
                        }
                    }
                    UpstreamType.TCP -> {
                        val buf = ByteArray(65536) // Match Python's reader.read(65536)
                        val tcpInput = upstream.tcpSocket!!.getInputStream()
                        while (isActive) {
                            val read = tcpInput.read(buf)
                            if (read <= 0) break
                            ProxyStats.bytesDown.addAndGet(read.toLong())
                            lastActivityAt.set(System.currentTimeMillis())
                            downBytes += read
                            downPackets++

                            val data = buf.copyOf(read)
                            val decrypted = cryptoCtx.telegramDecryptor.update(data)
                            val reencrypted = cryptoCtx.clientEncryptor.update(decrypted)

                            clientOutput.write(reencrypted)
                            clientOutput.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Upstream->client ended: ${e.message}")
            }
        }

        // Wait for either direction to complete, then cancel the other
        try {
            awaitFirstCompletion(clientToUpstream, upstreamToClient)
        } finally {
            clientToUpstream.cancel()
            upstreamToClient.cancel()
            pingJob?.cancel()

            // Log a compact per-session summary.
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            Log.i(TAG, "Session: ${upstream.type} %.1fs up=%s(%d) down=%s(%d)".format(
                elapsed,
                MtProtoConstants.humanBytes(upBytes), upPackets,
                MtProtoConstants.humanBytes(downBytes), downPackets,
            ))
            DiagnosticLogger.event(
                "session_completed",
                "route" to upstream.type,
                "durationMs" to System.currentTimeMillis() - startTime,
                "bytesUp" to upBytes,
                "bytesDown" to downBytes,
                "packetsUp" to upPackets,
                "packetsDown" to downPackets,
            )
        }
    }

    private suspend fun awaitFirstCompletion(vararg jobs: Job) {
        try {
            // Wait for first job to complete using select
            val deferreds = jobs.map { job ->
                CompletableDeferred<Unit>().also { d ->
                    job.invokeOnCompletion { d.complete(Unit) }
                }
            }
            // Use kotlinx select to await whichever completes first
            select<Unit> {
                for (d in deferreds) {
                    d.onAwait {}
                }
            }
        } catch (_: Exception) {
        }
    }
}

class ProxyException(message: String, cause: Throwable? = null) : Exception(message, cause)
