package dev.minios.tgwsproxy.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootReceiverTest {
    @Test
    fun startsOnlyForCompletedBootWhenEnabled() {
        assertTrue(shouldStartProxyOnBoot(BOOT_COMPLETED_ACTION, enabled = true))
        assertFalse(shouldStartProxyOnBoot(BOOT_COMPLETED_ACTION, enabled = false))
        assertFalse(shouldStartProxyOnBoot("android.intent.action.LOCKED_BOOT_COMPLETED", enabled = true))
        assertFalse(shouldStartProxyOnBoot(null, enabled = true))
    }
}
