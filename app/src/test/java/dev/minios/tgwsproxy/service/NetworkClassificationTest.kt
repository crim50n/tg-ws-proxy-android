package dev.minios.tgwsproxy.service

import dev.minios.tgwsproxy.proxy.NetworkStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkClassificationTest {
    @Test
    fun `no network is offline regardless of other flags`() {
        for (validated in listOf(false, true)) {
            for (captivePortal in listOf(false, true)) {
                for (blocked in listOf(false, true)) {
                    assertEquals("validated=$validated captivePortal=$captivePortal blocked=$blocked",
                        NetworkStatus.OFFLINE,
                        classifyNetwork(false, validated, captivePortal, blocked))
                }
            }
        }
    }

    @Test
    fun `blocked network takes precedence over validation and captive portal`() {
        for (validated in listOf(false, true)) {
            for (captivePortal in listOf(false, true)) {
                assertEquals(NetworkStatus.BLOCKED, classifyNetwork(true, validated, captivePortal, true))
            }
        }
    }

    @Test
    fun `captive portal takes precedence over validation`() {
        for (validated in listOf(false, true)) {
            assertEquals(NetworkStatus.CAPTIVE_PORTAL, classifyNetwork(true, validated, true, false))
        }
    }

    @Test
    fun `unblocked network without portal requires validation to be online`() {
        assertEquals(NetworkStatus.ONLINE, classifyNetwork(true, true, false, false))
        assertEquals(NetworkStatus.UNVERIFIED, classifyNetwork(true, false, false, false))
    }
}
