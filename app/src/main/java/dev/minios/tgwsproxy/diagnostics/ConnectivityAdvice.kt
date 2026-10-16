package dev.minios.tgwsproxy.diagnostics

import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.TransportRoute

fun analyzeConnectivity(
    snapshot: ConnectivitySnapshot,
    configuredCfEnabled: Boolean,
    configuredCfFirst: Boolean,
): ConnectionAdvice? {
    if (!snapshot.allowsTraffic || !snapshot.internetConfirmed) return null
    // Media-only failures retain their existing per-DC circuit-breaker behavior.
    val regular = snapshot.routes.filterKeys { !it.media }
    val ws = regular.filterKeys { it.route == TransportRoute.WEBSOCKET }
    val cf = regular.filterKeys { it.route == TransportRoute.CLOUDFLARE }
    val tcp = regular.filterKeys { it.route == TransportRoute.TCP }
    val wsFailures = ws.values.sumOf { it.failures }
    val cfFailures = cf.values.sumOf { it.failures }
    val wsSuccesses = ws.values.sumOf { maxOf(it.connections, if (it.responses > 0) 1 else 0) }
    val cfSuccesses = cf.values.sumOf { maxOf(it.connections, if (it.responses > 0) 1 else 0) }
    val wsFailedDcs = ws.filterValues { it.failures > 0 }.keys.map { it.dc }.distinct().size
    val cfFailedDcs = cf.filterValues { it.failures > 0 }.keys.map { it.dc }.distinct().size
    if (!configuredCfEnabled) {
        val direct = ws + tcp
        return ConnectionAdvice.ENABLE_CLOUDFLARE.takeIf {
            direct.values.sumOf { it.failures } >= 6 &&
                direct.filterValues { it.failures > 0 }.keys.map { it.dc }.distinct().size >= 2 &&
                wsSuccesses == 0 && cfSuccesses >= 2
        }
    }
    return if (!configuredCfFirst) {
        ConnectionAdvice.USE_CLOUDFLARE_FIRST.takeIf {
            wsFailures >= 3 && wsFailedDcs >= 2 && wsSuccesses == 0 &&
                cfSuccesses >= 3 && cfFailures * 4 <= cfSuccesses
        }
    } else {
        ConnectionAdvice.USE_DIRECT_FIRST.takeIf {
            cfFailures >= 3 && cfFailedDcs >= 2 && cfSuccesses == 0 &&
                wsSuccesses >= 3 && wsFailures * 4 <= wsSuccesses
        }
    }
}
