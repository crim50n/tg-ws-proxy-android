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
        assertEquals(false, config.dynamicColor)
        assertEquals(true, config.autoOptimizeConnection)
        assertEquals(false, config.keepCpuAwake)
        assertEquals(false, config.preconnectWebSockets)
        assertEquals(false, config.routeProbesEnabled)
        assertEquals(false, config.cfDomainRefreshEnabled)
        assertEquals(180, config.webSocketPingIntervalSeconds)
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

    @Test fun newTransportDefaultsPreserveSecureAndBatterySavingBehavior() {
        val config = ProxyConfig()
        assertEquals(true, config.cfSecure)
        assertEquals(true, config.cfH2Media)
        assertEquals(emptyList<String>(), config.cfWorkerDomains)
        assertEquals(false, config.forceTestDc)
        assertEquals(false, config.proxyProtocol)
        assertEquals("", config.fakeTlsDomain)
    }

    @Test fun domainListsNormalizeDeduplicateAndRejectUrls() {
        assertEquals(listOf("a.example", "b.example"), ProxyConfig.parseDomains("A.EXAMPLE\nb.example,a.example"))
        assertEquals(null, ProxyConfig.parseDomains("https://a.example/path"))
        assertEquals(null, ProxyConfig.parseDomains("a.example:443"))
        assertEquals(null, ProxyConfig.parseDomains((1..33).joinToString("\n") { "host$it.example" }))
    }

    @Test fun legacySingleDomainRemainsSupported() {
        val config = ProxyConfig(cfProxyUserDomain = "legacy.example")
        assertEquals(listOf("legacy.example"), config.customCfDomains())
        assertEquals(listOf("new.example"), config.copy(cfProxyDomains = listOf("new.example")).customCfDomains())
    }

    @Test fun eeSecretEncodesCamouflageHostnameAndIpv6Links() {
        val config = ProxyConfig(host = "::1", secret = "00112233445566778899aabbccddeeff", fakeTlsDomain = "a.example")
        assertEquals("ee00112233445566778899aabbccddeeff612e6578616d706c65", config.linkSecret())
        assertEquals("tg://proxy?server=[::1]&port=1443&secret=${config.linkSecret()}", config.proxyLink())
        assertEquals("dd${config.secret}", config.copy(fakeTlsDomain = "").linkSecret())
    }
}
