package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WsPoolTest {
    private val pools = CopyOnWriteArrayList<WsPool>()
    private val sockets = CopyOnWriteArrayList<SSLSocket>()
    private val redirects = mapOf(2 to "192.0.2.2")

    private data class Attempt(
        val ip: String,
        val domains: List<String>,
        val time: Long,
        val socket: SSLSocket,
        val ws: RawWebSocket,
    )

    private inner class Harness(scope: TestScope, size: Int = 1, age: Long = 120_000) {
        val attempts = mutableListOf<Attempt>()
        var result: (Attempt) -> RawWebSocket? = { it.ws }
        val pool = WsPool(size, age, 1024, { scope.testScheduler.currentTime },
            StandardTestDispatcher(scope.testScheduler)) { ip, domains, created ->
            val socket = newSocket()
            created(socket)
            val attempt = Attempt(ip, domains.toList(), scope.testScheduler.currentTime,
                socket, webSocket(socket))
            attempts.add(attempt)
            result(attempt)
        }.also {
            pools.add(it)
            it.start(scope.backgroundScope)
        }

        fun dataAttempts() = attempts.filter { it.domains == MtProtoConstants.wsDomainsForDc(2, false) }
    }

    @After
    fun cleanUp() {
        pools.forEach { it.stop() }
        sockets.forEach { it.close() }
    }

    private fun newSocket(): SSLSocket =
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).also { sockets.add(it) }

    // Real, unconnected TLS sockets exercise abort/ownership without network or Android APIs.
    private fun webSocket(socket: SSLSocket): RawWebSocket =
        RawWebSocket::class.java.getDeclaredConstructor(
            SSLSocket::class.java, InputStream::class.java, OutputStream::class.java,
        ).also { it.isAccessible = true }.newInstance(
            socket, ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
        )

    @Test
    fun disabledPoolNeverDialsOrSchedulesWorkers() = runTest {
        val h = Harness(this, size = 0)
        h.pool.warmUp(redirects)
        repeat(20) {
            assertNull(h.pool.get(2, false))
            assertNull(h.pool.get(2, true))
        }
        h.pool.resetAndWarm(redirects)
        h.pool.updateRedirects(mapOf(3 to "192.0.2.3"))
        runCurrent()
        advanceTimeBy(7_200_000)
        runCurrent()
        assertTrue(h.attempts.isEmpty())
        val buckets = WsPool::class.java.getDeclaredField("buckets").also { it.isAccessible = true }
        assertTrue((buckets.get(h.pool) as Map<*, *>).isEmpty())
    }

    @Test
    fun warmupFillsSeparateDataAndMediaBucketsAndUnchangedRoutingKeepsThem() = runTest {
        val h = Harness(this, size = 2)
        h.pool.warmUp(redirects)
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertEquals(2, h.dataAttempts().size)
        assertTrue(h.attempts.all { it.ip == "192.0.2.2" })
        assertEquals(2, h.attempts.count { it.domains == MtProtoConstants.wsDomainsForDc(2, true) })
        h.pool.warmUp(redirects.toMutableMap())
        h.pool.updateRedirects(redirects)
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertSame(h.dataAttempts()[0].ws, h.pool.get(2, false))
        assertSame(h.dataAttempts()[1].ws, h.pool.get(2, false))
        assertNull(h.pool.get(2, false))
        assertNotNull(h.pool.get(2, true))
        assertNull(h.pool.get(99, false))
    }

    @Test
    fun idleConnectionsRotateAndRefillAutomaticallyAtExactExpiry() = runTest {
        val h = Harness(this, size = 2, age = 100)
        h.pool.warmUp(redirects)
        runCurrent()
        val original = h.attempts.toList()
        advanceTimeBy(99)
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertTrue(original.none { it.socket.isClosed })
        advanceTimeBy(1)
        runCurrent()
        assertEquals(8, h.attempts.size)
        assertTrue(original.all { it.socket.isClosed && it.ws.isClosed() })
        assertTrue(h.attempts.drop(4).all { it.time == 100L && !it.socket.isClosed })
        advanceTimeBy(100)
        runCurrent()
        assertEquals(12, h.attempts.size)
        assertTrue(h.attempts.take(8).all { it.socket.isClosed })
    }

    @Test
    fun getDiscardsExpiredSocketBeforeWorkerRunsAndWakesRefill() = runTest {
        val h = Harness(this, age = 100)
        h.pool.warmUp(redirects)
        runCurrent()
        advanceTimeBy(100)
        assertNull(h.pool.get(2, false))
        assertTrue(h.dataAttempts().single().socket.isClosed)
        runCurrent()
        assertEquals(2, h.dataAttempts().size)
        assertSame(h.dataAttempts().last().ws, h.pool.get(2, false))
    }

    @Test
    fun checkoutWakesRefillWithoutClosingCallerOwnedConnection() = runTest {
        val h = Harness(this, age = 100)
        h.pool.warmUp(redirects)
        runCurrent()
        val checkedOut = h.pool.get(2, false)
        assertSame(h.dataAttempts().single().ws, checkedOut)
        runCurrent()
        assertEquals(2, h.dataAttempts().size)
        advanceTimeBy(100)
        runCurrent()
        h.pool.stop()
        assertFalse(checkedOut!!.isClosed())
        assertTrue(h.attempts.filter { it.ws !== checkedOut }.all { it.socket.isClosed })
    }

    @Test
    fun closedIdleSocketIsRejectedAndReplacedOnGet() = runTest {
        val h = Harness(this)
        h.pool.warmUp(redirects)
        runCurrent()
        h.dataAttempts().single().socket.close()
        assertNull(h.pool.get(2, false))
        runCurrent()
        assertEquals(2, h.dataAttempts().size)
        assertSame(h.dataAttempts().last().ws, h.pool.get(2, false))
    }

    @Test
    fun emptyWarmupRetriesExponentiallyAndRemainsCappedAtOneHour() = runTest {
        val h = Harness(this, size = 2)
        h.result = { null }
        h.pool.warmUp(redirects)
        runCurrent()
        var expectedAttempts = 4
        // Assert real dial batches at each deadline, not just the delay calculation helper.
        val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L,
            64_000L, 128_000L, 256_000L, 512_000L, 1_024_000L, 2_048_000L,
            3_600_000L, 3_600_000L, 3_600_000L)
        for (delay in delays) {
            assertEquals(expectedAttempts, h.attempts.size)
            assertTrue(h.attempts.all { it.socket.isClosed })
            advanceTimeBy(delay - 1)
            runCurrent()
            assertEquals(expectedAttempts, h.attempts.size)
            advanceTimeBy(1)
            runCurrent()
            expectedAttempts += 4
            assertEquals(expectedAttempts, h.attempts.size)
            assertTrue(h.attempts.takeLast(4).all { it.time == testScheduler.currentTime })
        }
    }

    @Test
    fun getAndUnchangedWarmupDuringCooldownDoNotCauseRetryStorm() = runTest {
        val h = Harness(this)
        h.result = { null }
        h.pool.warmUp(redirects)
        runCurrent()
        repeat(20) {
            advanceTimeBy(49)
            assertNull(h.pool.get(2, false))
            assertNull(h.pool.get(2, true))
            h.pool.warmUp(redirects)
            h.pool.updateRedirects(redirects)
            runCurrent()
            assertEquals(2, h.attempts.size)
        }
        advanceTimeBy(20)
        runCurrent()
        assertEquals(4, h.attempts.size)
    }

    @Test
    fun connectorExceptionsCloseSocketsAndRetryWithoutKillingWorker() = runTest {
        val h = Harness(this)
        h.result = { throw IOException("dial failed") }
        h.pool.warmUp(redirects)
        runCurrent()
        assertEquals(2, h.attempts.size)
        assertTrue(h.attempts.all { it.socket.isClosed })
        h.result = { it.ws }
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertSame(h.dataAttempts().last().ws, h.pool.get(2, false))
    }

    @Test
    fun successfulRetryResetsBackoffForNextEmptyBatch() = runTest {
        val h = Harness(this)
        h.result = { null }
        h.pool.warmUp(redirects)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        h.result = { it.ws }
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(6, h.attempts.size)
        h.result = { null }
        assertNotNull(h.pool.get(2, false))
        runCurrent()
        assertEquals(7, h.attempts.size)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(7, h.attempts.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(8, h.attempts.size)
    }

    @Test
    fun partialSuccessIsKeptAndOnlyMissingSlotsAreRetried() = runTest {
        val h = Harness(this, size = 2)
        var dataDials = 0
        h.result = { attempt ->
            if (attempt.domains == MtProtoConstants.wsDomainsForDc(2, false) &&
                ++dataDials == 2) null else attempt.ws
        }
        h.pool.warmUp(redirects)
        runCurrent()
        assertEquals(5, h.attempts.size)
        val data = h.dataAttempts()
        assertEquals(3, data.size)
        assertTrue(data[1].socket.isClosed)
        assertFalse(data[0].socket.isClosed)
        assertFalse(data[2].socket.isClosed)
        assertTrue(h.attempts.all { it.time == 0L })
        assertSame(data[0].ws, h.pool.get(2, false))
        assertSame(data[2].ws, h.pool.get(2, false))
        assertNull(h.pool.get(2, false))
    }

    @Test
    fun failedRouteCooldownDoesNotBlockOtherDcOrMediaRefills() = runTest {
        val h = Harness(this)
        h.result = { attempt ->
            if (attempt.ip == "192.0.2.2" &&
                attempt.domains == MtProtoConstants.wsDomainsForDc(2, false)) null else attempt.ws
        }
        h.pool.warmUp(redirects + (3 to "192.0.2.3"))
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertNull(h.pool.get(2, false))
        assertNotNull(h.pool.get(2, true))
        assertNotNull(h.pool.get(3, false))
        assertNotNull(h.pool.get(3, true))
        runCurrent()
        assertEquals(7, h.attempts.size)
        assertEquals(1, h.dataAttempts().size)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(8, h.attempts.size)
        assertEquals(2, h.dataAttempts().size)
    }

    @Test
    fun stopDuringCooldownCancelsScheduledRetry() = runTest {
        val h = Harness(this)
        h.result = { null }
        h.pool.warmUp(redirects)
        runCurrent()
        assertEquals(2, h.attempts.size)
        h.pool.stop()
        advanceTimeBy(7_200_000)
        runCurrent()
        assertEquals(2, h.attempts.size)
        assertTrue(h.attempts.all { it.socket.isClosed })
        assertNull(h.pool.get(2, false))
    }

    @Test
    fun routingUpdateClosesOldBucketsUsesNewTargetsAndRemovesMissingRoutes() = runTest {
        val h = Harness(this)
        h.pool.warmUp(redirects)
        runCurrent()
        val old = h.attempts.toList()
        val updated = mutableMapOf(3 to "192.0.2.33")
        h.pool.updateRedirects(updated)
        updated[3] = "192.0.2.99"
        assertTrue(old.all { it.socket.isClosed })
        assertNull(h.pool.get(2, false))
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertTrue(h.attempts.drop(2).all { it.ip == "192.0.2.33" })
        assertEquals(MtProtoConstants.wsDomainsForDc(3, false), h.attempts[2].domains)
        assertEquals(MtProtoConstants.wsDomainsForDc(3, true), h.attempts[3].domains)
        assertNotNull(h.pool.get(3, false))
        h.pool.updateRedirects(emptyMap())
        runCurrent()
        assertNull(h.pool.get(3, true))
        assertEquals(4, h.attempts.size)
    }

    @Test
    fun resetWithUnchangedRoutesClosesIdleSocketsAndBypassesExistingCooldown() = runTest {
        val h = Harness(this)
        h.pool.warmUp(redirects)
        runCurrent()
        val old = h.attempts.toList()
        h.pool.resetAndWarm(redirects)
        assertTrue(old.all { it.socket.isClosed })
        h.result = { null }
        runCurrent()
        assertEquals(4, h.attempts.size)
        h.result = { it.ws }
        h.pool.resetAndWarm(redirects)
        runCurrent()
        assertEquals(6, h.attempts.size)
        assertSame(h.dataAttempts().last().ws, h.pool.get(2, false))
    }

    @Test
    fun stopClosesIdleSocketsPreventsRetriesAndAllowsFreshRestart() = runTest {
        val h = Harness(this)
        h.pool.warmUp(redirects)
        runCurrent()
        h.pool.stop()
        assertTrue(h.attempts.all { it.socket.isClosed })
        h.pool.warmUp(redirects)
        h.pool.resetAndWarm(redirects)
        assertNull(h.pool.get(2, false))
        advanceTimeBy(7_200_000)
        runCurrent()
        assertEquals(2, h.attempts.size)
        h.pool.start(backgroundScope)
        h.pool.start(backgroundScope)
        h.pool.warmUp(redirects)
        runCurrent()
        assertEquals(4, h.attempts.size)
        assertNotNull(h.pool.get(2, false))
    }

    @Test
    fun scopeCancellationClosesIdleSocketsAndPreventsFurtherDials() = runTest {
        val h = Harness(this)
        h.pool.warmUp(redirects)
        runCurrent()
        backgroundScope.cancel()
        runCurrent()
        assertTrue(h.attempts.all { it.socket.isClosed })
        assertNull(h.pool.get(2, false))
        h.pool.warmUp(redirects)
        advanceTimeBy(1_000_000)
        runCurrent()
        assertEquals(2, h.attempts.size)
    }

    @Test
    fun fastestCompletionIsAvailableWhileSiblingDialIsStillBlocked() = runTest {
        val started = CountDownLatch(2)
        val releaseSlow = CountDownLatch(1)
        val sequence = AtomicInteger()
        val completed = CopyOnWriteArrayList<RawWebSocket>()
        val pool = WsPool(2, 120_000, 1024, { 0L }, Dispatchers.IO) { _, domains, created ->
            val socket = newSocket()
            created(socket)
            val ws = webSocket(socket)
            if (domains == MtProtoConstants.wsDomainsForDc(2, false)) {
                val order = sequence.incrementAndGet()
                started.countDown()
                if (order == 1) check(releaseSlow.await(15, TimeUnit.SECONDS))
                completed.add(ws)
                ws
            } else null
        }.also { pools.add(it); it.start(backgroundScope) }
        try {
            pool.warmUp(redirects)
            withContext(Dispatchers.IO) {
                assertTrue("both data dials must run concurrently", started.await(5, TimeUnit.SECONDS))
                var fastest: RawWebSocket? = null
                awaitCondition { pool.get(2, false).also { fastest = it } != null }
                assertEquals(1L, releaseSlow.count)
                assertSame(completed.single(), fastest)
                assertFalse(fastest!!.isClosed())
                releaseSlow.countDown()
                awaitCondition { completed.size >= 2 }
                var next: RawWebSocket? = null
                awaitCondition { pool.get(2, false).also { next = it } != null }
                assertNotSame(fastest, next)
            }
        } finally {
            releaseSlow.countDown()
            pool.stop()
        }
    }

    @Test
    fun routingUpdateClosesPendingSocketsAndRejectsLateResults() = lateResults("update")

    @Test
    fun resetClosesPendingSocketsAndRejectsLateResults() = lateResults("reset")

    @Test
    fun stopClosesPendingSocketsAndRejectsLateResults() = lateResults("stop")

    @Test
    fun cancellationClosesPendingSocketsAndRejectsLateResults() = lateResults("cancel")

    private fun lateResults(action: String) = runTest {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val old = CopyOnWriteArrayList<Attempt>()
        val fresh = CopyOnWriteArrayList<Attempt>()
        val sequence = AtomicInteger()
        val pool = WsPool(1, 120_000, 1024, { 0L }, Dispatchers.IO) { ip, domains, created ->
            val socket = newSocket()
            created(socket)
            val attempt = Attempt(ip, domains, 0, socket, webSocket(socket))
            if (sequence.incrementAndGet() <= 2) {
                old.add(attempt)
                started.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            } else fresh.add(attempt)
            attempt.ws
        }.also { pools.add(it); it.start(backgroundScope) }
        try {
            pool.warmUp(redirects)
            withContext(Dispatchers.IO) {
                assertTrue(started.await(5, TimeUnit.SECONDS))
                when (action) {
                    "update" -> pool.updateRedirects(mapOf(2 to "192.0.2.22"))
                    "reset" -> pool.resetAndWarm(redirects)
                    "stop" -> pool.stop()
                    "cancel" -> backgroundScope.cancel()
                    else -> error(action)
                }
                awaitCondition("pending sockets were not closed after $action") {
                    old.all { it.socket.isClosed }
                }
                release.countDown()
                // isClosed alone would also pass for an un-aborted late result because its
                // pending socket was already closed. Check RawWebSocket's own abort flag.
                val closed = RawWebSocket::class.java.getDeclaredField("closed").also { it.isAccessible = true }
                awaitCondition { old.all { closed.getBoolean(it.ws) } }
                if (action == "update" || action == "reset") {
                    awaitCondition { fresh.size == 2 }
                    var replacement: RawWebSocket? = null
                    awaitCondition { pool.get(2, false).also { replacement = it } != null }
                    assertTrue(fresh.any { it.ws === replacement })
                    assertTrue(old.none { it.ws === replacement })
                    val expectedIp = if (action == "update") "192.0.2.22" else "192.0.2.2"
                    assertTrue(fresh.all { it.ip == expectedIp })
                } else {
                    assertNull(pool.get(2, false))
                    assertTrue(fresh.isEmpty())
                }
            }
        } finally {
            release.countDown()
            pool.stop()
        }
    }

    private fun awaitCondition(message: String = "condition not met within five seconds", condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() >= deadline) fail(message)
            Thread.sleep(1)
        }
    }
}
