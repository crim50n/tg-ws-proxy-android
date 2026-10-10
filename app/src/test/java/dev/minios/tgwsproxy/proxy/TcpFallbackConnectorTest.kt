package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class TcpFallbackConnectorTest {
    private class TestSocket(
        val onConnect: () -> Unit = {},
        val output: OutputStream = ByteArrayOutputStream(),
    ) : Socket() {
        var closed = false
        override fun connect(endpoint: SocketAddress, timeout: Int) {
            assertEquals(10_000, timeout)
            onConnect()
        }
        override fun getOutputStream(): OutputStream = output
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setKeepAlive(on: Boolean) = Unit
        override fun setSendBufferSize(size: Int) = Unit
        override fun setReceiveBufferSize(size: Int) = Unit
        override fun close() { closed = true }
    }

    private val init = ByteArray(64) { it.toByte() }

    @Test
    fun sendsInitBeforeReturningAndResetsBackoffOnSuccess() {
        var now = 0L
        var fail = true
        val sockets = mutableListOf<TestSocket>()
        val connector = TcpFallbackConnector({ now }) {
            TestSocket(onConnect = { if (fail) throw IOException("blocked") }).also { sockets.add(it) }
        }
        assertThrows(IOException::class.java) { connector.connect("127.0.0.1", 443, 1024, init) }
        assertTrue(sockets.last().closed)
        assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        now = 30_000
        fail = false
        val connected = connector.connect("127.0.0.1", 443, 1024, init) as TestSocket
        assertArrayEquals(init, (connected.output as ByteArrayOutputStream).toByteArray())
        assertFalse(connected.closed)
        fail = true
        assertThrows(IOException::class.java) { connector.connect("127.0.0.1", 443, 1024, init) }
        now += 30_000
        fail = false
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun exponentialBackoffIsCappedAndIndependentPerEndpoint() {
        var now = 0L
        var attempts = 0
        val connector = TcpFallbackConnector({ now }) {
            attempts++
            TestSocket(onConnect = { throw IOException("blocked") })
        }
        for (delay in listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 960_000L, 1_920_000L, 3_600_000L, 3_600_000L)) {
            assertThrows(IOException::class.java) { connector.connect("127.0.0.1", 443, 1024, init) }
            val before = attempts
            now += delay - 1
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
            assertEquals(before, attempts)
            now++
        }
        assertThrows(IOException::class.java) { connector.connect("127.0.0.2", 443, 1024, init) }
        assertThrows(IOException::class.java) { connector.connect("127.0.0.1", 80, 1024, init) }
    }

    @Test
    fun initWriteFailureClosesSocketAndBacksOff() {
        var failWrite = true
        val output = object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun flush() { if (failWrite) throw IOException("write failed") }
        }
        val sockets = mutableListOf<TestSocket>()
        val connector = TcpFallbackConnector({ 0L }) {
            TestSocket(output = output).also { sockets.add(it) }
        }
        assertThrows(IOException::class.java) { connector.connect("127.0.0.1", 443, 1024, init) }
        assertTrue(sockets.single().closed)
        assertNull(connector.connect("127.0.0.1", 443, 1024, init))
        connector.reset()
        failWrite = false
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun cancellationDoesNotBackOff() {
        val sockets = mutableListOf<TestSocket>()
        val connector = TcpFallbackConnector({ 0L }) { TestSocket().also { sockets.add(it) } }
        assertThrows(CancellationException::class.java) {
            connector.connect("127.0.0.1", 443, 1024, init) { false }
        }
        assertTrue(sockets.single().closed)
        assertNotNull(connector.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun staleRuntimeShutdownDoesNotDisableReplacement() {
        val oldRuntime = TcpFallbackConnector({ 0L }) { TestSocket() }
        val newRuntime = TcpFallbackConnector({ 0L }) { TestSocket() }
        newRuntime.reset()
        oldRuntime.reset(enabled = false)
        assertNull(oldRuntime.connect("127.0.0.1", 443, 1024, init))
        assertNotNull(newRuntime.connect("127.0.0.1", 443, 1024, init))
    }

    @Test
    fun concurrentAttemptIsSkippedAndResetRejectsLateResult() {
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
            val result = executor.submit<Socket?> { connector.connect("127.0.0.1", 443, 1024, init) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertNull(connector.connect("127.0.0.1", 443, 1024, init))
            connector.reset(enabled = false)
            assertTrue(socket.closed)
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
}
