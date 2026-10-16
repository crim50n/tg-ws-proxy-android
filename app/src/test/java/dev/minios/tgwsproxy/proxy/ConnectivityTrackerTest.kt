package dev.minios.tgwsproxy.proxy

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityTrackerTest {
    @Test
    fun `ongoing response refresh keeps unverified network useful across observation windows`() {
        var time = 0L
        val health = ConnectivityTracker { time }
        health.updateNetwork(7, NetworkStatus.UNVERIFIED)
        val epoch = health.state.value.epoch
        val route = RouteKey(2, false, TransportRoute.WEBSOCKET)
        repeat(20) {
            health.record(epoch, route, RoutePhase.RESPONDING)
            assertTrue(health.state.value.internetConfirmed)
            assertTrue(health.state.value.allowsBackgroundWork)
            time += 30_000
        }
    }

    private var now = 1_000L
    private val tracker = ConnectivityTracker { now }
    private val regular = RouteKey(2, false, TransportRoute.WEBSOCKET)

    @Test
    fun `offline checking captive portal and blocked gate traffic evidence and mutations`() {
        for (status in listOf(NetworkStatus.OFFLINE, NetworkStatus.CHECKING, NetworkStatus.CAPTIVE_PORTAL, NetworkStatus.BLOCKED)) {
            tracker.updateNetwork(7, status)
            val before = tracker.state.value
            var mutations = 0
            val socket = CountingSocket()
            val resource = CountingResource()

            assertFalse(status.name, before.allowsTraffic)
            assertFalse(status.name, before.internetConfirmed)
            assertFalse(status.name, before.allowsBackgroundWork)
            assertFalse(tracker.isCurrent(before.epoch))
            assertFalse(tracker.recordIfCurrent(before.epoch) { mutations++ })
            assertFalse(tracker.trackSocket(before.epoch, socket))
            assertFalse(tracker.trackResource(before.epoch, resource))
            for (phase in RoutePhase.entries) tracker.record(before.epoch, regular, phase)
            tracker.recordExhausted(before.epoch, RouteTarget(2, false))

            assertEquals(0, mutations)
            assertEquals(1, socket.closes)
            assertEquals(1, resource.closes)
            assertEquals(before, tracker.state.value)
        }
    }

    @Test
    fun `unknown and unverified allow foreground attempts but need a response for background work`() {
        for (status in listOf(NetworkStatus.UNKNOWN, NetworkStatus.UNVERIFIED)) {
            tracker.updateNetwork(7, status)
            val epoch = tracker.state.value.epoch
            assertTrue(tracker.state.value.allowsTraffic)
            assertTrue(tracker.isCurrent(epoch))
            assertFalse(tracker.state.value.internetConfirmed)
            assertFalse(tracker.state.value.allowsBackgroundWork)
            var mutations = 0
            assertTrue(tracker.recordIfCurrent(epoch) { mutations++ })
            assertFalse(tracker.recordIfCurrent(epoch, requireInternet = true) { mutations++ })

            tracker.record(epoch, regular, RoutePhase.CONNECTED)
            assertFalse(tracker.state.value.internetConfirmed)
            tracker.record(epoch, regular, RoutePhase.RESPONDING)

            assertTrue(tracker.state.value.internetConfirmed)
            assertTrue(tracker.state.value.allowsBackgroundWork)
            assertTrue(tracker.recordIfCurrent(epoch, requireInternet = true) { mutations++ })
            assertEquals(2, mutations)
        }
    }

    @Test
    fun `failures and exhaustion without internet do not become route failure evidence`() {
        tracker.updateNetwork(7, NetworkStatus.UNVERIFIED)
        val epoch = tracker.state.value.epoch
        repeat(4) { tracker.record(epoch, regular, RoutePhase.FAILED) }
        tracker.recordExhausted(epoch, RouteTarget(2, false))

        assertEquals(0, tracker.state.value.routes.getValue(regular).failures)
        assertTrue(tracker.state.value.exhausted.isEmpty())

        tracker.record(epoch, regular, RoutePhase.RESPONDING)
        tracker.record(epoch, regular, RoutePhase.FAILED)
        tracker.recordExhausted(epoch, RouteTarget(2, false))
        assertEquals(1, tracker.state.value.routes.getValue(regular).failures)
        assertEquals(setOf(RouteTarget(2, false)), tracker.state.value.exhausted)
    }

    @Test
    fun `network loss and recovery on same ID advance epochs and reset evidence`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val originalEpoch = tracker.state.value.epoch
        tracker.record(originalEpoch, regular, RoutePhase.RESPONDING)
        tracker.record(originalEpoch, regular, RoutePhase.FAILED)
        tracker.recordExhausted(originalEpoch, RouteTarget(2, false))

        now += 10
        assertTrue(tracker.updateNetwork(7, NetworkStatus.OFFLINE))
        val lost = tracker.state.value
        assertTrue(lost.epoch > originalEpoch)
        assertEquals(7L, lost.networkId)
        assertTrue(lost.routes.isEmpty())
        assertTrue(lost.exhausted.isEmpty())
        assertFalse(lost.internetConfirmed)

        now += 10
        assertTrue(tracker.updateNetwork(7, NetworkStatus.UNVERIFIED))
        val recovered = tracker.state.value
        assertTrue(recovered.epoch > lost.epoch)
        assertTrue(recovered.allowsTraffic)
        assertFalse(recovered.internetConfirmed)
        assertTrue(recovered.routes.isEmpty())
        assertTrue(recovered.exhausted.isEmpty())
    }

    @Test
    fun `identical network notification preserves epoch evidence and pending dial`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        val socket = CountingSocket()
        assertTrue(tracker.trackSocket(epoch, socket))
        tracker.record(epoch, regular, RoutePhase.CONNECTED)
        val before = tracker.state.value

        assertFalse(tracker.updateNetwork(7, NetworkStatus.ONLINE))
        assertEquals(before, tracker.state.value)
        assertEquals(0, socket.closes)
        tracker.stop()
        assertEquals(1, socket.closes)
    }

    @Test
    fun `old epoch results and side effects cannot contaminate new DC or media state`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val oldEpoch = tracker.state.value.epoch
        tracker.updateNetwork(8, NetworkStatus.ONLINE)
        val currentEpoch = tracker.state.value.epoch
        val media = RouteKey(4, true, TransportRoute.CLOUDFLARE)
        tracker.record(currentEpoch, media, RoutePhase.CONNECTED)
        val before = tracker.state.value
        var mutations = 0

        assertFalse(tracker.isCurrent(oldEpoch))
        assertFalse(tracker.recordIfCurrent(oldEpoch) { mutations++ })
        assertFalse(tracker.recordIfCurrent(oldEpoch, requireInternet = true) { mutations++ })
        for (key in listOf(regular, media)) {
            for (phase in RoutePhase.entries) tracker.record(oldEpoch, key, phase)
            tracker.recordExhausted(oldEpoch, RouteTarget(key.dc, key.media))
        }

        assertEquals(0, mutations)
        assertEquals(before, tracker.state.value)
    }

    @Test
    fun `route counters and exhaustion recovery are isolated by DC media and transport`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        val media = regular.copy(media = true)
        val otherDc = regular.copy(dc = 4)
        val cf = regular.copy(route = TransportRoute.CLOUDFLARE)
        for (key in listOf(regular, media, otherDc, cf)) {
            tracker.record(epoch, key, RoutePhase.FAILED)
            tracker.recordExhausted(epoch, RouteTarget(key.dc, key.media))
        }
        now += 10
        tracker.record(epoch, regular, RoutePhase.CONNECTED)
        tracker.record(epoch, regular, RoutePhase.RESPONDING)

        val snapshot = tracker.state.value
        assertEquals(4, snapshot.routes.size)
        assertEquals(1, snapshot.routes.getValue(regular).connections)
        assertEquals(1, snapshot.routes.getValue(regular).responses)
        for (key in listOf(media, otherDc, cf)) {
            assertEquals(1, snapshot.routes.getValue(key).failures)
            assertEquals(0, snapshot.routes.getValue(key).connections)
            assertEquals(0, snapshot.routes.getValue(key).responses)
        }
        assertEquals(setOf(RouteTarget(2, true), RouteTarget(4, false)), snapshot.exhausted)
    }

    @Test
    fun `network change closes pending sockets but not released sockets and rejects late registration`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        val pending = CountingSocket()
        val released = CountingSocket()
        assertTrue(tracker.trackSocket(epoch, pending))
        assertTrue(tracker.trackSocket(epoch, released))
        tracker.releaseSocket(released)

        tracker.updateNetwork(8, NetworkStatus.ONLINE)
        assertEquals(1, pending.closes)
        assertEquals(0, released.closes)
        val late = CountingSocket()
        assertFalse(tracker.trackSocket(epoch, late))
        assertEquals(1, late.closes)

        val current = CountingSocket()
        assertTrue(tracker.trackSocket(tracker.state.value.epoch, current))
        tracker.stop()
        assertEquals(1, current.closes)
        assertEquals(1, pending.closes)
        assertEquals(1, late.closes)
        assertEquals(0, released.closes)
    }

    @Test
    fun `runtime stop clears evidence closes all pending sockets and cannot be revived`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        tracker.record(epoch, regular, RoutePhase.RESPONDING)
        tracker.recordExhausted(epoch, RouteTarget(2, false))
        val throwing = CountingSocket(throwOnClose = true)
        val pending = CountingSocket()
        assertTrue(tracker.trackSocket(epoch, throwing))
        assertTrue(tracker.trackSocket(epoch, pending))

        tracker.stop()
        val stopped = tracker.state.value
        assertTrue(stopped.stopped)
        assertTrue(stopped.epoch > epoch)
        assertFalse(stopped.allowsTraffic)
        assertFalse(stopped.internetConfirmed)
        assertFalse(stopped.allowsBackgroundWork)
        assertTrue(stopped.routes.isEmpty())
        assertTrue(stopped.exhausted.isEmpty())
        assertEquals(1, throwing.closes)
        assertEquals(1, pending.closes)
        assertFalse(tracker.updateNetwork(8, NetworkStatus.ONLINE))
        for (candidate in listOf(epoch, stopped.epoch)) {
            assertFalse(tracker.isCurrent(candidate))
            assertFalse(tracker.recordIfCurrent(candidate) { error("Stopped mutation ran") })
            tracker.record(candidate, regular, RoutePhase.RESPONDING)
            tracker.recordExhausted(candidate, RouteTarget(2, false))
            val late = CountingSocket()
            assertFalse(tracker.trackSocket(candidate, late))
            assertEquals(1, late.closes)
        }
        assertEquals(stopped, tracker.state.value)
    }

    @Test
    fun `evidence window starts at first record and expires exactly after 120 seconds`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        now += 300_000
        val startedAt = now
        tracker.record(epoch, regular, RoutePhase.CONNECTED)
        tracker.record(epoch, regular, RoutePhase.RESPONDING)
        tracker.record(epoch, regular, RoutePhase.FAILED)
        tracker.recordExhausted(epoch, RouteTarget(2, false))
        val before = tracker.state.value
        assertEquals(startedAt, before.evidenceStartedAt)

        now += 119_999
        assertTrue(tracker.isCurrent(epoch))
        assertEquals(before, tracker.state.value)
        now++
        assertEquals(before, tracker.state.value)
        assertTrue(tracker.isCurrent(epoch))
        val expired = tracker.state.value
        assertTrue(expired.routes.isEmpty())
        assertTrue(expired.exhausted.isEmpty())
        assertEquals(epoch, expired.epoch)
        assertEquals(7L, expired.networkId)
        assertEquals(NetworkStatus.ONLINE, expired.network)
        assertTrue(expired.allowsBackgroundWork)

        tracker.record(epoch, regular, RoutePhase.CONNECTED)
        assertEquals(now, tracker.state.value.evidenceStartedAt)
        assertEquals(RouteObservation(RoutePhase.CONNECTED, connections = 1, updatedAt = now),
            tracker.state.value.routes.getValue(regular))
    }

    @Test
    fun `later records do not extend the counter window and next record prunes old counters`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        val startedAt = now
        tracker.record(epoch, regular, RoutePhase.RESPONDING)
        tracker.record(epoch, regular, RoutePhase.FAILED)
        tracker.recordExhausted(epoch, RouteTarget(2, false))
        now += 119_999
        tracker.record(epoch, regular, RoutePhase.CONNECTED)
        assertEquals(startedAt, tracker.state.value.evidenceStartedAt)
        now++
        tracker.record(epoch, regular, RoutePhase.FAILED)

        assertEquals(mapOf(regular to RouteObservation(RoutePhase.FAILED, failures = 1, updatedAt = now)),
            tracker.state.value.routes)
        assertTrue(tracker.state.value.exhausted.isEmpty())
        assertEquals(now, tracker.state.value.evidenceStartedAt)
    }

    @Test
    fun `expired unverified response cannot authorize background mutations failures or exhaustion`() {
        for (status in listOf(NetworkStatus.UNKNOWN, NetworkStatus.UNVERIFIED)) {
            for (action in listOf("isCurrent", "recordIfCurrent", "record", "recordExhausted")) {
                val current = ConnectivityTracker { now }
                current.updateNetwork(7, status)
                val epoch = current.state.value.epoch
                current.record(epoch, regular, RoutePhase.RESPONDING)
                current.record(epoch, regular, RoutePhase.FAILED)
                current.recordExhausted(epoch, RouteTarget(2, false))
                assertTrue(current.state.value.allowsBackgroundWork)
                now += 120_000
                when (action) {
                    "isCurrent" -> assertTrue(current.isCurrent(epoch))
                    "recordIfCurrent" -> assertFalse(current.recordIfCurrent(epoch, requireInternet = true) {
                        error("Expired response authorized background work")
                    })
                    "record" -> current.record(epoch, regular, RoutePhase.FAILED)
                    "recordExhausted" -> current.recordExhausted(epoch, RouteTarget(2, false))
                }
                val expired = current.state.value
                assertTrue("$status $action", expired.allowsTraffic)
                assertFalse("$status $action", expired.internetConfirmed)
                assertFalse("$status $action", expired.allowsBackgroundWork)
                assertTrue(expired.exhausted.isEmpty())
                assertTrue(expired.routes.values.all { it.responses == 0 && it.failures == 0 && it.connections == 0 })
                assertTrue(current.recordIfCurrent(epoch) { })
                current.record(epoch, regular, RoutePhase.CONNECTED)
                assertFalse(current.state.value.internetConfirmed)
                current.record(epoch, regular, RoutePhase.RESPONDING)
                assertTrue(current.state.value.allowsBackgroundWork)
            }
        }
    }

    @Test
    fun `network change cancels pending resources but preserves released ones and rejects old epochs`() {
        tracker.updateNetwork(7, NetworkStatus.ONLINE)
        val epoch = tracker.state.value.epoch
        val pending = CountingResource()
        val throwing = CountingResource(throwOnClose = true)
        val released = CountingResource()
        assertTrue(tracker.trackResource(epoch, throwing))
        assertTrue(tracker.trackResource(epoch, pending))
        assertTrue(tracker.trackResource(epoch, released))
        tracker.releaseResource(released)
        assertFalse(tracker.updateNetwork(7, NetworkStatus.ONLINE))
        assertEquals(0, pending.closes)
        assertEquals(0, throwing.closes)

        tracker.updateNetwork(7, NetworkStatus.BLOCKED)
        assertEquals(1, pending.closes)
        assertEquals(1, throwing.closes)
        assertEquals(0, released.closes)
        val late = CountingResource(throwOnClose = true)
        assertFalse(tracker.trackResource(epoch, late))
        assertEquals(1, late.closes)
        tracker.stop()
        assertEquals(1, pending.closes)
        assertEquals(1, throwing.closes)
        assertEquals(0, released.closes)
    }

    @Test
    fun `stop cancels pending resources despite close failures and rejects new registrations`() {
        tracker.updateNetwork(7, NetworkStatus.UNVERIFIED)
        val epoch = tracker.state.value.epoch
        val throwing = CountingResource(throwOnClose = true)
        val pending = CountingResource()
        val released = CountingResource()
        assertTrue(tracker.trackResource(epoch, throwing))
        assertTrue(tracker.trackResource(epoch, pending))
        assertTrue(tracker.trackResource(epoch, released))
        tracker.releaseResource(released)
        tracker.stop()
        assertEquals(1, throwing.closes)
        assertEquals(1, pending.closes)
        assertEquals(0, released.closes)
        for (candidate in listOf(epoch, tracker.state.value.epoch)) {
            val late = CountingResource()
            assertFalse(tracker.trackResource(candidate, late))
            assertEquals(1, late.closes)
        }
        tracker.stop()
        assertEquals(1, throwing.closes)
        assertEquals(1, pending.closes)
    }

    private class CountingResource(private val throwOnClose: Boolean = false) : AutoCloseable {
        var closes = 0
            private set

        override fun close() {
            closes++
            if (throwOnClose) throw IllegalStateException("Simulated cancellation failure")
        }
    }

    private class CountingSocket(private val throwOnClose: Boolean = false) : Socket() {
        var closes = 0
            private set

        override fun close() {
            closes++
            if (throwOnClose) throw java.io.IOException("Simulated close failure")
            super.close()
        }
    }
}
