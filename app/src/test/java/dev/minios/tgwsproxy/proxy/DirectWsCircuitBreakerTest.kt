package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectWsCircuitBreakerTest {
    @Test
    fun detectsRequestedMediaWithoutMeaningfulDownstreamData() {
        assertTrue(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                pendingBytesUp = 1_945L,
                pendingBytesDown = 1_335L,
                now = 17_000L,
            )
        )
    }

    @Test
    fun ignoresSmallRequestsAndMeaningfulMediaResponses() {
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                pendingBytesUp = 420L,
                pendingBytesDown = 205L,
                now = 61_000L,
            )
        )
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                pendingBytesUp = 1_945L,
                pendingBytesDown = 4_096L,
                now = 61_000L,
            )
        )
    }

    @Test
    fun firstStallTemporarilyBlocksOnlyAffectedSlot() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }

        val first = breaker.recordStall(dc = 2, isMedia = true)
        assertEquals(1, first.strikes)
        assertTrue(first.activated)
        assertEquals(DirectWsCircuitBreaker.RETRY_BLOCK_DURATION_MS, first.blockedForMs)
        assertTrue(breaker.isBlocked(dc = 2, isMedia = true))
        assertFalse(breaker.isBlocked(dc = 2, isMedia = false))
        assertFalse(breaker.isBlocked(dc = 4, isMedia = true))

        now += first.blockedForMs
        assertFalse(breaker.isBlocked(dc = 2, isMedia = true))
    }

    @Test
    fun secondStallAfterDirectRetryActivatesLongOverride() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        val first = breaker.recordStall(dc = 4, isMedia = true)
        now += first.blockedForMs

        val second = breaker.recordStall(dc = 4, isMedia = true)

        assertEquals(2, second.strikes)
        assertTrue(second.activated)
        assertTrue(second.blockedForMs > DirectWsCircuitBreaker.RETRY_BLOCK_DURATION_MS)
        assertTrue(breaker.isBlocked(dc = 4, isMedia = true))
    }

    @Test
    fun responsiveDirectRetryClearsFirstStrike() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }

        val first = breaker.recordStall(dc = 4, isMedia = true)
        now += first.blockedForMs
        breaker.recordResponsive(dc = 4, isMedia = true)

        assertEquals(1, breaker.recordStall(dc = 4, isMedia = true).strikes)
    }

    @Test
    fun activeOverrideSurvivesOtherResponsiveSessionAndExpires() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        val first = breaker.recordStall(dc = 2, isMedia = true)
        now += first.blockedForMs
        val activated = breaker.recordStall(dc = 2, isMedia = true)

        breaker.recordResponsive(dc = 2, isMedia = true)
        assertTrue(breaker.isBlocked(dc = 2, isMedia = true))

        now += activated.blockedForMs
        assertFalse(breaker.isBlocked(dc = 2, isMedia = true))
    }

    @Test
    fun oldStrikeDoesNotActivateOverride() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        breaker.recordStall(dc = 2, isMedia = true)

        now += DirectWsCircuitBreaker.STRIKE_WINDOW_MS + 1

        val result = breaker.recordStall(dc = 2, isMedia = true)
        assertEquals(1, result.strikes)
        assertEquals(DirectWsCircuitBreaker.RETRY_BLOCK_DURATION_MS, result.blockedForMs)
    }
}
