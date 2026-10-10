package dev.minios.tgwsproxy.proxy

import android.os.SystemClock
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import java.util.ArrayDeque
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Idle direct WebSockets, with one rotation/refill worker per DC/media route. */
class WsPool internal constructor(
    private val poolSize: Int,
    private val maxAgeMs: Long,
    private val bufferSize: Int,
    private val clock: () -> Long,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val connector: ((String, List<String>, (SSLSocket) -> Unit) -> RawWebSocket?)? = null,
) {
    constructor(
        poolSize: Int = 4,
        maxAgeMs: Long = 120_000,
        bufferSize: Int = 256 * 1024,
    ) : this(poolSize, maxAgeMs, bufferSize, SystemClock::elapsedRealtime)

    private data class PooledConnection(val ws: RawWebSocket, val createdAt: Long)
    private class Bucket(val dc: Int, val isMedia: Boolean, val targetIp: String) {
        val idle = ArrayDeque<PooledConnection>()
        val pending = mutableSetOf<SSLSocket>()
        val wake = Channel<Unit>(Channel.CONFLATED)
        var job: Job? = null
        var failures = 0
        var retryAfter = 0L
    }

    private val lock = Any()
    private val buckets = mutableMapOf<Int, Bucket>()
    private var running = false
    private var scope: CoroutineScope? = null
    private var scopeMonitor: Job? = null
    private var owner: Any? = null
    private var dcRedirects: Map<Int, String> = emptyMap()

    init {
        require(poolSize >= 0 && maxAgeMs > 0)
    }

    fun start(coroutineScope: CoroutineScope) = synchronized(lock) {
        if (!running && coroutineScope.isActive) {
            scope = coroutineScope
            running = true
            val token = Any()
            owner = token
            // Cancellation cannot wait for blocking IO children before closing their sockets.
            scopeMonitor = coroutineScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    synchronized(lock) {
                        if (owner === token) {
                            running = false
                            owner = null
                            invalidateBuckets()
                            scope = null
                        }
                    }
                }
            }
        }
    }

    fun warmUp(redirects: Map<Int, String>) = synchronized(lock) {
        if (!running) return@synchronized
        if (dcRedirects != redirects) invalidateBuckets()
        dcRedirects = redirects.toMap()
        if (poolSize <= 0) return@synchronized
        for (dc in dcRedirects.keys) {
            bucketFor(dc, false)
            bucketFor(dc, true)
        }
    }

    fun stop() = synchronized(lock) {
        running = false
        owner = null
        scopeMonitor?.cancel()
        scopeMonitor = null
        invalidateBuckets()
        scope = null
    }

    fun resetAndWarm(redirects: Map<Int, String>) = synchronized(lock) {
        if (!running) return@synchronized
        invalidateBuckets()
        warmUp(redirects)
    }

    fun updateRedirects(redirects: Map<Int, String>) = warmUp(redirects)

    fun get(dc: Int, isMedia: Boolean): RawWebSocket? = synchronized(lock) {
        val bucket = bucketFor(dc, isMedia) ?: return@synchronized null
        var selected: RawWebSocket? = null
        while (bucket.idle.isNotEmpty()) {
            val connection = bucket.idle.removeFirst()
            if (isStale(connection, clock())) {
                connection.ws.abort()
            } else {
                selected = connection.ws
                break
            }
        }
        bucket.wake.trySend(Unit)
        selected
    }

    private fun bucketFor(dc: Int, isMedia: Boolean): Bucket? {
        if (!running || poolSize <= 0 || scope?.isActive != true) return null
        val targetIp = dcRedirects[dc] ?: return null
        val key = poolKey(dc, isMedia)
        buckets[key]?.let { return it }
        val bucket = Bucket(dc, isMedia, targetIp)
        buckets[key] = bucket
        bucket.job = scope!!.launch(dispatcher, start = CoroutineStart.LAZY) { maintain(bucket) }
        bucket.job!!.start()
        return bucket
    }

    private fun owns(bucket: Bucket): Boolean = running &&
        buckets[poolKey(bucket.dc, bucket.isMedia)] === bucket

    private fun isStale(connection: PooledConnection, now: Long): Boolean =
        now - connection.createdAt >= maxAgeMs || connection.ws.isClosed() ||
            connection.ws.isOutputShutdown()

    private suspend fun maintain(bucket: Bucket) {
        try {
            while (currentCoroutineContext().isActive) {
                val needed = synchronized(lock) {
                    if (!owns(bucket)) return
                    val now = clock()
                    val iterator = bucket.idle.iterator()
                    while (iterator.hasNext()) {
                        val connection = iterator.next()
                        if (isStale(connection, now)) {
                            iterator.remove()
                            connection.ws.abort()
                        }
                    }
                    if (now >= bucket.retryAfter) poolSize - bucket.idle.size else 0
                }
                if (needed > 0) {
                    refill(bucket, needed)
                    continue
                }
                val waitMs = synchronized(lock) {
                    if (!owns(bucket)) return
                    val now = clock()
                    val expiry = bucket.idle.minOfOrNull { it.createdAt + maxAgeMs } ?: Long.MAX_VALUE
                    val retry = if (bucket.idle.size < poolSize) bucket.retryAfter else Long.MAX_VALUE
                    (minOf(expiry, retry) - now).coerceAtLeast(1L)
                }
                withTimeoutOrNull(waitMs) { bucket.wake.receive() }
            }
        } finally {
            synchronized(lock) {
                if (buckets[poolKey(bucket.dc, bucket.isMedia)] === bucket) {
                    buckets.remove(poolKey(bucket.dc, bucket.isMedia))
                    closeBucket(bucket)
                }
            }
        }
    }

    private suspend fun refill(bucket: Bucket, needed: Int) {
        val startedAt = clock()
        var connected = 0
        val domains = MtProtoConstants.wsDomainsForDc(bucket.dc, bucket.isMedia)
        coroutineScope {
            repeat(needed) {
                launch(dispatcher) {
                    var ws: RawWebSocket? = null
                    var socket: SSLSocket? = null
                    var adopted = false
                    try {
                        ensureActive()
                        val onSocketCreated: (SSLSocket) -> Unit = { created ->
                            synchronized(lock) {
                                socket?.let { bucket.pending.remove(it) }
                                socket = created
                                if (!owns(bucket) || !isActive) {
                                    created.close()
                                    throw CancellationException("WS pool reset")
                                }
                                bucket.pending.add(created)
                            }
                        }
                        ws = if (connector != null) {
                            connector.invoke(bucket.targetIp, domains, onSocketCreated)
                        } else {
                            connectOne(bucket.targetIp, domains, onSocketCreated)
                        }
                        ensureActive()
                        val ready = ws
                        if (ready != null) synchronized(lock) {
                            if (owns(bucket)) {
                                bucket.idle.addLast(PooledConnection(ready, clock()))
                                socket?.let { bucket.pending.remove(it) }
                                connected++
                                adopted = true
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A failed dial contributes to the batch backoff below.
                    } finally {
                        synchronized(lock) { socket?.let { bucket.pending.remove(it) } }
                        if (!adopted) {
                            ws?.abort()
                            try { socket?.close() } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
        synchronized(lock) {
            if (!owns(bucket)) return
            if (connected > 0) {
                bucket.failures = 0
                bucket.retryAfter = 0
            } else {
                bucket.failures = (bucket.failures + 1).coerceAtMost(13)
                bucket.retryAfter = clock() + refillDelayMs(bucket.failures)
            }
            DiagnosticLogger.event(
                "pool_refill_completed",
                "dc" to bucket.dc,
                "media" to bucket.isMedia,
                "requested" to needed,
                "ready" to bucket.idle.size,
                "elapsedMs" to clock() - startedAt,
                "retryInMs" to (bucket.retryAfter - clock()).coerceAtLeast(0),
            )
        }
    }

    private fun connectOne(targetIp: String, domains: List<String>, onSocketCreated: (SSLSocket) -> Unit): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(
                    connectHost = targetIp,
                    domain = domain,
                    connectTimeoutMs = 8000,
                    bufferSize = bufferSize,
                    onSocketCreated = onSocketCreated,
                )
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun invalidateBuckets() {
        val obsolete = buckets.values.toList()
        buckets.clear()
        obsolete.forEach {
            it.job?.cancel()
            closeBucket(it)
        }
    }

    private fun closeBucket(bucket: Bucket) {
        bucket.wake.close()
        bucket.idle.forEach { it.ws.abort() }
        bucket.idle.clear()
        bucket.pending.forEach { try { it.close() } catch (_: Exception) {} }
        bucket.pending.clear()
    }

    private fun poolKey(dc: Int, isMedia: Boolean): Int = dc * 10 + if (isMedia) 1 else 0

    internal companion object {
        fun refillDelayMs(failures: Int): Long =
            (1_000L shl (failures - 1).coerceIn(0, 12)).coerceAtMost(3_600_000L)
    }
}
