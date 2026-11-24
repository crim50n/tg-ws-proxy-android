package dev.minios.tgwsproxy.proxy

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CfTransportPolicyTest {
    @Test fun tlsTimeoutAllowsPort80AndPreservesTheCooldownForOtherClients() = runTest {
        val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
        val attempted = mutableListOf<Boolean>()
        val result = policy.connect("first.example", true, 8_000) { secure, _ ->
            attempted += secure
            if (secure) throw SSLException("Wrapped timeout", SocketTimeoutException())
            "connected"
        }
        assertEquals(listOf(true, false), attempted)
        assertEquals("connected", result?.value)
        assertFalse(result!!.secure)
        assertFalse(policy.tlsAvailable("FIRST.EXAMPLE"))
        assertTrue(policy.tlsAvailable("second.example"))
        assertTrue("A failed TLS probe alone does not change preference", policy.prefersTls())

        attempted.clear()
        policy.connect("FIRST.EXAMPLE", true, 8_000) { secure, _ ->
            attempted += secure
            "connected by another client"
        }
        assertEquals(listOf(false), attempted)

        testScheduler.advanceTimeBy(30_000)
        assertTrue(policy.tlsAvailable("first.example"))
    }

    @Test fun handshakeAndConnectionErrorsDoNotTriggerPlaintext() = runTest {
        for (error in listOf(WsHandshakeError(403, "Forbidden", null), SSLException("TLS alert"), ConnectException())) {
            val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
            val attempted = mutableListOf<Boolean>()
            val result = policy.connect<String>("first.example", true, 8_000) { secure, _ ->
                attempted += secure
                throw error
            }
            assertNull(result)
            assertEquals(listOf(true), attempted)
            assertTrue(policy.tlsAvailable("first.example"))
        }
    }

    @Test fun explicitTlsOffDoesNotTryTlsEvenAfterPort80TimesOut() = runTest {
        val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
        val attempted = mutableListOf<Boolean>()
        assertNull(policy.connect<String>("example.test", false, 8_000) { secure, _ ->
            attempted += secure
            throw SocketTimeoutException()
        })
        assertEquals(listOf(false), attempted)
        assertTrue(policy.tlsAvailable("example.test"))
    }

    @Test fun routeFamiliesLearnIndependentlyAndNewRuntimeDoesNotInheritPreference() = runTest {
        val cf = CfTransportPolicy(clock = { testScheduler.currentTime })
        val workers = CfTransportPolicy(lastSuccessWins = true, clock = { testScheduler.currentTime })
        cf.recordSuccess(true)
        cf.recordSuccess(true)
        cf.recordSuccess(false)
        assertTrue(cf.prefersTls())
        workers.recordSuccess(true)
        workers.recordSuccess(false)
        assertFalse(workers.prefersTls())

        cf.recordSuccess(false)
        assertTrue("Cloudflare ties prefer TLS", cf.prefersTls())
        cf.recordSuccess(false)
        assertFalse(cf.prefersTls())
        val attempted = mutableListOf<Boolean>()
        val result = cf.connect("example.test", true, 8_000) { secure, _ ->
            attempted += secure
            if (!secure) throw SocketTimeoutException()
            "recovered on TLS"
        }
        assertEquals(listOf(false, true), attempted)
        assertTrue(result!!.secure)
        assertTrue(CfTransportPolicy().prefersTls())
        assertTrue(CfTransportPolicy(lastSuccessWins = true).prefersTls())
    }

    @Test fun concurrentClientsDoNotWaitOrDowngradeOnAProbeInFlight() = runTest {
        val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
        val hold = CompletableDeferred<Unit>()
        val first = async {
            policy.connect("example.test", true, 8_000) { secure, _ ->
                assertTrue(secure)
                hold.await()
                "connected"
            }
        }
        runCurrent()
        assertFalse(policy.tlsAvailable("example.test"))
        assertNull(policy.connect("example.test", true, 8_000) { _, _ -> error("Duplicate dial") })
        first.cancelAndJoin()
        assertTrue(policy.tlsAvailable("example.test"))
        assertTrue(policy.connect("example.test", true, 8_000) { secure, _ -> secure }!!.secure)
    }

    @Test fun oneDeadlineBoundsAllCandidatesAndTruncatedProbeDoesNotPoisonLaterClients() = runTest {
        val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
        val elapsed = mutableListOf<Long>()
        for (i in 1..32) {
            policy.connect<Unit>("example$i.test", true, 8_000) { _, timeout ->
                elapsed += timeout
                delay(timeout)
                throw SocketTimeoutException()
            }
        }
        assertEquals(listOf(3_000L, 3_000L, 2_000L), elapsed)
        assertEquals(8_000L, testScheduler.currentTime)
        assertFalse(policy.tlsAvailable("example1.test"))
        assertTrue(policy.tlsAvailable("example2.test"))
    }

    @Test fun wholeWorkerDomainTimeoutRequiresAllApplicableModesToActuallyTimeOut() = runTest {
        val policy = CfTransportPolicy(clock = { testScheduler.currentTime })
        var exhausted = 0
        suspend fun attempt(domain: String, allowTls: Boolean, budget: Long, error: (Boolean) -> Exception) {
            policy.connect<Unit>(domain, allowTls, testScheduler.currentTime + budget,
                onTimeoutsExhausted = { exhausted++ }) { secure, timeout ->
                delay(timeout)
                throw error(secure)
            }
        }
        attempt("partial.example", true, 4_000) { SocketTimeoutException() }
        // A prior TLS cooldown is not an attempt and the remaining HTTP timeout alone is insufficient.
        attempt("partial.example", true, 8_000) { SocketTimeoutException() }
        attempt("capacity.example", true, 8_000) { if (it) SocketTimeoutException() else TransportDialNotStarted() }
        assertEquals(0, exhausted)
        attempt("both.example", true, 8_000) { SocketTimeoutException() }
        attempt("plain.example", false, 8_000) { SocketTimeoutException() }
        assertEquals(2, exhausted)
    }

    @Test fun worker429ExpiresAtUtcMidnightWhileTransientFailuresLastOneMinute() {
        var now = 0L
        val tracker = WorkerCooldownTracker({ now }, { 2 * 86_400_000L - 4_000L + now })
        val domain = "worker.example"
        for (error in listOf(WsHandshakeError(403, "", null), WsHandshakeError(404, "", null),
            TransportDialNotStarted(), SocketTimeoutException(), kotlinx.coroutines.CancellationException())) {
            assertNull(tracker.markFailure(domain, error))
            assertFalse(tracker.isCoolingDown(domain))
        }
        assertEquals(60_000L, tracker.markFailure(domain, WsHandshakeError(503, "", null)))
        assertEquals(4_000L, tracker.markFailure(domain, WsHandshakeError(429, "", null)))
        assertNull(tracker.markFailure(domain, ConnectException()))
        now += 3_999
        assertTrue(tracker.isCoolingDown("WORKER.EXAMPLE"))
        now++
        assertFalse(tracker.isCoolingDown(domain))
        assertEquals(60_000L, tracker.markFailure(domain, ConnectException()))
        now += 59_999
        assertTrue(tracker.isCoolingDown(domain))
        now++
        assertFalse(tracker.isCoolingDown(domain))
    }

    @Test fun knownUpgradeRejectionTakesPrecedenceOverTheOuterDeadline() = runBlocking {
        val attempts = mutableListOf<Boolean>()
        val failures = mutableListOf<Exception>()
        val blocked = CountDownLatch(1)
        try {
            val policy = CfTransportPolicy()
            policy.connect<Unit>("example.test", true, transportClockMs() + 8_000,
                onFailure = { _, error -> failures += error },
            ) { secure, _ ->
                attempts += secure
                boundedTransportAttempt(500) { resources ->
                    resources.recordUpgradeRejection(WsHandshakeError(429, "Too Many Requests", null))
                    check(blocked.await(5, TimeUnit.SECONDS))
                }
            }
            assertEquals(listOf(true), attempts)
            assertEquals(429, (failures.single() as WsHandshakeError).statusCode)
        } finally {
            blocked.countDown()
        }
    }

    @Test fun expiredBlockingDialReleasesTheCallerAndDisposesALateResult() {
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val restart = CountDownLatch(1)
            val closed = CompletableDeferred<Unit>()
            val discarded = CompletableDeferred<Unit>()
            try {
                val outcome = async {
                    runCatching {
                        boundedTransportAttempt(500, discard = { _: String -> discarded.complete(Unit) }) { resources ->
                            resources.register(AutoCloseable { closed.complete(Unit) })
                            entered.complete(Unit)
                            check(restart.await(5, TimeUnit.SECONDS))
                            "late"
                        }
                    }
                }
                withTimeout(3_000) { entered.await() }
                assertTrue(withTimeout(3_000) { outcome.await() }.exceptionOrNull() is SocketTimeoutException)
                withTimeout(3_000) { closed.await() }
                assertFalse("Application did not wait for blocked system I/O", discarded.isCompleted)
                restart.countDown()
                withTimeout(3_000) { discarded.await() }
            } finally {
                restart.countDown()
            }
        }
    }
}
