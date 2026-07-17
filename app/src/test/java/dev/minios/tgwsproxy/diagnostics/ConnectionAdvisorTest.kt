package dev.minios.tgwsproxy.diagnostics

import dev.minios.tgwsproxy.proxy.ProxyConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionAdvisorTest {
    @Test
    fun `recommends Cloudflare first after repeated direct failures and CF successes`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 5),
        )

        assertEquals(
            ConnectionAdvice.USE_CLOUDFLARE_FIRST,
            analyzeConnectionMode(entries, configuredCfEnabled = true, configuredCfFirst = false),
        )
    }

    @Test
    fun `does not recommend mode after failures in only one DC`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 2),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 2),
            success("cloudflare", 2),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `TCP probe failures do not disqualify working direct WebSocket`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("tcp", 2),
            failure("tcp", 4),
            failure("tcp", 2),
            success("ws_direct", 2),
            success("ws_direct", 4),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 2),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `intermittent direct success prevents automatic switch`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("ws_direct", 4),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 2),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `ignores delayed probes from previous runtime`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false runtimeId=2"),
            probeFailure("ws_direct", 2, runtimeId = 1),
            probeFailure("ws_direct", 4, runtimeId = 1),
            probeFailure("ws_direct", 2, runtimeId = 1),
            probeSuccess("cloudflare", 2, runtimeId = 1),
            probeSuccess("cloudflare", 4, runtimeId = 1),
            probeSuccess("cloudflare", 2, runtimeId = 1),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `recommends enabling Cloudflare when all direct routes time out`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=false cfFirst=false"),
            failure("ws_direct", 2),
            failure("tcp", 2),
            failure("ws_direct", 4),
            failure("tcp", 4),
            failure("tcp", 5),
            failure("tcp", 5),
            success("cloudflare", 2),
            success("cloudflare", 4),
        )

        assertEquals(
            ConnectionAdvice.ENABLE_CLOUDFLARE,
            analyzeConnectionMode(entries, configuredCfEnabled = false, configuredCfFirst = false),
        )
    }

    @Test
    fun `uses only the latest runtime`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 5),
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            success("ws_direct", 2),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `resets observations after network change`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 5),
            entry("network_changed", ""),
            success("ws_direct", 2),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `does not repeat recommendation already applied in saved settings`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 5),
        )

        assertNull(
            analyzeConnectionMode(
                entries,
                configuredCfEnabled = true,
                configuredCfFirst = true,
            )
        )
    }

    @Test
    fun `resets observations when recommendation is applied`() {
        val entries = listOf(
            entry("runtime_starting", "cfEnabled=true cfFirst=false"),
            failure("ws_direct", 2),
            failure("ws_direct", 4),
            failure("ws_direct", 2),
            success("cloudflare", 2),
            success("cloudflare", 4),
            success("cloudflare", 5),
            entry("connection_advice_applied", "advice=USE_CLOUDFLARE_FIRST"),
        )

        assertNull(analyzeConnectionMode(entries, true, false))
    }

    @Test
    fun `applying advice preserves automation preference`() {
        val updated = ProxyConfig(autoOptimizeConnection = false)
            .withConnectionAdvice(ConnectionAdvice.USE_CLOUDFLARE_FIRST)

        assertEquals(false, updated.autoOptimizeConnection)
        assertEquals(true, updated.cfProxyEnabled)
        assertEquals(true, updated.cfProxyFirst)
    }

    @Test
    fun `automatic defaults do not modify saved manual config`() {
        val manual = ProxyConfig(
            dcRedirects = mapOf(2 to "example.com"),
            cfProxyEnabled = false,
            cfProxyPriority = false,
            cfProxyFirst = true,
            cfProxyUserDomain = "custom.example.com",
        )

        val automatic = manual.withAutomaticRouteDefaults()

        assertEquals(false, manual.cfProxyEnabled)
        assertEquals(true, manual.cfProxyFirst)
        assertEquals(true, automatic.cfProxyEnabled)
        assertEquals(true, automatic.cfProxyPriority)
        assertEquals(false, automatic.cfProxyFirst)
        assertEquals("", automatic.cfProxyUserDomain)
        assertEquals(256 * 1024, automatic.bufferSize)
        assertEquals(4, automatic.poolSize)
    }

    @Test
    fun `automatic pool follows active route`() {
        val directFirst = ProxyConfig().withAutomaticRouteDefaults()
        val cloudflareFirst = directFirst
            .withConnectionAdvice(ConnectionAdvice.USE_CLOUDFLARE_FIRST)
            .withAutomaticPerformanceDefaults()

        assertEquals(4, directFirst.poolSize)
        assertEquals(0, cloudflareFirst.poolSize)
        assertEquals(256 * 1024, cloudflareFirst.bufferSize)
    }

    @Test
    fun `disabled preconnection forces runtime pool to zero without modifying saved size`() {
        val saved = ProxyConfig(poolSize = 7, preconnectWebSockets = false)

        val runtime = saved.withRuntimeConnectionPreferences()

        assertEquals(7, saved.poolSize)
        assertEquals(0, runtime.poolSize)
        assertEquals(false, runtime.preconnectWebSockets)
    }

    @Test
    fun `background connection settings require restart`() {
        val config = ProxyConfig()

        assertEquals(true, requiresProxyRestart(config, config.copy(keepCpuAwake = false)))
        assertEquals(true, requiresProxyRestart(config, config.copy(preconnectWebSockets = false)))
        assertEquals(true, requiresProxyRestart(config, config.copy(routeProbesEnabled = false)))
        assertEquals(true, requiresProxyRestart(config, config.copy(cfDomainRefreshEnabled = false)))
        assertEquals(true, requiresProxyRestart(config, config.copy(webSocketPingIntervalSeconds = 90)))
        assertEquals(true, requiresProxyRestart(config, config.copy(showTrafficInNotification = false)))
    }

    @Test
    fun `unchanged settings do not require restart`() {
        val config = ProxyConfig()

        assertEquals(false, requiresProxyRestart(config, config.copy()))
    }

    @Test
    fun `interface settings do not require restart`() {
        val config = ProxyConfig()

        assertEquals(
            false,
            requiresProxyRestart(
                config,
                config.copy(showDetailedStats = true, appTheme = "dark", dynamicColor = false),
            ),
        )
    }

    @Test
    fun `hidden manual routes do not restart automatic mode`() {
        val config = ProxyConfig(autoOptimizeConnection = true)

        assertEquals(
            false,
            requiresProxyRestart(
                config,
                config.copy(cfProxyEnabled = false, cfProxyFirst = true, dcRedirects = emptyMap()),
            ),
        )
    }

    @Test
    fun `manual connection change requires restart`() {
        val config = ProxyConfig(autoOptimizeConnection = false)

        assertEquals(true, requiresProxyRestart(config, config.copy(cfProxyFirst = true)))
    }

    private fun failure(route: String, dc: Int) =
        entry("upstream_attempt_failed", "dc=$dc route=$route stage=timeout")

    private fun success(route: String, dc: Int) =
        entry("upstream_route_ready", "dc=$dc route=$route")

    private fun probeFailure(route: String, dc: Int, runtimeId: Long) =
        entry("upstream_attempt_failed", "dc=$dc route=$route source=probe runtimeId=$runtimeId")

    private fun probeSuccess(route: String, dc: Int, runtimeId: Long) =
        entry("upstream_route_ready", "dc=$dc route=$route source=probe runtimeId=$runtimeId")

    private fun entry(event: String, details: String) =
        DiagnosticEntry("2026-07-15T12:00:00.000+0300", event, details)
}
