package dev.minios.tgwsproxy.proxy

import java.io.EOFException
import java.io.InputStream
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class RawWebSocketTransportTest {
    private fun responseThatStalls(prefix: String, beforeTimeout: () -> Unit = {}): InputStream {
        val bytes = prefix.byteInputStream(Charsets.US_ASCII)
        return object : InputStream() {
            override fun read(): Int {
                val byte = bytes.read()
                if (byte >= 0) return byte
                beforeTimeout()
                throw SocketTimeoutException("Incomplete response")
            }
        }
    }

    @Test fun completedRateLimitIsNotAHeaderTimeout() {
        val rejection = assertThrows(WsHandshakeError::class.java) {
            RawWebSocket.readUpgradeHeaders(responseThatStalls("HTTP/1.1 429 Too Many Requests\r\n") {
                fail("Must not wait for headers after a non-redirect rejection")
            })
        }
        assertEquals(429, rejection.statusCode)
        assertFalse(isTransportTimeout(rejection))
    }

    @Test fun stalledRedirectHeadersCannotEraseKnownStatus() {
        var published: WsHandshakeError? = null
        val rejection = assertThrows(WsHandshakeError::class.java) {
            RawWebSocket.readUpgradeHeaders(
                responseThatStalls("HTTP/1.1 302 Found\r\nServer: test\r\n") {
                    assertEquals(302, published?.statusCode)
                },
                onUpgradeRejected = { published = it },
            )
        }
        assertTrue(rejection.isRedirect)
        assertNull(rejection.location)
        assertFalse(isTransportTimeout(rejection))
    }

    @Test fun intermediateAliasRedirectDoesNotWaitForUnusedLocation() {
        val redirect = assertThrows(WsHandshakeError::class.java) {
            RawWebSocket.readUpgradeHeaders(
                responseThatStalls("HTTP/1.1 302 Found\r\n") { fail("Next alias should be tried immediately") },
                readRedirectLocation = false,
            )
        }
        assertTrue(redirect.isRedirect)
        assertNull(redirect.location)
    }

    @Test fun keepsRedirectLocationWithoutWaitingForRemainingHeaders() {
        val published = mutableListOf<WsHandshakeError>()
        val rejection = assertThrows(WsHandshakeError::class.java) {
            RawWebSocket.readUpgradeHeaders(
                responseThatStalls("HTTP/1.1 307 Temporary Redirect\r\nlocation: https://redirect.example/\r\n") {
                    fail("Location is complete; remaining headers are immaterial")
                },
                onUpgradeRejected = { published.add(it) },
            )
        }
        assertEquals(307, rejection.statusCode)
        assertEquals("https://redirect.example/", rejection.location)
        assertEquals(listOf(null, rejection.location), published.map { it.location })
    }

    @Test fun stalledSwitchingProtocolsHeadersStillTimeOut() {
        assertThrows(SocketTimeoutException::class.java) {
            RawWebSocket.readUpgradeHeaders(responseThatStalls("HTTP/1.1 101 Switching Protocols\r\n")) {
                fail("An incomplete upgrade is not an HTTP rejection")
            }
        }
    }

    @Test fun cancelledTlsSetupClosesTcpBeforeHandshakeFinishes() = runBlocking {
        val clientHello = CompletableDeferred<Unit>()
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                server.soTimeout = 3_000
                val peer = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream()
                        if (input.read() < 0) throw EOFException("Expected TLS ClientHello")
                        clientHello.complete(Unit)
                        while (input.read() >= 0) Unit
                        true
                    }
                }
                val pending = async {
                    boundedTransportAttempt(10_000, RawWebSocket::abort) { attempt ->
                        RawWebSocket.connect("127.0.0.1", "telegram.example", port = server.localPort,
                            onSocketCreated = attempt::register)
                    }
                }
                withTimeout(3_000) { clientHello.await() }
                pending.cancelAndJoin()
                assertTrue(peer.get(3, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun incompleteUpgradeIsBoundedByWholeAttemptAndClosesTheSocket() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0).use { server ->
                server.soTimeout = 3_000
                val peer = executor.submit<Boolean> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val input = socket.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            check(next >= 0)
                            header.append(next.toChar())
                        }
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 101 Switching Protocols\r\n".toByteArray())
                            flush()
                        }
                        input.read() == -1
                    }
                }
                val outcome = runCatching {
                    boundedTransportAttempt(500, RawWebSocket::abort) { attempt ->
                        RawWebSocket.connect("127.0.0.1", "worker.example", port = server.localPort,
                            secure = false, onSocketCreated = attempt::register)
                    }
                }
                assertTrue(outcome.exceptionOrNull() is SocketTimeoutException)
                assertTrue(peer.get(3, TimeUnit.SECONDS))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun optionalPlainWorkerTransportUpgradesAndExchangesMaskedBinaryFrames() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { server ->
            server.soTimeout = 3_000
            val peer = executor.submit<Boolean> {
                server.accept().use { socket ->
                    socket.soTimeout = 3_000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0)
                        header.append(next.toChar())
                    }
                    check(header.startsWith("GET /apiws?dst=192.0.2.2&dc=2 HTTP/1.1\r\n"))
                    check("Host: worker.example\r\n" in header)
                    val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                    output.flush()
                    check(input.read() == 0x82)
                    check(input.read() == 0x83)
                    val mask = ByteArray(4) { input.read().also { check(it >= 0) }.toByte() }
                    val payload = ByteArray(3) { (input.read() xor mask[it % 4].toInt()).toByte() }
                    check(payload.contentEquals(byteArrayOf(1, 2, 3)))
                    output.write(byteArrayOf(0x82.toByte(), 3, 4, 5, 6))
                    output.flush()
                    true
                }
            }
            try {
                RawWebSocket.connect("127.0.0.1", "worker.example",
                    path = "/apiws?dst=192.0.2.2&dc=2", port = server.localPort, secure = false,
                    onSocketCreated = { assertFalse(it is SSLSocket) },
                ).use { ws ->
                    ws.sendBinary(byteArrayOf(1, 2, 3))
                    assertArrayEquals(byteArrayOf(4, 5, 6), ws.readBinary())
                }
                assertTrue(peer.get(5, TimeUnit.SECONDS))
            } finally {
                peer.cancel(true)
                executor.shutdownNow()
            }
        }
    }
}
