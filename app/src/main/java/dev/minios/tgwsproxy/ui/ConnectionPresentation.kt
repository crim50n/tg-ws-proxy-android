package dev.minios.tgwsproxy.ui

import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.NetworkStatus
import dev.minios.tgwsproxy.proxy.RoutePhase

internal enum class ConnectionSummary {
    WAITING_FOR_NETWORK_STATE, OFFLINE, CHECKING, CAPTIVE_PORTAL, BLOCKED, UNCERTAIN,
    WAITING_FOR_TELEGRAM, WORKING, WAITING_FOR_DATA, CONNECTING, RETRYING, UNREACHABLE, DEGRADED,
}

internal fun presentConnectivity(snapshot: ConnectivitySnapshot): ConnectionSummary {
    val responding = snapshot.routes.values.any { it.phase == RoutePhase.RESPONDING && it.responses > 0 }
    return when {
        snapshot.stopped -> ConnectionSummary.WAITING_FOR_NETWORK_STATE
        snapshot.network == NetworkStatus.OFFLINE -> ConnectionSummary.OFFLINE
        snapshot.network == NetworkStatus.CHECKING -> ConnectionSummary.CHECKING
        snapshot.network == NetworkStatus.CAPTIVE_PORTAL -> ConnectionSummary.CAPTIVE_PORTAL
        snapshot.network == NetworkStatus.BLOCKED -> ConnectionSummary.BLOCKED
        responding && snapshot.exhausted.isNotEmpty() -> ConnectionSummary.DEGRADED
        responding -> ConnectionSummary.WORKING
        snapshot.network != NetworkStatus.ONLINE -> ConnectionSummary.UNCERTAIN
        snapshot.exhausted.isNotEmpty() -> ConnectionSummary.UNREACHABLE
        snapshot.routes.values.any { it.phase == RoutePhase.CONNECTING } -> ConnectionSummary.CONNECTING
        snapshot.routes.values.any { it.phase == RoutePhase.CONNECTED } -> ConnectionSummary.WAITING_FOR_DATA
        snapshot.routes.isNotEmpty() -> ConnectionSummary.RETRYING
        else -> ConnectionSummary.WAITING_FOR_TELEGRAM
    }
}
