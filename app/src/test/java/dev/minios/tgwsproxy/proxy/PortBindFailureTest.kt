package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortBindFailureTest {
    @Test
    fun recognizesAndroidAndJvmAddressInUseMessages() {
        assertTrue(isAddressAlreadyInUse("bind failed: EADDRINUSE (Address already in use)"))
        assertTrue(isAddressAlreadyInUse("Address already in use"))
    }

    @Test
    fun doesNotMisclassifyOtherBindFailures() {
        assertFalse(isAddressAlreadyInUse("Cannot assign requested address"))
        assertFalse(isAddressAlreadyInUse("Permission denied"))
        assertFalse(isAddressAlreadyInUse(null))
    }
}
