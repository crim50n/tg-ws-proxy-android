package dev.minios.tgwsproxy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPolicyTest {
    private val intervalMs = 24 * 60 * 60_000L

    private fun release(version: String, code: Int? = null) = UpdateInfo(
        versionName = version,
        releaseUrl = "https://github.com/crim50n/tg-ws-proxy-android/releases/tag/v$version",
        versionCode = code,
    )

    @Test
    fun stoppedServiceDoesNotNotifyEvenForANewRelease() {
        assertFalse(UpdatePolicy.shouldNotify(false, 8, "1.6.0", release("1.7.0"), null))
        assertFalse(UpdatePolicy.shouldNotify(false, 8, "1.6.0", release("1.7.0", 9), null))
        assertTrue(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.7.0"), null))
    }

    @Test
    fun upToDateAndOlderApiReleasesDoNotNotify() {
        for (version in listOf("1.6.0", "1.6", "1.5.9")) {
            assertFalse(version, UpdatePolicy.shouldNotify(true, 8, "1.6.0", release(version), null))
        }
    }

    @Test
    fun invalidReleaseMetadataDoesNotNotify() {
        val valid = release("1.7.0", 9)
        val invalid = listOf(
            release("preview", 9),
            valid.copy(versionCode = 0),
            valid.copy(versionCode = -1),
            valid.copy(releaseUrl = "https://example.com/releases/tag/v1.7.0"),
            valid.copy(releaseUrl = release("1.6.0").releaseUrl),
            valid.copy(apkUrl = "https://example.com/update.apk"),
            valid.copy(checksumUrl = "https://example.com/update.apk.sha256"),
            valid.copy(
                apkUrl = "https://example.com/update.apk",
                checksumUrl = "https://example.com/update.apk.sha256",
            ),
        )
        for (candidate in invalid) {
            assertFalse(candidate.toString(), UpdatePolicy.shouldNotify(true, 8, "1.6.0", candidate, null))
        }
    }

    @Test
    fun notifiesOncePerVersionAndAllowsTheNextVersion() {
        val first = release("1.7.0")
        assertTrue(UpdatePolicy.shouldNotify(true, 8, "1.6.0", first, null))
        assertFalse(UpdatePolicy.shouldNotify(true, 8, "1.6.0", first, first.versionName))
        assertFalse(UpdatePolicy.shouldNotify(true, 8, "1.6.0", first.copy(versionCode = 9), first.versionName))
        assertTrue(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.8.0"), first.versionName))
    }

    @Test
    fun apiUsesVersionNameWhileManifestUsesVersionCode() {
        assertTrue(UpdatePolicy.shouldNotify(true, 100, "1.6.0", release("1.7.0"), null))
        assertFalse(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.7.0", 8), null))
        assertFalse(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.7.0", 7), null))
        assertTrue(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.5.0", 9), null))
        assertTrue(UpdatePolicy.shouldNotify(true, 8, "1.6.0", release("1.6.0", 9), null))
    }

    @Test
    fun noPreviousAttemptIsImmediatelyDue() {
        assertEquals(0L, UpdatePolicy.checkDelayMs(1_000L, 0L))
        assertEquals(0L, UpdatePolicy.checkDelayMs(1_000L, -1L))
        assertEquals(0L, UpdatePolicy.checkDelayMs(0L, 0L))
    }

    @Test
    fun checkIsDueAtExactlyTwentyFourHoursAndAfterwards() {
        val lastAttempt = 1_000L
        assertEquals(intervalMs, UpdatePolicy.checkDelayMs(lastAttempt, lastAttempt))
        assertEquals(intervalMs - 1, UpdatePolicy.checkDelayMs(lastAttempt + 1, lastAttempt))
        assertEquals(1L, UpdatePolicy.checkDelayMs(lastAttempt + intervalMs - 1, lastAttempt))
        assertEquals(0L, UpdatePolicy.checkDelayMs(lastAttempt + intervalMs, lastAttempt))
        assertEquals(0L, UpdatePolicy.checkDelayMs(lastAttempt + intervalMs + 1, lastAttempt))
        assertEquals(0L, UpdatePolicy.checkDelayMs(lastAttempt + 3 * intervalMs, lastAttempt))
    }

    @Test
    fun clockRollbackMakesCheckImmediatelyDue() {
        assertEquals(0L, UpdatePolicy.checkDelayMs(999L, 1_000L))
        assertEquals(0L, UpdatePolicy.checkDelayMs(0L, intervalMs))
        assertEquals(0L, UpdatePolicy.checkDelayMs(-1L, 1_000L))
    }
}
