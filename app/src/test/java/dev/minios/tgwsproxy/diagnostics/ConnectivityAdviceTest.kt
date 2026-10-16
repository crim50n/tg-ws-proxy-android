package dev.minios.tgwsproxy.diagnostics

import dev.minios.tgwsproxy.proxy.ConnectivitySnapshot
import dev.minios.tgwsproxy.proxy.ConnectivityTracker
import dev.minios.tgwsproxy.proxy.NetworkStatus
import dev.minios.tgwsproxy.proxy.RouteKey
import dev.minios.tgwsproxy.proxy.RouteObservation
import dev.minios.tgwsproxy.proxy.RoutePhase
import dev.minios.tgwsproxy.proxy.TransportRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectivityAdviceTest {
    @Test
    fun `working WS data prevents fallback even after original handshake evidence expired`() {
        val candidate = cloudflareCandidate()
        val key = RouteKey(2, false, TransportRoute.WEBSOCKET)
        val responseOnly = candidate.copy(routes = candidate.routes + (
            key to candidate.routes.getValue(key).copy(phase = RoutePhase.RESPONDING, connections = 0, responses = 1)
        ))
        assertNull(analyzeConnectivity(responseOnly, true, false))
    }

    @Test
    fun `Cloudflare first requires three failures across two DCs and three successes`() {
        val eligible = cloudflareCandidate()
        assertEquals(ConnectionAdvice.USE_CLOUDFLARE_FIRST, analyzeConnectivity(eligible, true, false))
        assertNull(analyzeConnectivity(cloudflareCandidate(failures = 2), true, false))
        assertNull(analyzeConnectivity(cloudflareCandidate(failedDcs = 1), true, false))
        assertNull(analyzeConnectivity(cloudflareCandidate(successes = 2), true, false))
        assertNull(analyzeConnectivity(cloudflareCandidate(primarySuccesses = 1), true, false))
        assertNull(analyzeConnectivity(eligible, true, true))
    }

    @Test
    fun `direct first uses the same thresholds with the routes reversed`() {
        val eligible = cloudflareCandidate().swapWebSocketAndCloudflare()
        assertEquals(ConnectionAdvice.USE_DIRECT_FIRST, analyzeConnectivity(eligible, true, true))
        for (ineligible in listOf(
            cloudflareCandidate(failures = 2),
            cloudflareCandidate(failedDcs = 1),
            cloudflareCandidate(successes = 2),
            cloudflareCandidate(primarySuccesses = 1),
        )) {
            assertNull(analyzeConnectivity(ineligible.swapWebSocketAndCloudflare(), true, true))
        }
        assertNull(analyzeConnectivity(eligible, true, false))
    }

    @Test
    fun `alternate route failure ratio must not exceed one failure per four successes`() {
        for (cfFirst in listOf(false, true)) {
            val atBoundary = cloudflareCandidate(successes = 4, alternateFailures = 1)
            val belowBoundary = cloudflareCandidate(successes = 3, alternateFailures = 1)
            val tooManyFailures = cloudflareCandidate(successes = 4, alternateFailures = 2)
            fun orient(snapshot: ConnectivitySnapshot) =
                if (cfFirst) snapshot.swapWebSocketAndCloudflare() else snapshot

            assertEquals(
                if (cfFirst) ConnectionAdvice.USE_DIRECT_FIRST else ConnectionAdvice.USE_CLOUDFLARE_FIRST,
                analyzeConnectivity(orient(atBoundary), true, cfFirst),
            )
            assertNull(analyzeConnectivity(orient(belowBoundary), true, cfFirst))
            assertNull(analyzeConnectivity(orient(tooManyFailures), true, cfFirst))
        }
    }

    @Test
    fun `enabling Cloudflare needs six direct failures across two DCs and two CF successes`() {
        val eligible = enableCandidate()
        for (cfFirst in listOf(false, true)) {
            assertEquals(ConnectionAdvice.ENABLE_CLOUDFLARE, analyzeConnectivity(eligible, false, cfFirst))
            assertNull(analyzeConnectivity(enableCandidate(failures = 5), false, cfFirst))
            assertNull(analyzeConnectivity(enableCandidate(failedDcs = 1), false, cfFirst))
            assertNull(analyzeConnectivity(enableCandidate(successes = 1), false, cfFirst))
            val withWsSuccess = eligible.copy(routes = eligible.routes + observation(
                TransportRoute.WEBSOCKET, dc = 5, connections = 1,
            ))
            assertNull(analyzeConnectivity(withWsSuccess, false, cfFirst))
        }
    }

    @Test
    fun `TCP failures count toward enabling CF but not route order advice`() {
        val tcpOnlyFailures = enableCandidate().copy(routes = enableCandidate().routes
            .filterKeys { it.route != TransportRoute.WEBSOCKET })
        assertNull(analyzeConnectivity(tcpOnlyFailures, true, false))
        val eligible = cloudflareCandidate()
        val noisyTcp = eligible.copy(routes = eligible.routes + observation(
            TransportRoute.TCP, failures = 100, connections = 100,
        ))
        assertEquals(ConnectionAdvice.USE_CLOUDFLARE_FIRST, analyzeConnectivity(noisyTcp, true, false))
    }

    @Test
    fun `media routes cannot supply failures successes or distinct DCs for any advice`() {
        val cases = listOf(
            Triple(cloudflareCandidate(), true, false),
            Triple(cloudflareCandidate().swapWebSocketAndCloudflare(), true, true),
            Triple(enableCandidate(), false, false),
        )
        for ((eligible, enabled, first) in cases) {
            val mediaOnly = eligible.copy(routes = eligible.routes.mapKeys { (key, _) -> key.copy(media = true) })
            assertNull(analyzeConnectivity(mediaOnly, enabled, first))
            // Moving any required boundary observation to media must invalidate the recommendation.
            for ((key, observation) in eligible.routes) {
                val mixed = eligible.copy(routes = eligible.routes - key + (key.copy(media = true) to observation))
                assertNull("Media evidence counted for $key", analyzeConnectivity(mixed, enabled, first))
            }
            val mediaNoise = eligible.copy(routes = eligible.routes + mapOf(
                RouteKey(5, true, TransportRoute.WEBSOCKET) to RouteObservation(RoutePhase.FAILED, 100, 100),
                RouteKey(5, true, TransportRoute.CLOUDFLARE) to RouteObservation(RoutePhase.FAILED, 100, 100),
            ))
            assertEquals(analyzeConnectivity(eligible, enabled, first), analyzeConnectivity(mediaNoise, enabled, first))
        }
    }

    @Test
    fun `advice requires traffic permission and current internet confirmation`() {
        val cases = listOf(
            Triple(cloudflareCandidate(), true, false),
            Triple(cloudflareCandidate().swapWebSocketAndCloudflare(), true, true),
            Triple(enableCandidate(), false, false),
        )
        for ((eligible, enabled, first) in cases) {
            for (status in listOf(NetworkStatus.OFFLINE, NetworkStatus.CHECKING, NetworkStatus.CAPTIVE_PORTAL, NetworkStatus.BLOCKED)) {
                assertNull(analyzeConnectivity(eligible.copy(network = status), enabled, first))
                val withResponse = eligible.copy(network = status, routes = eligible.routes + observation(
                    TransportRoute.TCP, dc = 5, responses = 1,
                ))
                assertNull(analyzeConnectivity(withResponse, enabled, first))
            }
            assertNull(analyzeConnectivity(eligible.copy(stopped = true), enabled, first))
            for (status in listOf(NetworkStatus.UNKNOWN, NetworkStatus.UNVERIFIED)) {
                val unconfirmed = eligible.copy(network = status)
                assertNull(analyzeConnectivity(unconfirmed, enabled, first))
                val confirmed = unconfirmed.copy(routes = unconfirmed.routes + observation(
                    TransportRoute.TCP, dc = 5, responses = 1,
                ))
                assertEquals(analyzeConnectivity(eligible, enabled, first), analyzeConnectivity(confirmed, enabled, first))
            }
        }
    }

    @Test
    fun `old epoch failures cannot combine with recovered network successes to switch routes`() {
        var now = 1_000L
        val tracker = ConnectivityTracker { now }
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val oldEpoch = tracker.state.value.epoch
        val failedKeys = listOf(
            RouteKey(2, false, TransportRoute.WEBSOCKET),
            RouteKey(4, false, TransportRoute.WEBSOCKET),
            RouteKey(2, false, TransportRoute.WEBSOCKET),
        )
        for (key in failedKeys) tracker.record(oldEpoch, key, RoutePhase.FAILED)
        repeat(3) { tracker.record(oldEpoch, RouteKey(2, false, TransportRoute.CLOUDFLARE), RoutePhase.CONNECTED) }
        assertEquals(ConnectionAdvice.USE_CLOUDFLARE_FIRST, analyzeConnectivity(tracker.state.value, true, false))

        now += 10
        tracker.updateNetwork(7, NetworkStatus.OFFLINE)
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val currentEpoch = tracker.state.value.epoch
        repeat(3) { tracker.record(currentEpoch, RouteKey(2, false, TransportRoute.CLOUDFLARE), RoutePhase.CONNECTED) }
        for (key in failedKeys) tracker.record(oldEpoch, key, RoutePhase.FAILED)
        assertNull(analyzeConnectivity(tracker.state.value, true, false))

        for (key in failedKeys) tracker.record(currentEpoch, key, RoutePhase.FAILED)
        assertEquals(ConnectionAdvice.USE_CLOUDFLARE_FIRST, analyzeConnectivity(tracker.state.value, true, false))
        tracker.stop()
        assertNull(analyzeConnectivity(tracker.state.value, true, false))
    }

    @Test
    fun `expired successes and failures cannot combine with new evidence for any advice`() {
        val cases = listOf(
            Triple(cloudflareCandidate(), true, false),
            Triple(cloudflareCandidate().swapWebSocketAndCloudflare(), true, true),
            Triple(enableCandidate(), false, false),
        )
        for ((candidate, enabled, first) in cases) {
            for (oldFailures in listOf(false, true)) {
                var now = 1_000L
                val tracker = ConnectivityTracker { now }
                tracker.updateNetwork(7, NetworkStatus.ONLINE)
                val epoch = tracker.state.value.epoch
                fun recordEvidence(failures: Boolean) {
                    for ((key, observation) in candidate.routes) {
                        repeat(if (failures) observation.failures else observation.connections) {
                            tracker.record(epoch, key, if (failures) RoutePhase.FAILED else RoutePhase.CONNECTED)
                        }
                    }
                }
                recordEvidence(oldFailures)
                now += 120_000
                recordEvidence(!oldFailures)
                assertNull("Stale counters contributed: enabled=$enabled first=$first oldFailures=$oldFailures",
                    analyzeConnectivity(tracker.state.value, enabled, first))
                recordEvidence(oldFailures)
                assertEquals(analyzeConnectivity(candidate, enabled, first),
                    analyzeConnectivity(tracker.state.value, enabled, first))
                now += 120_000
                tracker.isCurrent(epoch)
                assertNull(analyzeConnectivity(tracker.state.value, enabled, first))
            }
        }
    }

    @Test
    fun `expired unverified response cannot authorize new advice evidence`() {
        var now = 1_000L
        val tracker = ConnectivityTracker { now }
        tracker.updateNetwork(7, NetworkStatus.UNVERIFIED)
        val epoch = tracker.state.value.epoch
        tracker.record(epoch, RouteKey(5, false, TransportRoute.TCP), RoutePhase.RESPONDING)
        now += 120_000
        val candidate = cloudflareCandidate()
        for ((key, observation) in candidate.routes) {
            repeat(observation.failures) { tracker.record(epoch, key, RoutePhase.FAILED) }
            repeat(observation.connections) { tracker.record(epoch, key, RoutePhase.CONNECTED) }
        }
        assertNull(analyzeConnectivity(tracker.state.value, true, false))
        tracker.record(epoch, RouteKey(5, false, TransportRoute.TCP), RoutePhase.RESPONDING)
        assertNull(analyzeConnectivity(tracker.state.value, true, false))
        for ((key, observation) in candidate.routes) {
            repeat(observation.failures) { tracker.record(epoch, key, RoutePhase.FAILED) }
        }
        assertEquals(ConnectionAdvice.USE_CLOUDFLARE_FIRST, analyzeConnectivity(tracker.state.value, true, false))
    }

    private fun cloudflareCandidate(
        failures: Int = 3,
        failedDcs: Int = 2,
        successes: Int = 3,
        primarySuccesses: Int = 0,
        alternateFailures: Int = 0,
    ): ConnectivitySnapshot {
        val routes = mutableMapOf(observation(
            TransportRoute.WEBSOCKET, failures = failures - (failedDcs - 1), connections = primarySuccesses,
        ))
        if (failedDcs == 2) routes += observation(TransportRoute.WEBSOCKET, dc = 4, failures = 1)
        routes += observation(TransportRoute.CLOUDFLARE, failures = alternateFailures, connections = successes)
        return ConnectivitySnapshot(network = NetworkStatus.ONLINE, routes = routes)
    }

    private fun enableCandidate(failures: Int = 6, failedDcs: Int = 2, successes: Int = 2) =
        ConnectivitySnapshot(network = NetworkStatus.ONLINE, routes = mapOf(
            observation(TransportRoute.WEBSOCKET, failures = failures - 3),
            observation(TransportRoute.TCP, dc = if (failedDcs == 2) 4 else 2, failures = 3),
            observation(TransportRoute.CLOUDFLARE, connections = successes),
        ))

    private fun observation(
        route: TransportRoute,
        dc: Int = 2,
        failures: Int = 0,
        connections: Int = 0,
        responses: Int = 0,
    ) = RouteKey(dc, false, route) to RouteObservation(
        phase = if (failures > 0) RoutePhase.FAILED else RoutePhase.CONNECTED,
        failures = failures,
        connections = connections,
        responses = responses,
        updatedAt = 1_000,
    )

    private fun ConnectivitySnapshot.swapWebSocketAndCloudflare() = copy(routes = routes.mapKeys { (key, _) ->
        key.copy(route = when (key.route) {
            TransportRoute.WEBSOCKET -> TransportRoute.CLOUDFLARE
            TransportRoute.CLOUDFLARE -> TransportRoute.WEBSOCKET
            TransportRoute.TCP -> TransportRoute.TCP
        })
    })
}
