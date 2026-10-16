package dev.minios.tgwsproxy.ui

import dev.minios.tgwsproxy.proxy.*
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPresentationTest {
    private val ws = RouteKey(2, false, TransportRoute.WEBSOCKET)
    private val cf = ws.copy(route = TransportRoute.CLOUDFLARE)
    private val responding = RouteObservation(RoutePhase.RESPONDING, responses = 1)

    @Test
    fun networkProblemsTakePrecedenceOverOldTraffic() {
        for ((network, summary) in mapOf(
            NetworkStatus.OFFLINE to ConnectionSummary.OFFLINE,
            NetworkStatus.CHECKING to ConnectionSummary.CHECKING,
            NetworkStatus.CAPTIVE_PORTAL to ConnectionSummary.CAPTIVE_PORTAL,
            NetworkStatus.BLOCKED to ConnectionSummary.BLOCKED,
        )) assertEquals(summary, presentConnectivity(ConnectivitySnapshot(network = network, routes = mapOf(cf to responding))))
    }

    @Test
    fun workingFallbackDoesNotShowDirectRouteFailuresAsConnectionFailure() {
        assertEquals(ConnectionSummary.WORKING, presentConnectivity(ConnectivitySnapshot(
            network = NetworkStatus.ONLINE,
            routes = mapOf(ws to RouteObservation(RoutePhase.COOLDOWN, failures = 3), cf to responding),
        )))
    }

    @Test
    fun responseDataCanConfirmUnverifiedNetworkButHandshakeCannot() {
        for (network in listOf(NetworkStatus.UNKNOWN, NetworkStatus.UNVERIFIED)) {
            assertEquals(ConnectionSummary.UNCERTAIN, presentConnectivity(ConnectivitySnapshot(
                network = network, routes = mapOf(ws to RouteObservation(RoutePhase.CONNECTED, connections = 1)),
            )))
            assertEquals(ConnectionSummary.WORKING, presentConnectivity(ConnectivitySnapshot(
                network = network, routes = mapOf(ws to responding),
            )))
        }
    }

    @Test
    fun handshakeIsNotPresentedAsTelegramWorking() {
        assertEquals(ConnectionSummary.WAITING_FOR_DATA, presentConnectivity(ConnectivitySnapshot(
            network = NetworkStatus.ONLINE, routes = mapOf(ws to RouteObservation(RoutePhase.CONNECTED, connections = 1)),
        )))
    }

    @Test
    fun exhaustedRoutesAreNotPresentedAsInternetOutage() {
        val snapshot = ConnectivitySnapshot(network = NetworkStatus.ONLINE, exhausted = setOf(RouteTarget(4, true)))
        assertEquals(ConnectionSummary.UNREACHABLE, presentConnectivity(snapshot))
        assertEquals(ConnectionSummary.DEGRADED, presentConnectivity(snapshot.copy(routes = mapOf(cf to responding))))
        assertEquals(ConnectionSummary.UNCERTAIN, presentConnectivity(snapshot.copy(network = NetworkStatus.UNVERIFIED)))
    }

    @Test
    fun waitingConnectingAndRetryingHaveShortDistinctStates() {
        val snapshot = ConnectivitySnapshot(network = NetworkStatus.ONLINE)
        assertEquals(ConnectionSummary.WAITING_FOR_TELEGRAM, presentConnectivity(snapshot))
        assertEquals(ConnectionSummary.CONNECTING, presentConnectivity(snapshot.copy(routes = mapOf(ws to RouteObservation(RoutePhase.CONNECTING)))))
        assertEquals(ConnectionSummary.RETRYING, presentConnectivity(snapshot.copy(routes = mapOf(ws to RouteObservation(RoutePhase.FAILED)))))
    }

    @Test
    fun stoppedSnapshotDoesNotShowOldConnectionSuccess() {
        assertEquals(ConnectionSummary.WAITING_FOR_NETWORK_STATE, presentConnectivity(ConnectivitySnapshot(
            network = NetworkStatus.ONLINE, routes = mapOf(cf to responding), stopped = true,
        )))
    }
}
