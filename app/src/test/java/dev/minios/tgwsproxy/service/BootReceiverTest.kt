package dev.minios.tgwsproxy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun acceptsOnlyCompletedBootForResume() {
        assertTrue(isProxyBootAction(BOOT_COMPLETED_ACTION))
        assertFalse(isProxyBootAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertFalse(isProxyBootAction(null))
    }
}
