package dev.minios.tgwsproxy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private fun release(version: String, versionCode: Int? = null) = UpdateInfo(
        versionName = version,
        versionCode = versionCode,
        releaseUrl = "https://github.com/crim50n/tg-ws-proxy-android/releases/tag/v$version",
    )

    @Test
    fun comparesNumericVersionSegments() {
        assertTrue(VersionComparator.compare("1.10.0", "1.9.9")!! > 0)
        assertTrue(VersionComparator.compare("2.0", "1.99.99")!! > 0)
        assertEquals(0, VersionComparator.compare("1.2", "1.2.0"))
        assertNull(VersionComparator.compare("preview", "1.0.0"))
    }

    @Test
    fun apiReleaseComparisonIsIndependentOfCurrentAppRelease() {
        assertTrue(UpdatePolicy.isAvailable(100, "4.9.0", release("5.0.0")))
        assertFalse(UpdatePolicy.isAvailable(100, "5.0.0", release("5.0.0")))
        assertFalse(UpdatePolicy.isAvailable(100, "6.0.0", release("5.0.0")))
    }

    @Test
    fun manifestUsesVersionCodeWhenAvailable() {
        assertTrue(UpdatePolicy.isAvailable(100, "9.0.0", release("5.0.0", versionCode = 101)))
        assertFalse(UpdatePolicy.isAvailable(100, "1.0.0", release("5.0.0", versionCode = 100)))
        assertFalse(UpdatePolicy.isAvailable(100, "1.0.0", release("5.0.0", versionCode = 99)))
    }

    @Test
    fun rejectsReleaseUrlForAnotherTagOrRepository() {
        assertFalse(
            UpdateValidator.isValid(
                UpdateInfo("5.0.0", "https://github.com/crim50n/tg-ws-proxy-android/releases/tag/v4.0.0"),
            ),
        )
        assertFalse(UpdateValidator.isValid(UpdateInfo("5.0.0", "https://example.com/tag/v5.0.0")))
    }
}
