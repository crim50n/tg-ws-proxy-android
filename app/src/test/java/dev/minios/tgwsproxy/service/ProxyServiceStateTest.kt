package dev.minios.tgwsproxy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyServiceStateTest {

    @Test
    fun `only stopped state accepts start`() {
        assertTrue(ProxyServiceState.STOPPED.acceptsStart)
        assertFalse(ProxyServiceState.STARTING.acceptsStart)
        assertFalse(ProxyServiceState.RUNNING.acceptsStart)
        assertFalse(ProxyServiceState.RESTARTING.acceptsStart)
        assertFalse(ProxyServiceState.STOPPING.acceptsStart)
    }

    @Test
    fun `stopping state rejects restart`() {
        assertTrue(ProxyServiceState.STARTING.acceptsRestart)
        assertTrue(ProxyServiceState.RUNNING.acceptsRestart)
        assertFalse(ProxyServiceState.RESTARTING.acceptsRestart)
        assertFalse(ProxyServiceState.STOPPING.acceptsRestart)
        assertFalse(ProxyServiceState.STOPPED.acceptsRestart)
    }
}
