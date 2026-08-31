package dev.minios.tgwsproxy.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerProfileMigrationTest {
    @Test
    fun freshInstallUsesEfficientDefaultsWithoutNotice() {
        val migration = resolvePowerProfileMigration(
            existingInstall = false,
            current = LEGACY_DEFAULT_POWER_PROFILE,
        )

        assertEquals(
            BATTERY_SAVING_DEFAULT_POWER_PROFILE,
            migration.profile,
        )
        assertFalse(migration.showNotice)
    }

    @Test
    fun upgradedLegacyDefaultsAreMigratedWithNotice() {
        val migration = resolvePowerProfileMigration(
            existingInstall = true,
            current = LEGACY_DEFAULT_POWER_PROFILE,
        )

        assertEquals(BATTERY_SAVING_DEFAULT_POWER_PROFILE, migration.profile)
        assertTrue(migration.showNotice)
    }

    @Test
    fun anyManualPowerChangePreservesTheWholeProfileWithoutNotice() {
        val manualProfiles = listOf(
            LEGACY_DEFAULT_POWER_PROFILE.copy(keepCpuAwake = false),
            LEGACY_DEFAULT_POWER_PROFILE.copy(preconnectWebSockets = false),
            LEGACY_DEFAULT_POWER_PROFILE.copy(routeProbesEnabled = false),
            LEGACY_DEFAULT_POWER_PROFILE.copy(cfDomainRefreshEnabled = false),
            LEGACY_DEFAULT_POWER_PROFILE.copy(webSocketPingIntervalSeconds = 90),
        )

        manualProfiles.forEach { profile ->
            val migration = resolvePowerProfileMigration(existingInstall = true, current = profile)
            assertEquals(profile, migration.profile)
            assertFalse(migration.showNotice)
        }
    }
}
