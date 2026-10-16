package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class BridgeShutdownTest {

    @Test
    fun `offline connect skips all transports before creating a socket`() {
        val tracker = ConnectivityTracker()
        tracker.updateNetwork(null, NetworkStatus.OFFLINE)
        val fallback = TcpFallbackConnector({ 0L }) { error("Must not dial while offline") }
        assertThrows(CancellationException::class.java) {
            runBlocking {
                Bridge(tracker).connectUpstream(
                    dc = 2,
                    isMedia = false,
                    config = ProxyConfig(secret = "00".repeat(16), cfProxyFirst = true),
                    pool = null,
                    protoTag = 0,
                    relayInit = ByteArray(64),
                    tcpFallback = fallback,
                )
            }
        }
        assertEquals(emptyMap<RouteKey, RouteObservation>(), tracker.state.value.routes)
    }

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
