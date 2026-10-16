package dev.minios.tgwsproxy.service

import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.NetworkStatus
import org.junit.Assert.*
import org.junit.Test

class NetworkDecisionTest {
    @Test
    fun queuedAdviceRequiresSameRuntimeNetworkAndEpoch() {
        val decision = NetworkDecision(11, 5, 7)
        val online = ConnectivitySnapshot(epoch = 5, networkId = 7, network = NetworkStatus.ONLINE)
        assertTrue(decision.appliesTo(11, online))
        assertFalse(decision.appliesTo(12, online))
        assertFalse(decision.appliesTo(11, online.copy(epoch = 6)))
        assertFalse(decision.appliesTo(11, online.copy(networkId = 8)))
        for (status in listOf(NetworkStatus.CHECKING, NetworkStatus.OFFLINE, NetworkStatus.BLOCKED, NetworkStatus.CAPTIVE_PORTAL)) {
            assertFalse(decision.appliesTo(11, online.copy(network = status)))
        }
        assertFalse(decision.appliesTo(11, online.copy(stopped = true)))
    }

    @Test
    fun restartCannotCarryAdviceToDifferentOrUnavailableNetwork() {
        val decision = NetworkDecision(11, 5, 7)
        assertTrue(decision.canCarryTo(7, NetworkStatus.ONLINE))
        assertTrue(decision.canCarryTo(7, NetworkStatus.UNVERIFIED))
        assertFalse(decision.canCarryTo(8, NetworkStatus.ONLINE))
        assertFalse(decision.canCarryTo(null, NetworkStatus.OFFLINE))
        for (status in listOf(NetworkStatus.CHECKING, NetworkStatus.OFFLINE, NetworkStatus.BLOCKED, NetworkStatus.CAPTIVE_PORTAL)) {
            assertFalse(decision.canCarryTo(7, status))
        }
    }

    @Test
    fun capabilitiesDoNotClearBlockedStatusAndReplacementDoes() {
        val access = NetworkAccessState()
        access.selectNetwork(7)
        access.setBlocked(7, true)
        access.selectNetwork(7)
        assertTrue(access.blocked)
        assertEquals(NetworkStatus.BLOCKED, classifyNetwork(true, true, false, access.blocked))
        access.setBlocked(8, false)
        assertTrue(access.blocked)
        access.setBlocked(7, false)
        assertEquals(NetworkStatus.ONLINE, classifyNetwork(true, true, false, access.blocked))
        access.setBlocked(7, true)
        access.selectNetwork(8)
        assertFalse(access.blocked)
    }
}
