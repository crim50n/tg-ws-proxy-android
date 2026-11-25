package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class TcpFallbackConnectorTest {
    private class TestSocket(
        val onConnect: () -> Unit = {},
        val output: OutputStream = ByteArrayOutputStream(),
    ) : Socket() {
        private val closed = CountDownLatch(1)
        fun awaitClosed(): Boolean = closed.await(3, TimeUnit.SECONDS)
        override fun isClosed(): Boolean = closed.count == 0L
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            assertEquals(10_000, timeout)
            onConnect()
        }
        override fun getOutputStream(): OutputStream = output
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setKeepAlive(on: Boolean) = Unit
        override fun setSendBufferSize(size: Int) = Unit
        override fun setReceiveBufferSize(size: Int) = Unit
        override fun close() { closed.countDown() }
    }

    private val init = ByteArray(64) { it.toByte() }

    private suspend inline fun <reified T : Throwable> assertConnectThrows(block: () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected ${T::class.simpleName}, got $error", error is T)
    }

    @Test
    fun sendsInitBeforeReturningAndResetsBackoffOnSuccess() = runBlocking {
        var now = 0L
        var fail = true
        val sockets = mutableListOf<TestSocket>()
        val connector = TcpFallbackConnector({ now }) {
            TestSocket(onConnect = { if (fail) throw IOException("blocked") }).also { sockets.add(it) }
        }
        assertConnectThrows<IOException> { connector.connect("127.0.0.1", 443, 1024, init) }
        assertTrue(sockets.last().awaitClosed())
        assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        now = 30_000
        fail = false
        val connected = connector.connect("127.0.0.1", 443, 1024, init) as TestSocket
        assertArrayEquals(init, (connected.output as ByteArrayOutputStream).toByteArray())
        assertFalse(connected.isClosed)
        fail = true
        assertConnectThrows<IOException> { connector.connect("127.0.0.1", 443, 1024, init) }
        now += 30_000
        fail = false
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun tcpInitUsesAnIndependentSlotWhenDialsFillUpAfterConnect() = runBlocking {
        val releaseDials = CountDownLatch(1)
        val firstDialsStarted = CountDownLatch(15)
        val tcpConnected = CompletableDeferred<Unit>()
        val lastDialStarted = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val socket = TestSocket(onConnect = { tcpConnected.complete(Unit) }, output = output)
        val firstDials = List(15) {
            async(Dispatchers.Default) {
                boundedTransportAttempt(15_000) {
                    firstDialsStarted.countDown()
                    check(releaseDials.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val lastDial = async(Dispatchers.Default) {
            tcpConnected.await()
            withTimeout(12_000) {
                while (true) {
                    try {
                        boundedTransportAttempt(12_000) {
                            lastDialStarted.countDown()
                            check(releaseDials.await(10, TimeUnit.SECONDS))
                        }
                        break
                    } catch (_: TransportDialNotStarted) {
                        // The TCP dial may still be returning its slot.
                        delay(1)
                    }
                }
            }
        }
        try {
            assertTrue(firstDialsStarted.await(3, TimeUnit.SECONDS))
            val connector = TcpFallbackConnector({ 0L }) { socket }
            val connected = connector.connect("127.0.0.1", 443, 1024, init, isActive = {
                if (tcpConnected.isCompleted) check(lastDialStarted.await(3, TimeUnit.SECONDS))
                true
            })
            assertSame(socket, connected)
            assertArrayEquals(init, output.toByteArray())
            assertFalse(socket.isClosed)
        } finally {
            releaseDials.countDown()
            if (tcpConnected.isCompleted) lastDial.join() else lastDial.cancelAndJoin()
            firstDials.forEach { it.join() }
            socket.close()
        }
    }

    @Test
    fun exponentialBackoffIsCappedAndIndependentPerEndpoint() = runBlocking {
        var now = 0L
        var attempts = 0
        val connector = TcpFallbackConnector({ now }) {
            attempts++
            TestSocket(onConnect = { throw IOException("blocked") })
        }
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L)) {
            assertConnectThrows<IOException> { connector.connect("127.0.0.1", 443, 1024, init) }
            val before = attempts
            now += delay - 1
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
            assertEquals(before, attempts)
            now++
        }
        assertConnectThrows<IOException> { connector.connect("127.0.0.2", 443, 1024, init) }
        assertConnectThrows<IOException> { connector.connect("127.0.0.1", 80, 1024, init) }
    }

    @Test
    fun initWriteFailureClosesSocketAndBacksOff() = runBlocking {
        var failWrite = true
        val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() { if (failWrite) throw IOException("write failed") }
        }
        val sockets = mutableListOf<TestSocket>()
        val connector = TcpFallbackConnector({ 0L }) {
            TestSocket(output = output).also { sockets.add(it) }
        }
        assertConnectThrows<IOException> { connector.connect("127.0.0.1", 443, 1024, init) }
        assertTrue(sockets.single().awaitClosed())
        assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        connector.reset()
        failWrite = false
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun cancellationDoesNotBackOff() = runBlocking {
        val sockets = mutableListOf<TestSocket>()
        var active = true
        var cancel = true
        val connector = TcpFallbackConnector({ 0L }) {
            TestSocket(onConnect = { if (cancel) active = false }).also { sockets.add(it) }
        }
        assertConnectThrows<CancellationException> {
            connector.connect("127.0.0.1", 443, 1024, init, isActive = { active })
        }
        assertTrue(sockets.single().awaitClosed())
        active = true
        // A different endpoint also checks cancellation at entry without creating a socket.
        assertNull(connector.connect("127.0.0.2", 443, 1024, init, isActive = { false }))
        assertEquals(1, sockets.size)
        cancel = false
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init, isActive = { active }))
    }

    @Test
    fun unconfirmedFailureDoesNotBackOffOrIncreaseNextConfirmedBackoff() = runBlocking {
        var now = 0L
        var attempts = 0
        val connector = TcpFallbackConnector({ now }) {
            attempts++
            TestSocket(onConnect = { throw IOException("unavailable") })
        }
        repeat(2) {
            assertConnectThrows<IOException> {
                connector.connect("127.0.0.1", 443, 1024, init, canRecordFailure = { false })
            }
        }
        assertEquals(2, attempts)
        assertConnectThrows<IOException> {
            connector.connect("127.0.0.1", 443, 1024, init, canRecordFailure = { true })
        }
        assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        now = 30_000L
        assertConnectThrows<IOException> { connector.connect("127.0.0.1", 443, 1024, init) }
        assertEquals(4, attempts)
    }

    @Test
    fun failureGateIsCheckedAfterConnectFails() = runBlocking {
        var confirmed = true
        val connector = TcpFallbackConnector({ 0L }) {
            TestSocket(onConnect = {
                confirmed = false
                throw IOException("network lost")
            })
        }
        repeat(2) {
            confirmed = true
            assertConnectThrows<IOException> {
                connector.connect("127.0.0.1", 443, 1024, init, canRecordFailure = { confirmed })
            }
        }
    }

    @Test
    fun staleRuntimeShutdownDoesNotDisableReplacement() = runBlocking {
        val oldRuntime = TcpFallbackConnector({ 0L }) { TestSocket() }
        val newRuntime = TcpFallbackConnector({ 0L }) { TestSocket() }
        newRuntime.reset()
        oldRuntime.reset(enabled = false)
        assertNull(oldRuntime.connect("127.0.0.1", 443, 1024, init))
        assertNotNull(newRuntime.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun concurrentAttemptIsSkippedAndResetRejectsLateResult() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = TestSocket(onConnect = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        })
        var first = true
        val connector = TcpFallbackConnector({ 0L }) {
            if (first) { first = false; socket } else TestSocket()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<Socket?> { runBlocking { connector.connect("127.0.0.1", 443, 1024, init) } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
            connector.reset(enabled = false)
            assertTrue(socket.awaitClosed())
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
            connector.reset()
            release.countDown()
            val error = assertThrows(java.util.concurrent.ExecutionException::class.java) {
                result.get(5, TimeUnit.SECONDS)
            }
            assertTrue(error.cause is IOException)
            assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun blockedInitTimesOutAndDisposesSocketBeforeTheWriteReturns() = runBlocking {
        val inFlush = CountDownLatch(1)
        val release = CountDownLatch(1)
        val socket = TestSocket(output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() {
                inFlush.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        })
        val connector = TcpFallbackConnector({ 0L }) { socket }
        try {
            assertConnectThrows<SocketTimeoutException> {
                connector.connect("127.0.0.1", 443, 1024, init, relayInitTimeoutMs = 500)
            }
            assertEquals(0L, inFlush.count)
            assertTrue(socket.awaitClosed())
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cancelledConnectClosesPendingSocketWithoutBackoffOrLateInit() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val first = TestSocket(onConnect = {
            started.complete(Unit)
            check(release.await(5, TimeUnit.SECONDS))
        })
        var attempts = 0
        val connector = TcpFallbackConnector({ 0L }) { if (attempts++ == 0) first else TestSocket() }
        try {
            val pending = async { connector.connect("127.0.0.1", 443, 1024, init) }
            started.await()
            pending.cancelAndJoin()
            assertTrue(first.awaitClosed())
            assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
            assertEquals(0, (first.output as ByteArrayOutputStream).size())
        } finally {
            release.countDown()
        }
    }
}
