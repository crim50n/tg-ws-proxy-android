package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfProxyDomainsTest {
    @Test
    fun normalizesAndDeduplicatesDomains() {
        assertEquals(
            listOf("example.com", "sub.example.co.uk"),
            CfProxyDomains.normalizeDomains(
                listOf(" Example.COM ", "example.com", "-bad.example", "sub.example.co.uk"),
            ),
        )
    }

    @Test
    fun validatesDnsNames() {
        assertTrue(CfProxyDomains.isValidDomain("kws2.example.com"))
        assertFalse(CfProxyDomains.isValidDomain("localhost"))
        assertFalse(CfProxyDomains.isValidDomain("bad_domain.example"))
        assertFalse(CfProxyDomains.isValidDomain("example.1"))
        assertFalse(CfProxyDomains.isValidDomain("пример.рф"))
    }
}
