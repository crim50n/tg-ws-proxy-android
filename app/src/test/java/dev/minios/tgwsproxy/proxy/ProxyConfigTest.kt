package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ProxyConfigTest {
    @Test
    fun usesFlowsealRouteOrderByDefault() {
        val config = ProxyConfig()

        assertEquals(false, config.cfProxyFirst)
        assertEquals(true, config.cfProxyPriority)
        assertEquals(setOf(2, 4), config.dcRedirects.keys)
        assertEquals("system", config.appTheme)
        assertEquals(true, config.dynamicColor)
        assertEquals(true, config.autoOptimizeConnection)
        assertEquals(RuntimeRouteMode.WS_CF_TCP, config.runtimeRouteMode())
    }

    @Test
    fun parsesSecretBytes() {
        val config = ProxyConfig(secret = "00112233445566778899aabbccddeeff")

        assertArrayEquals(
            byteArrayOf(
                0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
                0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
            ),
            config.secretBytes(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedSecret() {
        ProxyConfig(secret = "not-a-secret").secretBytes()
    }

    @Test
    fun parsesDcRedirects() {
        assertEquals(
            mapOf(2 to "149.154.167.220", 4 to "example.com"),
            ProxyConfig.parseDcRedirects("2:149.154.167.220\ninvalid\n4:example.com"),
        )
    }

    @Test
    fun strictlyRejectsMalformedDcRedirects() {
        assertEquals(null, ProxyConfig.parseDcRedirectsStrict("2:not a host"))
        assertEquals(null, ProxyConfig.parseDcRedirectsStrict("99:149.154.167.220"))
        assertEquals(
            mapOf(2 to "149.154.167.220", 203 to "example.com"),
            ProxyConfig.parseDcRedirectsStrict("2:149.154.167.220\n203:example.com"),
        )
    }
}
