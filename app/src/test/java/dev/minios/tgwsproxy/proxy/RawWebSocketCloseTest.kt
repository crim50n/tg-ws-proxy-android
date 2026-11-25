package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RawWebSocketCloseTest {
    private fun webSocket(socket: Socket, output: OutputStream) =
        RawWebSocket::class.java.getDeclaredConstructor(
            Socket::class.java, InputStream::class.java, OutputStream::class.java,
        ).also { it.isAccessible = true }.newInstance(
            socket, ByteArrayInputStream(byteArrayOf()), output,
        )

    @Test fun closeStillDisposesSocketAfterPeerEof() {
        val socket = Socket()
        val ws = webSocket(socket, ByteArrayOutputStream())
        assertNull(ws.readBinary())
        assertFalse(socket.isClosed)
        ws.close()
        assertTrue(socket.isClosed)
        ws.close()
    }

    @Test fun relayInitWritesAFrameWhileAllDialSlotsAreBlocked() = runBlocking {
        val blocked = CountDownLatch(1)
        val startedCount = AtomicInteger()
        val allStarted = CompletableDeferred<Unit>()
        val dials = List(16) {
            async {
                boundedTransportAttempt(10_000) {
                    if (startedCount.incrementAndGet() == 16) allStarted.complete(Unit)
                    check(blocked.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val output = ByteArrayOutputStream()
        val ws = webSocket(Socket(), output)
        try {
            withTimeout(5_000) { allStarted.await() }
            assertTrue(runCatching {
                boundedTransportAttempt(1_000) { error("A saturated dial must not start") }
            }.exceptionOrNull() is TransportDialNotStarted)
            ws.sendRelayInit(byteArrayOf(1, 2, 3), timeoutMs = 2_000)
            val frame = output.toByteArray()
            assertEquals(9, frame.size)
            assertEquals(0x82.toByte(), frame[0])
            assertEquals(0x83.toByte(), frame[1])
            assertArrayEquals(byteArrayOf(1, 2, 3), ByteArray(3) { i ->
                (frame[6 + i].toInt() xor frame[2 + i].toInt()).toByte()
            })
            assertFalse(ws.isClosed())
        } finally {
            blocked.countDown()
            ws.abort()
            dials.forEach { it.join() }
        }
    }

    @Test fun stalledRelayInitFlushTimesOutAndAbortsWithoutAWebSocketCloseFrame() = runBlocking {
        val inFlush = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { super.close(); closed.countDown() }
        }
        val output = object : ByteArrayOutputStream() {
            override fun flush() {
                inFlush.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val ws = webSocket(socket, output)
        try {
            val result = runCatching { ws.sendRelayInit(byteArrayOf(1, 2, 3), timeoutMs = 500) }
            assertTrue(result.exceptionOrNull() is SocketTimeoutException)
            assertEquals(0L, inFlush.count)
            assertTrue(closed.await(3, TimeUnit.SECONDS))
            assertTrue(ws.isClosed())
            assertEquals(9, output.size()) // Exactly one masked three-byte frame.
        } finally {
            release.countDown()
            ws.abort()
        }
    }
}
