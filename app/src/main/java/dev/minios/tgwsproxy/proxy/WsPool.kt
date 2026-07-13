package dev.minios.tgwsproxy.proxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*

private const val TAG = "WsPool"

/**
 * WebSocket connection pool for pre-established connections to Telegram DCs.
 * Matches the Python _WsPool implementation including:
 * - Parallel connection establishment during refill
 * - Concurrent refill guard (only one refill per DC key at a time)
 * - Proper cleanup of expired connections (async close, not just remove)
 * - Dynamic DC redirects (read from config at refill time, not fixed at construction)
 * - is_closing() check on pooled connections (matching Python transport.is_closing())
 */
class WsPool(
    private val poolSize: Int = 4,
    private val maxAgeMs: Long = 120_000,
    private val bufferSize: Int = 256 * 1024,
) {
    private data class PooledConnection(
        val ws: RawWebSocket,
        val createdAt: Long = System.currentTimeMillis(),
    ) {
        fun isExpired(maxAge: Long): Boolean = System.currentTimeMillis() - createdAt > maxAge
    }

    private data class RefillToken(val generation: Long, val poolKey: Int)

    private val pools = ConcurrentHashMap<Int, ConcurrentLinkedQueue<PooledConnection>>()
    private val running = AtomicBoolean(false)
    private val refilling = ConcurrentHashMap.newKeySet<RefillToken>()
    private val generation = AtomicLong(0)
    private val lifecycleLock = Any()
    private var scope: CoroutineScope? = null

    // Redirects are read at refill time so network recovery can reuse current config.
    @Volatile
    private var dcRedirects: Map<Int, String> = emptyMap()

    fun start(coroutineScope: CoroutineScope) {
        if (running.getAndSet(true)) return
        scope = coroutineScope
    }

    /**
     * Warm up the pool for all configured DCs.
     * Routes through triggerRefill() instead of launching fillPool() directly,
     * so the concurrent refill guard is respected (matching Python warmup -> _schedule_refill).
     * and takes the current redirects as a parameter.
     */
    fun warmUp(redirects: Map<Int, String>) {
        dcRedirects = redirects
        for ((dc, _) in redirects) {
            for (isMedia in listOf(false, true)) {
                val key = poolKey(dc, isMedia)
                triggerRefill(key, dc, isMedia)
            }
        }
        Log.i(TAG, "WS pool warmup started for ${redirects.size} DC(s)")
    }

    fun stop() {
        synchronized(lifecycleLock) {
            running.set(false)
            generation.incrementAndGet()
            closePooledConnections()
            refilling.clear()
        }
    }

    fun resetAndWarm(redirects: Map<Int, String>) {
        synchronized(lifecycleLock) {
            if (!running.get()) return
            generation.incrementAndGet()
            closePooledConnections()
            refilling.clear()
        }
        warmUp(redirects)
    }

    /**
     * Update DC redirects at runtime (called when config changes).
     */
    fun updateRedirects(redirects: Map<Int, String>) {
        dcRedirects = redirects
    }

    /**
     * Get a connection from the pool for the given DC.
     * Returns null if no valid pooled connection is available.
     *
     * Also rejects output-shutdown sockets and closes expired connections asynchronously.
     */
    fun get(dc: Int, isMedia: Boolean): RawWebSocket? {
        val key = poolKey(dc, isMedia)
        val queue = pools.getOrPut(key) { ConcurrentLinkedQueue() }

        while (true) {
            val conn = queue.poll() ?: break
            // Reject both fully closed and output-shutdown sockets.
            if (!conn.isExpired(maxAgeMs) && !conn.ws.isClosed() && !conn.ws.isOutputShutdown()) {
                // Trigger refill in background
                triggerRefill(key, dc, isMedia)
                return conn.ws
            }
            // Close expired or dead connections without blocking checkout.
            val ws = conn.ws
            scope?.launch(Dispatchers.IO) {
                try { ws.close() } catch (_: Exception) {}
            }
        }

        // Trigger refill
        triggerRefill(key, dc, isMedia)
        return null
    }

    private fun triggerRefill(key: Int, dc: Int, isMedia: Boolean) {
        // Guard: only one refill per key at a time (matching Python _refilling set)
        val refillGeneration = generation.get()
        val token = RefillToken(refillGeneration, key)
        if (!refilling.add(token)) return
        val s = scope ?: run {
            refilling.remove(token)
            return
        }
        s.launch(Dispatchers.IO) {
            try {
                fillPool(key, dc, isMedia, refillGeneration)
            } finally {
                refilling.remove(token)
            }
        }
    }

    private suspend fun fillPool(key: Int, dc: Int, isMedia: Boolean, refillGeneration: Long) {
        if (!running.get() || generation.get() != refillGeneration) return
        // Read the target dynamically from the current redirect map.
        val targetIp = dcRedirects[dc] ?: return
        val queue = pools.getOrPut(key) { ConcurrentLinkedQueue() }

        // Remove and close expired connections asynchronously
        val expired = mutableListOf<PooledConnection>()
        queue.removeAll { conn ->
            val dead = conn.isExpired(maxAgeMs) || conn.ws.isClosed() || conn.ws.isOutputShutdown()
            if (dead) expired.add(conn)
            dead
        }
        // Close expired connections asynchronously.
        for (conn in expired) {
            scope?.launch(Dispatchers.IO) {
                try { conn.ws.close() } catch (_: Exception) {}
            }
        }

        val needed = poolSize - queue.size
        if (needed <= 0) return

        val domains = MtProtoConstants.wsDomainsForDc(dc, isMedia)

        // Establish connections in parallel (matching Python's asyncio.create_task approach)
        coroutineScope {
            val deferreds = (0 until needed).map {
                async(Dispatchers.IO) {
                    if (!running.get()) return@async null
                    connectOne(targetIp, domains)
                }
            }
            for (d in deferreds) {
                try {
                    val ws = d.await()
                    if (ws != null) {
                        synchronized(lifecycleLock) {
                            if (running.get() && generation.get() == refillGeneration) {
                                queue.offer(PooledConnection(ws))
                            } else {
                                try { ws.close() } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        if (TgWsProxyServer.globalVerbose) Log.d(TAG, "WS pool refilled DC$dc${if (isMedia) "m" else ""}: ${queue.size} ready")
    }

    /**
     * Try connecting to one WS endpoint, rotating domains on redirect.
     * Matches Python _WsPool._connect_one().
     */
    private fun connectOne(targetIp: String, domains: List<String>): RawWebSocket? {
        for (domain in domains) {
            try {
                return RawWebSocket.connect(
                    connectHost = targetIp,
                    domain = domain,
                    connectTimeoutMs = 8000,
                    bufferSize = bufferSize,
                )
            } catch (e: WsHandshakeError) {
                if (e.isRedirect) continue
                return null
            } catch (_: Exception) {
                return null
            }
        }
        return null
    }

    private fun poolKey(dc: Int, isMedia: Boolean): Int {
        return dc * 10 + (if (isMedia) 1 else 0)
    }

    private fun closePooledConnections() {
        pools.values.forEach { queue ->
            while (true) {
                val conn = queue.poll() ?: break
                try { conn.ws.close() } catch (_: Exception) {}
            }
        }
        pools.clear()
    }
}
