package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeShutdownTest {

    @Test
    fun `shutdown closes both endpoints before bridge jobs are cancelled`() {
        val closed = mutableListOf<String>()

        closeBridgeEndpoints(
            closeClient = { closed += "client" },
            closeUpstream = { closed += "upstream" },
        )

        assertEquals(listOf("client", "upstream"), closed)
    }

    @Test
    fun `upstream is still closed when client close fails`() {
        val closed = mutableListOf<String>()

        closeBridgeEndpoints(
            closeClient = { throw IllegalStateException("already closed") },
            closeUpstream = { closed += "upstream" },
        )

        assertEquals(listOf("upstream"), closed)
    }
}
