package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

class ClientSetupInputTest {
    @Test
    fun `empty FIN cancels an in-flight setup`() = runBlocking {
        val loopback = InetAddress.getLoopbackAddress()
        ServerSocket(0, 1, loopback).use { listener ->
            Socket(loopback, listener.localPort).use { peer ->
                listener.accept().use { client ->
                    val input = ClientSetupInput(client)
                    val started = CompletableDeferred<Unit>()
                    val cancelled = CompletableDeferred<Unit>()
                    val failure = withTimeout(5_000) {
                        val setup = async {
                            runCatching {
                                input.duringUpstreamSetup<Unit>(discard = {}) {
                                    try {
                                        started.complete(Unit)
                                        awaitCancellation()
                                    } finally {
                                        cancelled.complete(Unit)
                                    }
                                }
                            }.exceptionOrNull()
                        }
                        started.await()
                        peer.shutdownOutput()
                        setup.await()
                    }
                    assertTrue(failure is CancellationException)
                    assertTrue(cancelled.isCompleted)
                    assertEquals(0, client.soTimeout)
                }
            }
        }
    }

    @Test
    fun `buffered plain bytes survive a clean half-close`() = runBlocking {
        val payload = byteArrayOf(0, 0x42, 0xff.toByte(), 1, 2)
        val fin = CompletableDeferred<Unit>()
        scriptedSocket(payload) { fin.complete(Unit); -1 }.use { client ->
            val input = ClientSetupInput(client)
            client.soTimeout = 1_200
            val upstream = Any()
            val result = withTimeout(5_000) {
                input.duringUpstreamSetup(discard = { error("Unexpected discard") }) {
                    fin.await()
                    upstream
                }
            }

            assertSame(upstream, result)
            assertEquals(1_200, client.soTimeout)
            assertFalse(client.isClosed)
            assertEquals(0, input.read())
            assertArrayEquals(payload.copyOfRange(1, payload.size), input.readBytes())
        }
    }

    @Test
    fun `prefetched bytes reenter the same Fake TLS decoder mid-record`() = runBlocking {
        val fin = CompletableDeferred<Unit>()
        val handshake = ByteArray(64) { it.toByte() }
        val inner = byteArrayOf(0x17, 3, 3, 0x55, 0x66)
        val next = byteArrayOf(0x33, 0x11, 0x72)
        val wire = record(0x17, handshake + inner) +
            record(0x14, byteArrayOf(1)) + record(0x17, next)
        scriptedSocket(wire) { fin.complete(Unit); -1 }.use { client ->
            val input = ClientSetupInput(client)
            val framed = FakeTls.RecordInput(input)
            val parsed = ByteArray(handshake.size)
            assertEquals(handshake.size, framed.read(parsed))
            assertArrayEquals(handshake, parsed)

            withTimeout(5_000) {
                input.duringUpstreamSetup(discard = { error("Unexpected discard") }) { fin.await() }
            }

            assertArrayEquals(inner + next, framed.readBytes())
        }
    }

    @Test
    fun `prefetch cap leaves excess data in the wire stream for the bridge`() = runBlocking {
        val payload = ByteArray(83) { it.toByte() }
        val cap = 13
        val bytesRead = AtomicInteger()
        val prefetched = CompletableDeferred<Unit>()
        val eofCalls = AtomicInteger()
        scriptedSocket(payload, onRead = { count ->
            if (bytesRead.addAndGet(count) >= cap) prefetched.complete(Unit)
        }) {
            eofCalls.incrementAndGet()
            -1
        }.use { client ->
            val input = ClientSetupInput(client, prefetchLimit = cap)
            withTimeout(5_000) {
                input.duringUpstreamSetup(discard = { error("Unexpected discard") }) { prefetched.await() }
            }

            assertEquals(cap, bytesRead.get())
            assertEquals(0, eofCalls.get())
            assertArrayEquals(payload, input.readBytes())
            assertEquals(1, eofCalls.get())
        }
    }

    @Test
    fun `a reset cancels even with pending client bytes and discards a late upstream`() = runBlocking {
        val discarded = AtomicInteger()
        val upstream = Any()
        scriptedSocket(byteArrayOf(0x42)) { throw SocketException("Connection reset") }.use { client ->
            val input = ClientSetupInput(client)
            val failure = withTimeout(5_000) {
                runCatching {
                    input.duringUpstreamSetup(discard = {
                        assertSame(upstream, it)
                        discarded.incrementAndGet()
                    }) {
                        try {
                            awaitCancellation()
                        } catch (_: CancellationException) {
                            upstream
                        }
                    }
                }.exceptionOrNull()
            }
            assertTrue(failure is CancellationException)
            assertEquals(1, discarded.get())
        }
    }

    @Test
    fun `a timed out probe does not cancel setup or leave the short timeout in place`() = runBlocking {
        val probed = CompletableDeferred<Unit>()
        scriptedSocket(byteArrayOf()) {
            probed.complete(Unit)
            throw SocketTimeoutException()
        }.use { client ->
            val input = ClientSetupInput(client)
            client.soTimeout = 10_000
            val result = withTimeout(5_000) {
                input.duringUpstreamSetup(discard = { error("Unexpected discard") }) {
                    probed.await()
                    42
                }
            }
            assertEquals(42, result)
            assertEquals(10_000, client.soTimeout)
        }
    }

    private fun scriptedSocket(
        bytes: ByteArray,
        onRead: (Int) -> Unit = {},
        afterBytes: () -> Int,
    ): Socket = object : Socket() {
        private var timeout = 0
        private val source = ByteArrayInputStream(bytes)
        private val input = object : InputStream() {
            override fun read(): Int = source.read().takeIf { it >= 0 } ?: afterBytes()

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = source.read(bytes, offset, length)
                if (count < 0) return afterBytes()
                onRead(count)
                return count
            }
        }

        override fun getInputStream(): InputStream = input
        override fun getSoTimeout(): Int = timeout
        override fun setSoTimeout(timeout: Int) { this.timeout = timeout }
    }

    private fun record(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), 3, 3, 0, payload.size.toByte()) + payload
}
