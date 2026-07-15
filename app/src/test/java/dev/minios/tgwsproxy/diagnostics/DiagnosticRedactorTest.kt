package dev.minios.tgwsproxy.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun redactsProxyLinksAndSecrets() {
        val secret = "0123456789abcdef0123456789abcdef"
        val result = DiagnosticRedactor.redact(
            "open tg://proxy?server=127.0.0.1&port=1443&secret=dd$secret secret=$secret",
        )

        assertFalse(result.contains(secret))
        assertFalse(result.contains("tg://"))
        assertTrue(result.contains("[proxy-link-redacted]"))
    }

    @Test
    fun redactsForbiddenFields() {
        assertTrue(DiagnosticRedactor.field("proxyLink", "anything").contains("redacted"))
        assertTrue(DiagnosticRedactor.field("secret", "anything").contains("redacted"))
    }

    @Test
    fun redactsIpAddressesButKeepsPacketCounters() {
        val result = DiagnosticRedactor.redact(
            "failed /149.154.167.41 from 172.16.105.123 and 2001:67c:4e8:f002::a",
        )

        assertFalse(result.contains("149.154.167.41"))
        assertFalse(result.contains("172.16.105.123"))
        assertFalse(result.contains("2001:67c:4e8:f002::a"))
        assertTrue(DiagnosticRedactor.field("packetsUp", 12) == "12")
    }

    @Test
    fun parsesPersistedEntryForLiveLog() {
        val entry = parseDiagnosticLine(
            "2026-07-14T22:42:42.128+0300\tupstream_route_ready dc=2 ip=149.154.167.220 route=ws_pool",
        )

        assertTrue(entry?.event == "upstream_route_ready")
        assertFalse(entry?.details.orEmpty().contains("149.154.167.220"))
    }
}
