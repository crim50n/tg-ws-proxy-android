package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test

class RawWebSocketCloseTest {
    @Test fun closeStillDisposesSocketAfterPeerEof() {
        val socket = Socket()
        val ws = RawWebSocket::class.java.getDeclaredConstructor(
            Socket::class.java, InputStream::class.java, OutputStream::class.java,
        ).also { it.isAccessible = true }.newInstance(
            socket, ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(),
        )
        assertNull(ws.readBinary())
        assertFalse(socket.isClosed)
        ws.close()
        assertTrue(socket.isClosed)
        ws.close()
    }
}
