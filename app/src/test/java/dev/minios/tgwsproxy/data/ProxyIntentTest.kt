package dev.minios.tgwsproxy.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyIntentTest {
    @Test
    fun migrationDefaultsToFalseAndRemovesLegacy() {
        val fresh = mutablePreferencesOf()
        assertFalse(fresh.resolveProxyEnabled())
        assertEquals(false, fresh[PROXY_ENABLED])
        for (legacy in listOf(false, true)) {
            val prefs = mutablePreferencesOf(LEGACY_START_ON_BOOT to legacy)
            assertEquals(legacy, prefs.resolveProxyEnabled())
            assertNull(prefs[LEGACY_START_ON_BOOT])
            assertEquals(legacy, prefs.resolveProxyEnabled())
        }
    }

    @Test
    fun existingFlagAndExplicitCommandsWinMigration() {
        for (enabled in listOf(false, true)) {
            val migrated = mutablePreferencesOf(PROXY_ENABLED to enabled, LEGACY_START_ON_BOOT to !enabled)
            assertEquals(enabled, migrated.resolveProxyEnabled())
            assertNull(migrated[LEGACY_START_ON_BOOT])
            val explicit = mutablePreferencesOf(LEGACY_START_ON_BOOT to !enabled)
            assertEquals(enabled, explicit.resolveProxyEnabled(explicit = enabled))
            assertEquals(enabled, explicit.resolveProxyEnabled())
            assertNull(explicit[LEGACY_START_ON_BOOT])
        }
    }

    @Test
    fun commandsRemainOrderedAcrossSuspendingDurableWrites() = runTest {
        val events = mutableListOf<String>()
        val write = CompletableDeferred<Unit>()
        val commands = ProxyCommands(backgroundScope) { throw AssertionError(it) }
        commands.submit {
            write.await()
            events += "persist start"
            events += "launch"
        }
        val stop = commands.submit {
            events += "persist stop"
            events += "teardown"
        }
        runCurrent()
        assertTrue(events.isEmpty())
        write.complete(Unit)
        stop.await()
        assertEquals(listOf("persist start", "launch", "persist stop", "teardown"), events)
    }

    @Test
    fun delayedResumeReadsLatestIntentAndNeverWritesStart() = runTest {
        val prefs = mutablePreferencesOf(LEGACY_START_ON_BOOT to true)
        var launches = 0
        val commands = ProxyCommands(backgroundScope) { throw AssertionError(it) }
        commands.submit { prefs.resolveProxyEnabled(explicit = false) }
        commands.submit { if (prefs.resolveProxyEnabled()) launches++ }.await()
        assertEquals(0, launches)
        commands.submit { prefs.resolveProxyEnabled(explicit = true) }
        commands.submit { if (prefs.resolveProxyEnabled()) launches++ }.await()
        assertEquals(1, launches)
        assertTrue(prefs[PROXY_ENABLED]!!)
    }

    @Test
    fun callerCancellationDoesNotCancelAcceptedCommandAndFailureDoesNotPoisonQueue() = runTest {
        val failures = mutableListOf<Exception>()
        val commands = ProxyCommands(backgroundScope, failures::add)
        var enabled = false
        commands.submit { enabled = true }.cancel()
        commands.submit { throw java.io.IOException("write failed") }
        commands.submit { assertTrue(enabled) }.await()
        assertEquals(1, failures.size)
    }

    @Test
    fun failedPersistenceDoesNotDispatchOrRetry() = runTest {
        val failures = mutableListOf<Exception>()
        val commands = ProxyCommands(backgroundScope, failures::add)
        var writes = 0
        var dispatches = 0
        suspend fun persist() {
            writes++
            throw java.io.IOException("disk unavailable")
        }
        commands.submit {
            persist()
            dispatches++
        }
        commands.submit { }.await()
        assertEquals(1, writes)
        assertEquals(0, dispatches)
        assertEquals(1, failures.size)
    }

    @Test
    fun acceptedStartAfterStopIsNotDroppedWhileTeardownSuspends() = runTest {
        val teardown = CompletableDeferred<Unit>()
        val commands = ProxyCommands(backgroundScope) { throw AssertionError(it) }
        var enabled = true
        var launched = false
        commands.submit { enabled = false }
        val start = commands.submit {
            enabled = true
            teardown.await()
            launched = true
        }
        runCurrent()
        assertTrue(enabled)
        assertFalse(launched)
        teardown.complete(Unit)
        start.await()
        assertTrue(launched)
    }
}
