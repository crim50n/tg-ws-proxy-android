package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ByteBudgetTest {
    @Test fun backpressureWakesAfterConsumerReleasesCredit() = runTest {
        val budget = ByteBudget(8)
        budget.acquire(8)
        var acquired = false
        val waiter = launch { budget.acquire(4); acquired = true }
        runCurrent()
        assertFalse(acquired)
        budget.release(4)
        runCurrent()
        assertTrue(acquired)
        waiter.join()
    }

    @Test fun cancellationDoesNotConsumeCredit() = runTest {
        val budget = ByteBudget(8)
        budget.acquire(8)
        val cancelled = launch { budget.acquire(8) }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        budget.release(8)
        budget.acquire(8)
    }
}
