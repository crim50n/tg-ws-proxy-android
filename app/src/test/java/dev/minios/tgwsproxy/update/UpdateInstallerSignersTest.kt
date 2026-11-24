package dev.minios.tgwsproxy.update

import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallerSignersTest {
    private fun certificate(id: Int): ByteArray = byteArrayOf(0x30, id.toByte())

    @Suppress("DEPRECATION")
    @Test
    fun requestsOnlyLegacySignaturesOnApi26And27() {
        for (sdkInt in 26..27) {
            assertEquals("API $sdkInt signing flags", PackageManager.GET_SIGNATURES, signingQueryFlags(sdkInt))
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun requestsModernSignersAndEnablesArchiveCertificateCollectionOnApi28And29() {
        for (sdkInt in 28..29) {
            val flags = signingQueryFlags(sdkInt)
            assertTrue(
                "API $sdkInt must request modern signing information",
                (flags and PackageManager.GET_SIGNING_CERTIFICATES) != 0,
            )
            assertTrue(
                "API $sdkInt must request legacy signatures to trigger archive certificate collection",
                (flags and PackageManager.GET_SIGNATURES) != 0,
            )
        }
    }

    @Test
    fun matchesFullModernSignerSetRegardlessOfOrderOrLegacyValues() {
        verifyMatchingApkSigners(
            archive = ApkSigners(listOf(certificate(1), certificate(2)), listOf(certificate(3))),
            installed = ApkSigners(listOf(certificate(2), certificate(1)), listOf(certificate(4))),
        )
    }

    @Test
    fun missingModernArchiveSignersFallBackToLegacy() {
        verifyMatchingApkSigners(
            archive = ApkSigners(null, listOf(certificate(1))),
            installed = ApkSigners(listOf(certificate(1)), listOf(certificate(2))),
        )
    }

    @Test
    fun emptyModernSignersFallBackToLegacyOnBothSides() {
        verifyMatchingApkSigners(
            archive = ApkSigners(emptyList(), listOf(certificate(1))),
            installed = ApkSigners(emptyList(), listOf(certificate(1))),
        )
    }

    @Test
    fun rejectsModernMismatchEvenWhenLegacySignersMatch() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verifyMatchingApkSigners(
                archive = ApkSigners(listOf(certificate(1), certificate(2)), listOf(certificate(1))),
                installed = ApkSigners(listOf(certificate(1)), listOf(certificate(1))),
            )
        }
        assertEquals("APK signing certificate mismatch", error.message)
    }

    @Test
    fun rejectsLegacyMismatchWhenModernSignersAreUnavailable() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verifyMatchingApkSigners(
                archive = ApkSigners(null, listOf(certificate(1))),
                installed = ApkSigners(emptyList(), listOf(certificate(2))),
            )
        }
        assertEquals("APK signing certificate mismatch", error.message)
    }

    @Test
    fun reportsMissingArchiveCertificates() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verifyMatchingApkSigners(
                archive = ApkSigners(null, null),
                installed = ApkSigners(listOf(certificate(1)), null),
            )
        }
        assertEquals("Downloaded APK signing certificate is unavailable", error.message)
    }

    @Test
    fun reportsMissingInstalledCertificates() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            verifyMatchingApkSigners(
                archive = ApkSigners(listOf(certificate(1)), null),
                installed = ApkSigners(emptyList(), emptyList()),
            )
        }
        assertEquals("Installed package signing certificate is unavailable", error.message)
    }

    @Test
    fun rejectsMalformedModernSignerEntriesRatherThanFallingBack() {
        for (malformed in listOf(null, byteArrayOf())) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                verifyMatchingApkSigners(
                    archive = ApkSigners(listOf(certificate(1), malformed), listOf(certificate(1))),
                    installed = ApkSigners(listOf(certificate(1)), listOf(certificate(1))),
                )
            }
            assertEquals("Downloaded APK signing certificate is invalid", error.message)
        }
    }
}
