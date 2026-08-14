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
        assertTrue(DiagnosticRedactor.field("host", "private.example.com").contains("redacted"))
        assertTrue(DiagnosticRedactor.field("hostname", "private.example.com").contains("redacted"))
        assertFalse(
            DiagnosticRedactor.field(
                "message",
                "Unable to resolve private.example.com",
            ).contains("private.example.com"),
        )
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

    @Test
    fun redactsHostnameFromLegacyPersistedEntry() {
        val entry = parseDiagnosticLine(
            "2026-07-14T22:42:42.128+0300\tINFO\tdns_fallback host=private.example.com",
        )

        assertFalse(entry?.details.orEmpty().contains("private.example.com"))
        assertTrue(entry?.details.orEmpty().contains("[redacted]"))
    }

    @Test
    fun keepsStackIdentifiersWhileRedactingHostnameFromPersistedMessage() {
        val entry = parseDiagnosticLine(
            "2026-07-14T22:42:42.128+0300\tFATAL\tuncaught_exception " +
                "message=Unable to resolve private.example.com " +
                "stack=dev.minios.tgwsproxy.proxy.Bridge.connect(Bridge.kt:239)",
        )

        assertFalse(entry?.details.orEmpty().contains("private.example.com"))
        assertTrue(entry?.details.orEmpty().contains("dev.minios.tgwsproxy.proxy.Bridge.connect"))
        assertTrue(entry?.details.orEmpty().contains("Bridge.kt:239"))
    }

    @Test
    fun parsesLeveledEntryAndKeepsLegacyEntriesCompatible() {
        val error = parseDiagnosticLine(
            "2026-07-14T22:42:42.128+0300\tERROR\truntime_failed reason=timeout",
        )
        val legacy = parseDiagnosticLine(
            "2026-07-14T22:42:42.128+0300\tlistener_ready port=1443",
        )

        assertTrue(error?.level == DiagnosticLevel.ERROR)
        assertTrue(error?.event == "runtime_failed")
        assertTrue(legacy?.level == DiagnosticLevel.INFO)
        assertTrue(legacy?.event == "listener_ready")
    }

    @Test
    fun retentionUsesEntryTimestampInsteadOfFileAge() {
        val cutoff = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            java.util.Locale.US,
        ).parse("2026-07-14T22:42:42.128+0300")!!.time

        assertFalse(
            DiagnosticLogger.isLineWithinRetention(
                "2026-07-13T22:42:42.128+0300\tINFO\told_event",
                cutoff,
            ),
        )
        assertTrue(
            DiagnosticLogger.isLineWithinRetention(
                "2026-07-15T22:42:42.128+0300\tINFO\tnew_event",
                cutoff,
            ),
        )
        assertFalse(DiagnosticLogger.isLineWithinRetention("malformed", cutoff))
    }

    @Test
    fun keepsLongerCrashStackWhileStillRedactingSensitiveData() {
        val value = ("at example.Frame.method(Frame.kt:123) | ".repeat(30)) + "149.154.167.220"
        val result = DiagnosticRedactor.field("stack", value)

        assertTrue(result.length > 500)
        assertFalse(result.contains("149.154.167.220"))
        assertTrue(result.contains("[ip-redacted]"))
    }

}
