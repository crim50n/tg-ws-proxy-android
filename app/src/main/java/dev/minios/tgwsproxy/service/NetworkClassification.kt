package dev.minios.tgwsproxy.service

import dev.minios.tgwsproxy.proxy.NetworkStatus
import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot

internal data class NetworkDecision(val runtimeId: Long, val epoch: Long, val networkId: Long?) {
    fun appliesTo(currentRuntimeId: Long, snapshot: ConnectivitySnapshot): Boolean =
        runtimeId == currentRuntimeId && epoch == snapshot.epoch && networkId == snapshot.networkId &&
            snapshot.allowsTraffic && snapshot.internetConfirmed

    fun canCarryTo(id: Long?, status: NetworkStatus): Boolean =
        networkId != null && networkId == id && status in setOf(NetworkStatus.ONLINE, NetworkStatus.UNVERIFIED)
}

internal class NetworkAccessState {
    var networkId: Long? = null
        private set
    var blocked: Boolean = false
        private set

    fun selectNetwork(id: Long?) {
        if (networkId != id) {
            networkId = id
            blocked = false
        }
    }

    fun setBlocked(id: Long, value: Boolean) {
        if (id == networkId) blocked = value
    }
}

internal fun classifyNetwork(
    hasNetwork: Boolean,
    validated: Boolean,
    captivePortal: Boolean,
    blocked: Boolean,
): NetworkStatus = when {
    !hasNetwork -> NetworkStatus.OFFLINE
    blocked -> NetworkStatus.BLOCKED
    captivePortal -> NetworkStatus.CAPTIVE_PORTAL
    validated -> NetworkStatus.ONLINE
    else -> NetworkStatus.UNVERIFIED
}
