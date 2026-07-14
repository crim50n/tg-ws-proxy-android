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
}
