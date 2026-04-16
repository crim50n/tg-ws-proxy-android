package dev.minios.tgwsproxy.proxy

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
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

    private val pools = ConcurrentHashMap<Int, ConcurrentLinkedQueue<PooledConnection>>()
    private val running = AtomicBoolean(false)
    private val refilling = ConcurrentHashMap.newKeySet<Int>() // Guard against concurrent refill
    private var scope: CoroutineScope? = null

    // #20: Dynamic DC redirects — read at warmup/refill time, not fixed at construction
    @Volatile
    private var dcRedirects: Map<Int, String> = emptyMap()

    fun start(coroutineScope: CoroutineScope) {
        if (running.getAndSet(true)) return
        scope = coroutineScope
    }

    /**
     * Warm up the pool for all configured DCs.
     * #11: Routes through triggerRefill() instead of launching fillPool() directly,
     * so the concurrent refill guard is respected (matching Python warmup -> _schedule_refill).
     * #20: Takes dcRedirects as parameter (read dynamically from config).
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
        running.set(false)
        pools.values.forEach { queue ->
            while (true) {
                val conn = queue.poll() ?: break
                try { conn.ws.close() } catch (_: Exception) {}
            }
        }
        pools.clear()
        refilling.clear()
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
     * #9: Also checks ws.isOutputShutdown() (matching Python transport.is_closing())
     * #18: Closes expired connections asynchronously (matching Python asyncio.create_task(_quiet_close))
     */
    fun get(dc: Int, isMedia: Boolean): RawWebSocket? {
        val key = poolKey(dc, isMedia)
        val queue = pools[key] ?: return null

        while (true) {
            val conn = queue.poll() ?: break
            // #9: Check isClosed() AND isOutputShutdown() (matching Python ws._closed or transport.is_closing())
            if (!conn.isExpired(maxAgeMs) && !conn.ws.isClosed() && !conn.ws.isOutputShutdown()) {
                // Trigger refill in background
                triggerRefill(key, dc, isMedia)
                return conn.ws
            }
            // #18: Close expired/dead connections asynchronously (matching Python asyncio.create_task(_quiet_close))
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
        if (!refilling.add(key)) return
        val s = scope ?: run {
            refilling.remove(key)
            return
        }
        s.launch(Dispatchers.IO) {
            try {
                fillPool(key, dc, isMedia)
            } finally {
                refilling.remove(key)
            }
        }
    }

    private suspend fun fillPool(key: Int, dc: Int, isMedia: Boolean) {
        if (!running.get()) return
        // #20: Read target IP dynamically from current dcRedirects (not from constructor)
        val targetIp = dcRedirects[dc] ?: return
        val queue = pools.getOrPut(key) { ConcurrentLinkedQueue() }

        // Remove and close expired connections asynchronously
        val expired = mutableListOf<PooledConnection>()
        queue.removeAll { conn ->
            // #9: Also check isOutputShutdown
            val dead = conn.isExpired(maxAgeMs) || conn.ws.isClosed() || conn.ws.isOutputShutdown()
            if (dead) expired.add(conn)
            dead
        }
        // #18: Close expired connections asynchronously
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
                        if (running.get()) {
                            queue.offer(PooledConnection(ws))
                        } else {
                            try { ws.close() } catch (_: Exception) {}
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
}
