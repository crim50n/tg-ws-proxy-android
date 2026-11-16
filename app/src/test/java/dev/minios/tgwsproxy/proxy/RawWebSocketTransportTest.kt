package dev.minios.tgwsproxy.proxy

import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.Test

class RawWebSocketTransportTest {
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
