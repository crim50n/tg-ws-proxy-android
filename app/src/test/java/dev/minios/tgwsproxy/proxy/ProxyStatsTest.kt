package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.ui.screens.shouldWarnRejectedConnections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyStatsTest {
    @Test
    fun resetAllClearsCurrentRun() {
        ProxyStats.connectionsTotal.set(12)
        ProxyStats.connectionsActive.set(3)
        ProxyStats.connectionsWs.set(8)
        ProxyStats.connectionsTcpFallback.set(2)
        ProxyStats.connectionsCfProxy.set(4)
        ProxyStats.connectionsBad.set(5)
        ProxyStats.wsErrors.set(6)
        ProxyStats.bytesUp.set(1024)
        ProxyStats.bytesDown.set(2048)
        ProxyStats.poolHits.set(7)
        ProxyStats.poolMisses.set(9)
        ProxyStats.startedAtMs = System.currentTimeMillis()

        ProxyStats.resetAll()

        assertEquals(StatsSnapshot(), ProxyStats.snapshot())
    }

    @Test
    fun warnsOnlyForSustainedHighRejectedConnectionRate() {
        assertFalse(shouldWarnRejectedConnections(total = 19, rejected = 15))
        assertFalse(shouldWarnRejectedConnections(total = 100, rejected = 19))
        assertTrue(shouldWarnRejectedConnections(total = 100, rejected = 20))
        assertTrue(shouldWarnRejectedConnections(total = 816, rejected = 651))
    }
}
