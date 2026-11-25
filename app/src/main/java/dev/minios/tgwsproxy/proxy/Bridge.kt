package dev.minios.tgwsproxy.proxy

import android.util.Log
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.diagnostics.DiagnosticRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

private const val TAG = "Bridge"
private const val CF_STAGE_TIMEOUT_MS = 8_000L
private const val CF_H2_SETUP_TIMEOUT_MS = 1_000L

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
class Bridge(private val connectivity: ConnectivityTracker) {
    private val cfDnsResolver = CfDnsResolver()
    private val h2Pool = CfH2Pool(connectivity)

    private val DC_FAIL_COOLDOWN_MS = 30_000L
    private val WS_FAIL_TIMEOUT_MS = 2_000
    private val WS_NORMAL_TIMEOUT_MS = 10_000
    private val DIRECT_IP_FAIL_COOLDOWN_MS = 60 * 60_000L

    private fun checkEpoch(epoch: Long) {
        if (!connectivity.isCurrent(epoch)) throw CancellationException("Connectivity changed")
    }

    private fun record(epoch: Long, dc: Int, media: Boolean, route: TransportRoute, phase: RoutePhase) {
        connectivity.recordIfCurrent(epoch, requireInternet = phase == RoutePhase.FAILED || phase == RoutePhase.COOLDOWN) {
            connectivity.record(epoch, RouteKey(dc, media, route), phase)
        }
    }

    private fun dial(epoch: Long, dc: Int, media: Boolean, target: String, buffer: Int,
                     timeout: Int, cfDomain: String? = null, secure: Boolean = true,
                     isTestDc: Boolean = false, attempt: TransportAttemptResources,
                     onStage: TransportStageObserver): RawWebSocket =
        withTrackedSockets(epoch, attempt) { onSocketCreated ->
            RawWebSocket.connectToDc(dc, media, target, buffer, cfDomain, timeout,
                onSocketCreated = onSocketCreated,
                 resolveCfAddress = { domain ->
                     checkDialCurrent(epoch, attempt)
                     cfDnsResolver.resolve(domain, connectivity, epoch, attempt).also { checkDialCurrent(epoch, attempt) }
                },
                cachedCfAddress = { domain ->
                    var address: String? = null
                    connectivity.recordIfCurrent(epoch) { address = cfDnsResolver.cached(domain) }
                    address
                 }, secure = secure, isTestDc = isTestDc,
                 onUpgradeRejected = attempt::recordUpgradeRejection,
                 onStage = onStage,
                 checkCurrent = { checkDialCurrent(epoch, attempt) })
        }

    private fun checkDialCurrent(epoch: Long, attempt: TransportAttemptResources) {
        if (!attempt.isActive) throw CancellationException("Upstream dial cancelled")
        checkEpoch(epoch)
    }

    private fun withTrackedSockets(
        epoch: Long,
        attempt: TransportAttemptResources,
        connect: ((Socket) -> Unit) -> RawWebSocket,
    ): RawWebSocket {
        checkDialCurrent(epoch, attempt)
        val sockets = mutableListOf<Socket>()
        try {
            val ws = connect { socket ->
                attempt.register(socket)
                sockets.add(socket)
                pendingSockets.add(socket)
                if (!connectivity.trackSocket(epoch, socket)) checkEpoch(epoch)
            }
            if (!connectivity.isCurrent(epoch)) {
                dispatchTransportCleanup(ws::abort)
                checkEpoch(epoch)
            }
            return ws
        } finally {
            sockets.forEach {
                connectivity.releaseSocket(it)
                pendingSockets.remove(it)
                attempt.release(it)
            }
        }
    }

    enum class UpstreamType { WEBSOCKET, CFPROXY, TCP }

    // WS blacklist: DCs that always return redirects (permanent, like Python ws_blacklist)
    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()

    // DC fail cooldown: timestamp until which WS timeout is reduced
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val directIpFailUntil = ConcurrentHashMap<String, Long>()
    private val directWsCircuitBreaker = DirectWsCircuitBreaker()
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val cfRuntimeLock = Any()
    private var cfRuntime: CfRuntime? = null

    private class CfRuntime(val epoch: Long) {
        val cloudflare = CfTransportPolicy()
        val workers = CfTransportPolicy(lastSuccessWins = true)
        val rateLimits = CfCooldownTracker()
        val workerCooldowns = WorkerCooldownTracker()
        val activeWorkers = ConcurrentHashMap<Int, String>()
        val activeCustomCf = ConcurrentHashMap<Int, String>()
    }

    private fun currentCfRuntime(epoch: Long): CfRuntime {
        var runtime: CfRuntime? = null
        connectivity.recordIfCurrent(epoch) {
            synchronized(cfRuntimeLock) {
                runtime = cfRuntime?.takeIf { it.epoch == epoch } ?: CfRuntime(epoch).also { cfRuntime = it }
            }
        }
        return runtime ?: throw CancellationException("Connectivity changed")
    }

    private fun recordCfIfCurrent(runtime: CfRuntime, requireInternet: Boolean = false, action: () -> Unit): Boolean {
        var recorded = false
        connectivity.recordIfCurrent(runtime.epoch, requireInternet) {
            synchronized(cfRuntimeLock) {
                if (cfRuntime === runtime) {
                    action()
                    recorded = true
                }
            }
        }
        return recorded
    }

    private fun checkCfRuntime(runtime: CfRuntime) {
        checkEpoch(runtime.epoch)
        if (synchronized(cfRuntimeLock) { cfRuntime !== runtime }) throw CancellationException("Route reset")
    }

    /**
     * Reset blacklist and cooldown state (called on server restart).
     */
    fun resetState() {
        synchronized(cfRuntimeLock) { cfRuntime = null }
        pendingSockets.forEach { try { it.close() } catch (_: Exception) {} }
        pendingSockets.clear()
        wsBlacklist.clear()
        dcFailUntil.clear()
        directIpFailUntil.clear()
        cfDnsResolver.reset()
        directWsCircuitBreaker.reset()
        h2Pool.reset()
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
        val epoch: Long = 0,
        val h2: CfH2Pool.Channel? = null,
        val worker: Boolean = false,
    ) : AutoCloseable {
        fun abort() {
            ws?.abort()
            h2?.close()
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
        override fun close() {
            ws?.close()
            h2?.close()
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
    internal suspend fun connectUpstream(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
        protoTag: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long = connectivity.state.value.epoch,
    ): UpstreamConnection {
        val ready = selectUpstream(dc, isMedia, config, pool, protoTag, relayInit, tcpFallback, epoch)
        try {
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            ready.ws?.let { ws ->
                val route = when {
                    ready.worker -> "cf_worker"
                    ready.type == UpstreamType.CFPROXY -> "cloudflare"
                    else -> "websocket"
                }
                val startedAt = transportClockMs()
                val closePending = AutoCloseable { dispatchTransportCleanup(ws::abort) }
                if (!connectivity.trackResource(epoch, closePending)) checkEpoch(epoch)
                try {
                    ws.sendRelayInit(relayInit) { checkEpoch(epoch) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    checkEpoch(epoch)
                    DiagnosticLogger.warning("upstream_attempt_failed", "dc" to dc, "media" to isMedia, "route" to route,
                        "stage" to "relay_init", "elapsedMs" to transportClockMs() - startedAt,
                        "error" to cfTransportErrorForLog(e))
                    // This timeout is in upstream setup, not in the client's MTProto handshake.
                    throw ProxyException("Upstream relay init failed", e)
                } finally {
                    connectivity.releaseResource(closePending)
                }
            }
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            return ready
        } catch (e: Throwable) {
            dispatchTransportCleanup(ready::abort)
            throw e
        }
    }

    private suspend fun selectUpstream(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
        protoTag: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
    ): UpstreamConnection {
        checkEpoch(epoch)
        val context = currentCoroutineContext()
        context.ensureActive()
        val mediaTag = if (isMedia) " media" else ""
        val dcKey = "$dc${if (isMedia) "m" else ""}${if (config.forceTestDc) "t" else ""}"
        var adaptiveCfFirst = false
        connectivity.recordIfCurrent(epoch) {
            adaptiveCfFirst = !config.forceTestDc && config.autoOptimizeConnection && config.cfProxyEnabled &&
                    directWsCircuitBreaker.isBlocked(dc, isMedia)
        }
        val cfAttempted = !config.forceTestDc && config.cfProxyEnabled && (config.cfProxyFirst || adaptiveCfFirst)

        if (cfAttempted) {
            if (adaptiveCfFirst) {
                DiagnosticLogger.event(
                    "adaptive_route_override",
                    "dc" to dc,
                    "media" to isMedia,
                    "route" to "cloudflare",
                )
            }
            DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
            val conn = tryWorkerFallback(dc, isMedia, config, epoch)
                ?: tryCfProxyFallback(dc, isMedia, config, epoch)
            if (conn != null) return conn
            context.ensureActive()
        }

        // If DC not in config or WS blacklisted -> skip WS, go straight to fallback
        val targetIp = config.dcRedirects[dc]
        if (targetIp == null || dcKey in wsBlacklist) {
            if (targetIp == null) {
                Log.i(TAG, "DC$dc not in config -> fallback")
            } else {
                Log.i(TAG, "DC$dc$mediaTag WS blacklisted -> fallback")
            }
            if (dcKey in wsBlacklist) record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.COOLDOWN)
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
            if (conn != null) return conn
            context.ensureActive()
            connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
            throw ProxyException("DC$dc$mediaTag no fallback available")
        }

        // Determine WS timeout (adaptive, like Python)
        val now = System.currentTimeMillis()
        val ipFailUntil = directIpFailUntil[targetIp] ?: 0L
        if (config.cfProxyEnabled && now < ipFailUntil) {
            Log.i(TAG, "DC$dc$mediaTag direct WS IP cooling down -> fallback")
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.COOLDOWN)
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
            if (conn != null) return conn
            context.ensureActive()
            connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
            throw ProxyException("All upstream routes unavailable")
        }
        val failUntil = dcFailUntil[dcKey] ?: 0L
        val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT_MS else WS_NORMAL_TIMEOUT_MS

        // 1. Try WS pool
        context.ensureActive()
        checkEpoch(epoch)
        var pooledWs: RawWebSocket? = null
        val poolStartedAt = transportClockMs()
        connectivity.recordIfCurrent(epoch) { if (!config.forceTestDc) pooledWs = pool?.get(dc, isMedia) }
        val poolElapsedMs = transportClockMs() - poolStartedAt
        if (pooledWs != null) {
            val ready = pooledWs!!
            if (!context.isActive) {
                dispatchTransportCleanup(ready::abort)
                context.ensureActive()
            }
            if (!connectivity.recordIfCurrent(epoch) {
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
                    "elapsedMs" to poolElapsedMs,
                )
                record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTED)
            }) { dispatchTransportCleanup(ready::abort); checkEpoch(epoch) }
            return UpstreamConnection(UpstreamType.WEBSOCKET, ws = ready, epoch = epoch)
        }
        connectivity.recordIfCurrent(epoch) { ProxyStats.poolMisses.incrementAndGet() }

        // 2. Try WS direct — single call to connectToDc which handles domain iteration
        var ws: RawWebSocket? = null
        var allRedirects = false
        var attempted = true
        val dialStartedAt = transportClockMs()

        context.ensureActive()
        checkEpoch(epoch)
        Log.i(TAG, "DC$dc$mediaTag -> WS direct (timeout=${wsTimeout}ms)")
        DiagnosticLogger.event(
            "upstream_attempt",
            "dc" to dc,
            "media" to isMedia,
            "route" to "ws_direct",
            "timeoutMs" to wsTimeout,
        )
        try {
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTING)
            ws = boundedTransportAttempt(wsTimeout.toLong(), RawWebSocket::abort) { attempt ->
                dial(epoch, dc, isMedia, targetIp, config.bufferSize, wsTimeout,
                    isTestDc = config.forceTestDc, attempt = attempt,
                    onStage = transportStageObserver(context, epoch, dc, isMedia, "ws_direct", tls = true))
            }
        } catch (_: TransportDialNotStarted) {
            context.ensureActive()
            checkEpoch(epoch)
            attempted = false
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.COOLDOWN)
        } catch (e: WsHandshakeError) {
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            if (e.isRedirect) {
                allRedirects = true
                Log.w(TAG, "DC$dc$mediaTag WS redirect HTTP ${e.statusCode}")
            } else {
                Log.w(TAG, "DC$dc$mediaTag WS handshake HTTP ${e.statusCode}")
            }
            recordTransportFailure(dc, isMedia, "ws_direct", dialStartedAt, e)
        } catch (e: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            connectivity.recordIfCurrent(epoch, requireInternet = true) {
                directIpFailUntil[targetIp] = now + DIRECT_IP_FAIL_COOLDOWN_MS
            }
            Log.w(TAG, "DC$dc$mediaTag WS timeout; IP cooldown enabled")
            recordTransportFailure(dc, isMedia, "ws_direct", dialStartedAt, e)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            Log.w(TAG, "DC$dc$mediaTag WS connect failed: ${cfTransportErrorForLog(e)}")
            recordTransportFailure(dc, isMedia, "ws_direct", dialStartedAt, e)
        }

        // WS success
        if (ws != null) {
            if (!context.isActive) {
                dispatchTransportCleanup(ws::abort)
                context.ensureActive()
            }
            if (!connectivity.recordIfCurrent(epoch) {
                dcFailUntil.remove(dcKey)
                directIpFailUntil.remove(targetIp)
                ProxyStats.connectionsWs.incrementAndGet()
                DiagnosticLogger.event(
                    "upstream_route_ready",
                    "dc" to dc,
                    "media" to isMedia,
                    "route" to "ws_direct",
                    "elapsedMs" to transportClockMs() - dialStartedAt,
                )
                record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTED)
            }) { dispatchTransportCleanup(ws::abort); checkEpoch(epoch) }
            return UpstreamConnection(UpstreamType.WEBSOCKET, ws = ws, epoch = epoch)
        }

        // WS failed -> update blacklist/cooldown
        context.ensureActive()
        if (attempted) {
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.FAILED)
            connectivity.recordIfCurrent(epoch, requireInternet = true) {
                if (allRedirects) {
                    wsBlacklist.add(dcKey)
                    Log.w(TAG, "DC$dc$mediaTag blacklisted for WS (all redirects)")
                } else {
                    dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
                    Log.i(TAG, "DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN_MS / 1000}s")
                }
            }
        }

        // 3. Fallback (CF proxy / TCP)
        val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
        if (conn != null) return conn

        context.ensureActive()
        checkEpoch(epoch)
        connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
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
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
        allowCf: Boolean = true,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val methods = mutableListOf<String>()
        // Do not repeat Workers after an unsuccessful CF-first chain.
        if ((allowCf || config.forceTestDc) && config.cfWorkerDomains.isNotEmpty()) {
            methods.add("worker")
        }
        methods.add("tcp")

        if (config.cfProxyEnabled && allowCf && !config.forceTestDc) {
            if (config.cfProxyPriority) {
                methods.add(methods.indexOf("tcp"), "cf")
            } else {
                methods.add("cf")     // CF after TCP
            }
        }

        for (method in methods) {
            currentCoroutineContext().ensureActive()
            checkEpoch(epoch)
            when (method) {
                "worker" -> tryWorkerFallback(dc, isMedia, config, epoch)?.let { return it }
                "cf" -> {
                    DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
                    val conn = tryCfProxyFallback(dc, isMedia, config, epoch)
                    if (conn != null) return conn
                }
                "tcp" -> {
                    // TCP fallback uses standard DC IPs (like Python DC_DEFAULT_IPS),
                    // NOT the WS redirect IPs
                    val fallbackIp = (if (config.forceTestDc) MtProtoConstants.DC_TEST_IPS else MtProtoConstants.DC_IPS)[dc]
                    if (fallbackIp != null) {
                        DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "tcp")
                        Log.i(TAG, "DC$dc$mediaTag -> TCP fallback")
                        val conn = tryTcpDirect(dc, isMedia, fallbackIp, config.bufferSize, relayInit, tcpFallback, epoch)
                        if (conn != null) return conn
                    }
                }
            }
        }
        return null
    }

    private suspend fun tryWorkerFallback(dc: Int, isMedia: Boolean, config: ProxyConfig, epoch: Long): UpstreamConnection? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val destination = (if (config.forceTestDc) MtProtoConstants.DC_TEST_IPS else MtProtoConstants.DC_IPS)[dc]
            ?: return null
        if (config.cfWorkerDomains.isEmpty()) return null
        val runtime = currentCfRuntime(epoch)
        val deadline = transportClockMs() + CF_STAGE_TIMEOUT_MS
        val path = "/apiws?dst=${java.net.URLEncoder.encode(destination, "UTF-8")}&dc=$dc"
        val domains = config.cfWorkerDomains.distinct().sortedBy { it != runtime.activeWorkers[dc] }
        for (domain in domains) {
            context.ensureActive()
            checkCfRuntime(runtime)
            if (transportClockMs() >= deadline) break
            if (runtime.workerCooldowns.isCoolingDown(domain)) {
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN)
                continue
            }
            var dialStartedAt = transportClockMs()
            val connected = runtime.workers.connect(domain, config.cfSecure, deadline,
                onSkipped = { record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN) },
                onFailure = { secure, error ->
                    checkCfRuntime(runtime)
                    recordCfIfCurrent(runtime, requireInternet = true) {
                        runtime.workerCooldowns.markFailure(domain, error)?.let { delay ->
                            DiagnosticLogger.warning("worker_cooldown", "dc" to dc, "retryInMs" to delay,
                                "error" to cfTransportErrorForLog(error))
                        }
                    }
                    recordCfAttemptFailure(dc, isMedia, runtime, secure, error, "cf_worker", dialStartedAt)
                },
                onTimeoutsExhausted = {
                    checkCfRuntime(runtime)
                    recordCfIfCurrent(runtime, requireInternet = true) {
                        runtime.workerCooldowns.markTimeoutsExhausted(domain)?.let { delay ->
                            DiagnosticLogger.warning("worker_cooldown", "dc" to dc, "retryInMs" to delay,
                                "reason" to "mode_timeouts_exhausted")
                        }
                    }
                },
            ) { secure, timeout ->
                checkCfRuntime(runtime)
                dialStartedAt = transportClockMs()
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTING)
                boundedTransportAttempt(timeout, RawWebSocket::abort) { attempt ->
                    withTrackedSockets(epoch, attempt) { onSocketCreated ->
                        RawWebSocket.connect(domain, domain, path, port = if (secure) 443 else 80,
                             connectTimeoutMs = timeout.toInt(), bufferSize = config.bufferSize,
                             onSocketCreated = onSocketCreated, secure = secure,
                             onUpgradeRejected = attempt::recordUpgradeRejection,
                             onStage = transportStageObserver(context, epoch, dc, isMedia, "cf_worker", secure),
                             checkCurrent = { checkDialCurrent(epoch, attempt) })
                    }
                }
            } ?: continue
            val ws = connected.value
            if (!context.isActive) {
                dispatchTransportCleanup(ws::abort)
                context.ensureActive()
            }
            if (!recordCfIfCurrent(runtime) {
                runtime.workers.recordSuccess(connected.secure)
                runtime.activeWorkers[dc] = domain
                ProxyStats.connectionsCfProxy.incrementAndGet()
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
                DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "media" to isMedia,
                    "route" to "cf_worker", "tls" to connected.secure, "elapsedMs" to transportClockMs() - dialStartedAt)
            }) { dispatchTransportCleanup(ws::abort); checkCfRuntime(runtime) }
            return UpstreamConnection(UpstreamType.CFPROXY, ws = ws, epoch = epoch, worker = true)
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
        epoch: Long,
    ): UpstreamConnection? {
        val context = currentCoroutineContext()
        context.ensureActive()
        checkEpoch(epoch)
        if (config.forceTestDc || !config.cfProxyEnabled) return null
        val runtime = currentCfRuntime(epoch)
        val deadline = transportClockMs() + CF_STAGE_TIMEOUT_MS
        val mediaTag = if (isMedia) " media" else ""
        val allDomains = config.customCfDomains().distinct().sortedBy { it != runtime.activeCustomCf[dc] }
            .ifEmpty { CfProxyDomains.getDomainsForDc(dc) }
        val availableDomains = allDomains.filterNot(runtime.rateLimits::isCoolingDown)
        if (availableDomains.isEmpty()) {
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN)
            return null
        }

        if (isMedia && config.cfH2Media && config.cfSecure && runtime.cloudflare.prefersTls()) {
            val h2Domains = availableDomains.filter(runtime.cloudflare::tlsAvailable)
            val h2Timeout = (deadline - transportClockMs()).coerceAtMost(CF_H2_SETUP_TIMEOUT_MS)
            if (h2Domains.isNotEmpty() && h2Timeout > 0) {
                val callerContext = currentCoroutineContext()
                // Native H2 is TLS-only. Its HEAD can time out independently of the WS
                // upgrade; a failed probe alone does not justify a plaintext WS attempt.
                val ready = try {
                    boundedTransportAttempt(h2Timeout, { it?.close() }) { attempt ->
                        h2Pool.open(dc, h2Domains, epoch, h2Timeout, attempt,
                            callerIsActive = { callerContext.isActive }) { domain ->
                            runtime.cloudflare.prefersTls() && runtime.cloudflare.tlsAvailable(domain)
                        }
                    }
                } catch (_: SocketTimeoutException) {
                    null
                } catch (_: TransportDialNotStarted) {
                    null
                }
                if (ready != null) {
                    if (!recordCfIfCurrent(runtime) {
                        ProxyStats.connectionsCfProxy.incrementAndGet()
                        record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
                    }) { ready.close(); checkCfRuntime(runtime) }
                    return UpstreamConnection(UpstreamType.CFPROXY, epoch = epoch, h2 = ready)
                }
            }
        }

        Log.i(TAG, "DC$dc$mediaTag -> trying CF proxy (${availableDomains.size} available)")
        for (domain in availableDomains) {
            context.ensureActive()
            checkCfRuntime(runtime)
            if (transportClockMs() >= deadline) break
            if (runtime.rateLimits.isCoolingDown(domain)) continue
            val dialStartedAt = transportClockMs()
            val connected = tryCfDomain(dc, isMedia, config, domain, runtime, deadline) ?: continue
            val ws = connected.value
            if (!recordCfIfCurrent(runtime) {
                runtime.cloudflare.recordSuccess(connected.secure)
                cfDnsResolver.markAvailable()
                runtime.rateLimits.clear(domain)
                CfProxyDomains.setActiveDomain(dc, domain)
                runtime.activeCustomCf[dc] = domain
                ProxyStats.connectionsCfProxy.incrementAndGet()
                DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "media" to isMedia,
                    "route" to "cloudflare", "tls" to connected.secure, "elapsedMs" to transportClockMs() - dialStartedAt)
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
            }) { dispatchTransportCleanup(ws::abort); checkCfRuntime(runtime) }
            return UpstreamConnection(UpstreamType.CFPROXY, ws = ws, epoch = epoch)
        }
        return null
    }

    private suspend fun tryCfDomain(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        baseDomain: String,
        runtime: CfRuntime,
        deadline: Long,
    ): CfTransportPolicy.Connected<RawWebSocket>? {
        val epoch = runtime.epoch
        val context = currentCoroutineContext()
        var dialStartedAt = transportClockMs()
        var skip = false
        recordCfIfCurrent(runtime, requireInternet = true) { skip = cfDnsResolver.shouldSkipConnectionAttempt() }
        if (skip) {
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN)
            return null
        }
        return runtime.cloudflare.connect(baseDomain, config.cfSecure, deadline,
            onSkipped = { record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN) },
            onFailure = { secure, error ->
                checkCfRuntime(runtime)
                recordCfIfCurrent(runtime, requireInternet = true) {
                    if (error is WsHandshakeError && error.statusCode == 429) {
                        val delay = runtime.rateLimits.markRateLimited(baseDomain)
                        Log.w(TAG, "CF proxy ${DiagnosticRedactor.field("domain", baseDomain)} cooling down for ${delay / 1000}s after HTTP 429")
                    }
                }
                Log.w(TAG, "DC$dc CF proxy via ${DiagnosticRedactor.field("domain", baseDomain)} failed " +
                        "(tls=$secure): ${cfTransportErrorForLog(error)}")
                recordCfAttemptFailure(dc, isMedia, runtime, secure, error, "cloudflare", dialStartedAt)
            },
        ) { secure, timeout ->
            checkCfRuntime(runtime)
            dialStartedAt = transportClockMs()
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTING)
            boundedTransportAttempt(timeout, RawWebSocket::abort) { attempt ->
                dial(epoch, dc, isMedia, baseDomain, config.bufferSize, timeout.toInt(), baseDomain, secure, attempt = attempt,
                    onStage = transportStageObserver(context, epoch, dc, isMedia, "cloudflare", secure))
            }
        }
    }

    private fun recordCfAttemptFailure(
        dc: Int,
        isMedia: Boolean,
        runtime: CfRuntime,
        secure: Boolean,
        error: Exception,
        route: String,
        startedAt: Long,
    ) {
        if (!recordCfIfCurrent(runtime) {
            val phase = if (error is TransportDialNotStarted) RoutePhase.COOLDOWN else RoutePhase.FAILED
            record(runtime.epoch, dc, isMedia, TransportRoute.CLOUDFLARE, phase)
        }) {
            return
        }
        if (error !is TransportDialNotStarted) recordTransportFailure(dc, isMedia, route, startedAt, error, secure)
    }

    private suspend fun tryTcpDirect(
        dc: Int,
        isMedia: Boolean,
        ip: String,
        bufferSize: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
    ): UpstreamConnection? {
        val context = currentCoroutineContext()
        val startedAt = transportClockMs()
        return try {
            context.ensureActive()
            checkEpoch(epoch)
            record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.CONNECTING)
            val socket = tcpFallback.connect(ip, 443, bufferSize, relayInit,
                canRecordFailure = { connectivity.isCurrent(epoch) && connectivity.state.value.internetConfirmed },
                isActive = { context.isActive && connectivity.isCurrent(epoch) },
                onStage = transportStageObserver(context, epoch, dc, isMedia, "tcp", tls = null))
                ?: run {
                    context.ensureActive()
                    checkEpoch(epoch)
                    record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.COOLDOWN)
                    return null
                }
            if (!context.isActive) {
                dispatchTransportCleanup { socket.close() }
                context.ensureActive()
            }
            if (!connectivity.recordIfCurrent(epoch) {
                ProxyStats.connectionsTcpFallback.incrementAndGet()
                DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "route" to "tcp",
                    "elapsedMs" to transportClockMs() - startedAt)
                record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.CONNECTED)
            }) { dispatchTransportCleanup { socket.close() }; checkEpoch(epoch) }
            UpstreamConnection(UpstreamType.TCP, tcpSocket = socket, epoch = epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (_: TransportDialNotStarted) {
            context.ensureActive()
            checkEpoch(epoch)
            record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.COOLDOWN)
            null
        } catch (e: Exception) {
            context.ensureActive()
            checkEpoch(epoch)
            record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.FAILED)
            Log.w(TAG, "TCP direct to DC $dc failed: ${cfTransportErrorForLog(e)}")
            recordTransportFailure(dc, isMedia, "tcp", startedAt, e)
            null
        }
    }

    private fun transportStageObserver(
        context: CoroutineContext,
        epoch: Long,
        dc: Int,
        media: Boolean,
        route: String,
        tls: Boolean?,
    ): TransportStageObserver = { stage, elapsedMs, error ->
        if (context.isActive && error !is CancellationException) {
            connectivity.recordIfCurrent(epoch) {
                if (!context.isActive) return@recordIfCurrent
                DiagnosticLogger.debug("upstream_stage", "dc" to dc, "media" to media, "route" to route,
                    "tls" to tls, "stage" to stage, "elapsedMs" to elapsedMs,
                    "outcome" to if (error == null) "ok" else "failed",
                    "error" to error?.let(::cfTransportErrorForLog))
            }
        }
    }

    private fun recordTransportFailure(
        dc: Int, media: Boolean, route: String, startedAt: Long, error: Exception, tls: Boolean? = null,
    ) {
        // The status/exception type is sufficient; messages may contain hostnames or private paths.
        DiagnosticLogger.warning("upstream_attempt_failed", "dc" to dc, "media" to media, "route" to route,
            "tls" to tls, "elapsedMs" to transportClockMs() - startedAt, "timeout" to isTransportTimeout(error),
            "status" to (error as? WsHandshakeError)?.statusCode, "error" to cfTransportErrorForLog(error))
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
        @Suppress("UNUSED_PARAMETER") relayInit: ByteArray, // Setup already sent it; retain the server-facing signature.
        bufferSize: Int,
        protoTag: Int,
        dc: Int,
        isMedia: Boolean,
        adaptiveRoutingEnabled: Boolean,
        webSocketPingIntervalSeconds: Int,
        closeClient: () -> Unit,
    ) = coroutineScope {
        val epoch = upstream.epoch
        checkEpoch(epoch)
        if (upstream.h2 != null) {
            upstream.h2.bridge(clientInput, clientOutput, cryptoCtx, protoTag, closeClient)
            return@coroutineScope
        }
        val route = when (upstream.type) {
            UpstreamType.WEBSOCKET -> TransportRoute.WEBSOCKET
            UpstreamType.CFPROXY -> TransportRoute.CLOUDFLARE
            UpstreamType.TCP -> TransportRoute.TCP
        }
        var responded = false
        var lastResponseAt = 0L
        fun markResponse(bytes: Int) {
            val now = System.nanoTime() / 1_000_000
            // Refresh live traffic evidence without publishing a UI update for every packet.
            if (bytes > 0 && (!responded || now - lastResponseAt >= 30_000L)) {
                responded = true
                lastResponseAt = now
                record(epoch, dc, isMedia, route, RoutePhase.RESPONDING)
            }
        }
        // Per-session traffic statistics.
        val startTime = System.currentTimeMillis()
        val mediaTag = if (isMedia) " media" else ""
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val pendingLock = Any()
        var pendingSince = 0L
        var pendingBytes = 0L
        var pendingDownBytes = 0L
        val stalled = AtomicBoolean(false)

        fun markDirectRequest(bytes: Int) {
            synchronized(pendingLock) {
                if (pendingSince == 0L) pendingSince = System.currentTimeMillis()
                pendingBytes += bytes
            }
        }

        fun markDirectResponse(bytes: Int) {
            val meaningful = synchronized(pendingLock) {
                if (pendingSince > 0L) pendingDownBytes += bytes
                if (pendingDownBytes >= DirectWsCircuitBreaker.MIN_MEANINGFUL_DOWN_BYTES) {
                    pendingSince = 0L
                    pendingBytes = 0L
                    pendingDownBytes = 0L
                    true
                } else {
                    false
                }
            }
            if (meaningful) connectivity.recordIfCurrent(epoch) {
                directWsCircuitBreaker.recordResponsive(dc, isMedia)
            }
        }

        // Reuse decrypted client bytes for WS packet boundaries.
        val splitter: MsgSplitter? = if (upstream.type != UpstreamType.TCP && !upstream.worker) {
            MsgSplitter(protoTag)
        } else null

        // Cache the TCP output stream for the session.
        val tcpOutput = if (upstream.type == UpstreamType.TCP) {
            upstream.tcpSocket!!.getOutputStream()
        } else null

        val pingJob = upstream.ws?.let { ws ->
            val pingIntervalMs = webSocketPingIntervalSeconds.coerceIn(30, 300) * 1_000L
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(pingIntervalMs)
                    if (System.currentTimeMillis() - lastActivityAt.get() < pingIntervalMs) continue
                    try {
                        ws.sendPing()
                    } catch (_: Exception) {
                        ws.abort()
                        break
                    }
                }
            }
        }

        val stallWatchdogJob = if (
            adaptiveRoutingEnabled && isMedia && upstream.type == UpstreamType.WEBSOCKET
        ) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1_000)
                    if (!connectivity.isCurrent(epoch) || !connectivity.state.value.internetConfirmed) continue
                    val now = System.currentTimeMillis()
                    val stalledTraffic = synchronized(pendingLock) {
                        if (DirectWsCircuitBreaker.isStalled(
                                pendingSince = pendingSince,
                                pendingBytesUp = pendingBytes,
                                pendingBytesDown = pendingDownBytes,
                                now = now,
                            )
                        ) {
                            pendingBytes to pendingDownBytes
                        } else {
                            null
                        }
                    }
                    if (stalledTraffic == null) continue

                    var result: DirectWsStallResult? = null
                    connectivity.recordIfCurrent(epoch, requireInternet = true) {
                        if (stalled.compareAndSet(false, true)) {
                            result = directWsCircuitBreaker.recordStall(dc, isMedia)
                        }
                    }
                    val recorded = result ?: continue
                    DiagnosticLogger.event(
                        "direct_ws_stalled",
                        "dc" to dc,
                        "media" to isMedia,
                        "pendingBytesUp" to stalledTraffic.first,
                        "pendingBytesDown" to stalledTraffic.second,
                        "strikes" to recorded.strikes,
                        "overrideActivated" to recorded.activated,
                        "blockedForMs" to recorded.blockedForMs,
                    )
                    Log.w(TAG, "DC$dc$mediaTag direct WS stalled; closing session (strike ${recorded.strikes})")
                    upstream.ws?.abort()
                    break
                }
            }
        } else null

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
                    connectivity.recordIfCurrent(epoch) { ProxyStats.bytesUp.addAndGet(read.toLong()) }
                    lastActivityAt.set(System.currentTimeMillis())
                    upBytes += read
                    upPackets++

                    // Decrypt from client, re-encrypt for telegram
                    val decrypted = cryptoCtx.clientDecryptor.update(data)
                    val reencrypted = cryptoCtx.telegramEncryptor.update(decrypted)

                    when (upstream.type) {
                        UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                            if (splitter != null) {
                                val parts = splitter.split(reencrypted, decrypted)
                                if (parts.isEmpty()) continue
                                if (upstream.type == UpstreamType.WEBSOCKET) markDirectRequest(read)
                                if (parts.size > 1) {
                                    upstream.ws!!.sendBatch(parts)
                                } else {
                                    upstream.ws!!.sendBinary(parts[0])
                                }
                            } else {
                                if (upstream.type == UpstreamType.WEBSOCKET) markDirectRequest(read)
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
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Client->upstream ended: ${cfTransportErrorForLog(e)}")
            }
        }

        val upstreamToClient = launch(Dispatchers.IO) {
            try {
                when (upstream.type) {
                    UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                        while (isActive) {
                            val data = upstream.ws!!.readBinary() ?: break
                            markResponse(data.size)
                            connectivity.recordIfCurrent(epoch) { ProxyStats.bytesDown.addAndGet(data.size.toLong()) }
                            lastActivityAt.set(System.currentTimeMillis())
                            downBytes += data.size
                            downPackets++
                            if (upstream.type == UpstreamType.WEBSOCKET) markDirectResponse(data.size)

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
                            markResponse(read)
                            connectivity.recordIfCurrent(epoch) { ProxyStats.bytesDown.addAndGet(read.toLong()) }
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
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Upstream->client ended: ${cfTransportErrorForLog(e)}")
            }
        }

        // Wait for either direction to complete, then cancel the other
        try {
            awaitFirstCompletion(clientToUpstream, upstreamToClient)
        } finally {
            // Coroutine cancellation does not interrupt blocking socket reads. Close the
            // transport first so both bridge directions can actually finish.
            closeBridgeEndpoints(closeClient, upstream::abort)
            clientToUpstream.cancel()
            upstreamToClient.cancel()
            pingJob?.cancel()
            stallWatchdogJob?.cancel()

            // Log a compact per-session summary.
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            Log.i(TAG, "Session: ${upstream.type} %.1fs up=%s(%d) down=%s(%d)".format(
                elapsed,
                MtProtoConstants.humanBytes(upBytes), upPackets,
                MtProtoConstants.humanBytes(downBytes), downPackets,
            ))
            DiagnosticLogger.event(
                "session_completed",
                "dc" to dc,
                "media" to isMedia,
                "route" to upstream.type,
                "durationMs" to System.currentTimeMillis() - startTime,
                "bytesUp" to upBytes,
                "bytesDown" to downBytes,
                "packetsUp" to upPackets,
                "packetsDown" to downPackets,
                "stalled" to stalled.get(),
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

// Exception messages can contain private endpoint paths that hostname redaction cannot hide.
internal fun cfTransportErrorForLog(error: Exception): String {
    val type = DiagnosticRedactor.field("message", error.javaClass.simpleName)
    return if (error is WsHandshakeError) "$type (HTTP ${error.statusCode})" else type
}

internal fun closeBridgeEndpoints(closeClient: () -> Unit, closeUpstream: () -> Unit) {
    try { closeClient() } catch (_: Exception) {}
    try { closeUpstream() } catch (_: Exception) {}
}

class ProxyException(message: String, cause: Throwable? = null) : Exception(message, cause)
