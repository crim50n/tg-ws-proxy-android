package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticRedactor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class CfLogPrivacyTest {
    @Test
    fun transportErrorOmitsEndpointsPathsSecretsAndNestedCauses() {
        val error = IOException(
            "Failed https://private.example.com/secret-path?token=private at 192.0.2.1 and [2001:db8::1]\nprivate",
            IllegalStateException("private.example.com/another-private-path"),
        )
        assertEquals("IOException", cfTransportErrorForLog(error))
    }

    @Test
    fun handshakeErrorKeepsOnlyTypeAndStatus() {
        val error = WsHandshakeError(
            429,
            "HTTP/1.1 429 private.example.com/secret-path 192.0.2.1",
            "https://private.example.com/redirect-secret",
        )
        assertEquals("WsHandshakeError (HTTP 429)", cfTransportErrorForLog(error))
    }

    @Test
    fun cfDomainFieldIsFullyRedactedIncludingNonHostnameValues() {
        for (domain in listOf("private.example.com", "192.0.2.1", "[2001:db8::1]", "localhost/private-path")) {
            assertEquals("[redacted]", DiagnosticRedactor.field("domain", domain))
        }
    }
}
