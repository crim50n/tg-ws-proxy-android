package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyProtocolTest {
    @Test fun ipv4PreambleDoesNotConsumeHandshakeBytes() {
        val input = ByteArrayInputStream("PROXY TCP4 192.0.2.1 127.0.0.1 12345 1443\r\nsecret".toByteArray())
        ProxyProtocol.read(input)
        assertEquals("secret", input.readBytes().toString(Charsets.US_ASCII))
    }

    @Test fun ipv6AndUnknownHeadersAreSupported() {
        for (header in listOf("PROXY TCP6 2001:db8::1 ::1 12345 1443\r\n", "PROXY UNKNOWN\r\n")) {
            ProxyProtocol.read(ByteArrayInputStream(header.toByteArray()))
        }
    }

    @Test(expected = IOException::class) fun requiresHeaderWhenEnabled() {
        ProxyProtocol.read(ByteArrayInputStream(ByteArray(64) { 0x55 }))
    }

    @Test(expected = IOException::class) fun boundsHeaderLength() {
        ProxyProtocol.read(ByteArrayInputStream(("PROXY UNKNOWN " + "x".repeat(200) + "\r\n").toByteArray()))
    }

    @Test(expected = IOException::class) fun rejectsInvalidAddress() {
        ProxyProtocol.read(ByteArrayInputStream("PROXY TCP4 999.1.1.1 127.0.0.1 12345 1443\r\n".toByteArray()))
    }

    @Test(expected = IOException::class) fun rejectsInvalidPort() {
        ProxyProtocol.read(ByteArrayInputStream("PROXY TCP4 192.0.2.1 127.0.0.1 70000 1443\r\n".toByteArray()))
    }
}
