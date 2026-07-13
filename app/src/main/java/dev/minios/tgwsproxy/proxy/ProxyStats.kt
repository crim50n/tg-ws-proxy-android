package dev.minios.tgwsproxy.proxy

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Connection statistics tracking singleton.
 * Matches the Python _Stats implementation.
 *
 * Stats accumulate within the app process and are not reset on proxy restart.
 * (matching Python behavior where stats persist until the process exits).
 */
object ProxyStats {
    val connectionsTotal = AtomicInteger(0)
    val connectionsActive = AtomicInteger(0)
    val connectionsWs = AtomicInteger(0)
    val connectionsTcpFallback = AtomicInteger(0)
    val connectionsCfProxy = AtomicInteger(0)
    val connectionsBad = AtomicInteger(0)
    val wsErrors = AtomicInteger(0)
    val bytesUp = AtomicLong(0)
    val bytesDown = AtomicLong(0)
    val poolHits = AtomicInteger(0)
    val poolMisses = AtomicInteger(0)

    /** Timestamp (ms) when proxy was last started. 0 = not started. */
    @Volatile
    var startedAtMs: Long = 0L

    /**
     * Reset only the active connections counter (called on restart).
     * Other counters accumulate across restarts within the app session (#16).
     */
    fun resetActive() {
        connectionsActive.set(0)
        startedAtMs = System.currentTimeMillis()
    }

    /**
     * Full reset — only called when the app is first launched or explicitly requested.
     */
    fun resetAll() {
        connectionsTotal.set(0)
        connectionsActive.set(0)
        connectionsWs.set(0)
        connectionsTcpFallback.set(0)
        connectionsCfProxy.set(0)
        connectionsBad.set(0)
        wsErrors.set(0)
        bytesUp.set(0)
        bytesDown.set(0)
        poolHits.set(0)
        poolMisses.set(0)
        startedAtMs = 0L
    }

    fun snapshot(): StatsSnapshot {
        val started = startedAtMs
        val uptimeSec = if (started > 0) (System.currentTimeMillis() - started) / 1000 else 0L
        return StatsSnapshot(
            connectionsTotal = connectionsTotal.get(),
            connectionsActive = connectionsActive.get(),
            connectionsWs = connectionsWs.get(),
            connectionsTcpFallback = connectionsTcpFallback.get(),
            connectionsCfProxy = connectionsCfProxy.get(),
            connectionsBad = connectionsBad.get(),
            wsErrors = wsErrors.get(),
            bytesUp = bytesUp.get(),
            bytesDown = bytesDown.get(),
            poolHits = poolHits.get(),
            poolMisses = poolMisses.get(),
            uptimeSeconds = uptimeSec,
        )
    }

    /**
     * Pool format is hits/total where total = hits+misses,
     * or "n/a" when no pool lookups occurred.
     */
    fun formatStats(): String {
        val s = snapshot()
        val poolTotal = s.poolHits + s.poolMisses
        val poolStr = if (poolTotal > 0) "${s.poolHits}/$poolTotal" else "n/a"
        return "total=${s.connectionsTotal} active=${s.connectionsActive} " +
                "ws=${s.connectionsWs} tcp_fb=${s.connectionsTcpFallback} " +
                "cf=${s.connectionsCfProxy} bad=${s.connectionsBad} " +
                "err=${s.wsErrors} pool=$poolStr " +
                "up=${MtProtoConstants.humanBytes(s.bytesUp)} " +
                "down=${MtProtoConstants.humanBytes(s.bytesDown)}"
    }
}

data class StatsSnapshot(
    val connectionsTotal: Int = 0,
    val connectionsActive: Int = 0,
    val connectionsWs: Int = 0,
    val connectionsTcpFallback: Int = 0,
    val connectionsCfProxy: Int = 0,
    val connectionsBad: Int = 0,
    val wsErrors: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val poolHits: Int = 0,
    val poolMisses: Int = 0,
    val uptimeSeconds: Long = 0,
)
