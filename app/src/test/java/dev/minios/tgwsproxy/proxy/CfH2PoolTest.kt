package dev.minios.tgwsproxy.proxy

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CfH2PoolTest {
    private fun tracker() = ConnectivityTracker().apply { updateNetwork(1, NetworkStatus.ONLINE) }
    private fun packet(id: Int, size: Int = 24): ByteArray = ByteArray(size) { 1 }.also {
        NativeHttpCodec.bytesLE(id).copyInto(it, 8)
    }
    private fun id(body: ByteArray): Int = NativeHttpCodec.intLE(body.copyOfRange(8, 12))
    private fun CountDownLatch.awaitChecked() = check(await(5, TimeUnit.SECONDS)) { "Test interleaving timed out" }

    private class FakeCall(
        private val request: Request,
        private val perform: (FakeCall) -> Response = { it.response() },
    ) : Call {
        private val cancelled = AtomicBoolean()
        private val executed = AtomicBoolean()
        override fun request(): Request = request
        override fun execute(): Response {
            check(executed.compareAndSet(false, true))
            return perform(this)
        }
        fun response(): Response = Response.Builder().request(request).protocol(Protocol.HTTP_2)
            .code(200).message("OK").body(ByteArray(0).toResponseBody()).build()
        override fun enqueue(responseCallback: Callback): Unit = error("Synchronous test call only")
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = FakeCall(request, perform)
    }

    @Test fun stalePreflightFailureCannotRemoveOrCloseReplacementEpochLane() {
        val tracker = tracker()
        val oldStarted = CountDownLatch(1)
        val finishOld = CountDownLatch(1)
        val preflights = AtomicInteger()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(callFactory = { request ->
            FakeCall(request) { call ->
                if (preflights.incrementAndGet() == 1) {
                    oldStarted.countDown()
                    finishOld.awaitChecked()
                    throw IOException("Old epoch preflight completed late")
                }
                call.response()
            }
        }))
        val executor = Executors.newSingleThreadExecutor()
        val oldEpoch = tracker.state.value.epoch
        val old = executor.submit<CfH2Pool.Channel?> { pool.open(2, listOf("example.test"), oldEpoch) }
        try {
            oldStarted.awaitChecked()
            tracker.updateNetwork(2, NetworkStatus.ONLINE)
            pool.reset()
            val current = pool.open(2, listOf("example.test"), tracker.state.value.epoch)!!
            finishOld.countDown()
            assertNull(old.get(5, TimeUnit.SECONDS))
            assertFalse("Stale catch must not close the replacement", current.closed)
            val reused = pool.open(2, listOf("example.test"), tracker.state.value.epoch)!!
            assertFalse(reused.closed)
            assertEquals("Replacement must remain in the lane map", 2, preflights.get())
        } finally {
            finishOld.countDown()
            old.cancel(true)
            pool.reset()
            executor.shutdownNow()
        }
    }

    @Test fun callRegisteredAfterCloseSnapshotCancelsBeforeBlockingExecute() = runBlocking {
        val tracker = tracker()
        val creatingCall = CountDownLatch(1)
        val allowRegistration = CountDownLatch(1)
        val executed = CountDownLatch(1)
        val cancelledBeforeExecute = AtomicBoolean()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            callFactory = { request ->
                creatingCall.countDown()
                allowRegistration.awaitChecked()
                FakeCall(request) { call ->
                    cancelledBeforeExecute.set(call.isCanceled())
                    executed.countDown()
                    throw IOException("Controlled cancelled call")
                }
            },
            afterCloseSnapshot = {
                // The channel's job is deliberately still active at this point.
                allowRegistration.countDown()
                executed.awaitChecked()
            },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            channel.send(packet(1))
            creatingCall.awaitChecked()
            assertTrue(channel.calls.isEmpty())
            assertFalse(lane.closed)
            val jobs = channel.pendingRequests().map { it.job }
            channel.close()
            assertTrue("Owner closure, not lane closure or job cancellation, must cancel the call",
                cancelledBeforeExecute.get())
            jobs.joinAll()
            assertTrue(channel.calls.isEmpty())
            assertEquals(64, lane.slots.availablePermits)
            assertEquals(32 * 1024 * 1024, lane.uploads.availableBytes)
        } finally {
            allowRegistration.countDown()
            lane.close()
        }
    }

    private data class Started(
        val body: ByteArray,
        val pending: CfH2Pool.PendingRequest,
        val response: CompletableDeferred<ByteArray>,
    )

    @Test fun saturatedHeaderWaitersRotateWithoutAdmittingNinthPacketToHistoryEarly() = runTest {
        val tracker = tracker()
        val started = CopyOnWriteArrayList<Started>()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime) },
            post = { body, request ->
                request.sentAt = TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
                val response = CompletableDeferred<ByteArray>()
                started.add(Started(body, request, response))
                response.await()
            },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            repeat(8) { channel.send(packet(it + 1)) }
            runCurrent()
            val ninth = async { channel.send(packet(9)) }
            runCurrent()
            assertFalse(ninth.isCompleted)
            assertEquals((1..8).toList(), channel.replayBodies().map(::id))
            advanceTimeBy(4_000)
            val recovery = async { channel.recoverOnce() }
            runCurrent()
            assertTrue("Recovery must not wait behind the native uploader", recovery.isCompleted)
            recovery.await()
            assertTrue("Rotation must free capacity for the waiting native packet", ninth.isCompleted)
            ninth.await()
            assertTrue(channel.replayBodies().any { id(it) == 9 })
            assertTrue(started.any { it.pending.replay && id(it.body) == 1 })
            assertTrue(started.filter { it.pending.rotating }.all { it.pending.job.isCancelled })
            assertTrue(channel.pendingRequests().size <= 8)

            // Empty HTTP replies do not reset the retry allowance. Older outstanding
            // packets, not the ninth upload, remain eligible after the first three tries.
            repeat(4) {
                started.filter { it.pending.replay && !it.pending.job.isCompleted }
                    .forEach { it.response.complete(ByteArray(0)) }
                runCurrent()
                advanceTimeBy(2_000)
                channel.recoverOnce()
                runCurrent()
            }
            assertEquals(3, started.count { it.pending.replay && id(it.body) == 1 })
            assertTrue("Retired second request must also be recovered",
                started.any { it.pending.replay && id(it.body) == 2 })
            assertTrue(channel.pendingRequests().count { it.replay } <= 2)
            assertFalse(channel.closed)
        } finally {
            channel.close()
            runCurrent()
            assertEquals(64, lane.slots.availablePermits)
            assertEquals(32 * 1024 * 1024, lane.uploads.availableBytes)
            lane.close()
        }
    }

    @Test fun recoveryNeverRotatesRequestsWhoseResponseHeadersHaveStarted() = runTest {
        val tracker = tracker()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime) },
            post = { _, request ->
                request.sentAt = TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
                request.receivingHeaders = true
                awaitCancellation()
            },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            repeat(8) { channel.send(packet(it + 1)) }
            runCurrent()
            val ninth = async { channel.send(packet(9)) }
            runCurrent()
            advanceTimeBy(4_000)
            channel.recoverOnce()
            assertFalse(ninth.isCompleted)
            assertEquals(8, channel.pendingRequests().size)
            assertTrue(channel.pendingRequests().none { it.rotating || it.replay })
            ninth.cancelAndJoin()
        } finally {
            channel.close()
            runCurrent()
            assertEquals(64, lane.slots.availablePermits)
            lane.close()
        }
    }

    @Test fun recoveryUsesAtMostTwoNonblockingReplaySlots() = runTest {
        val tracker = tracker()
        val started = CopyOnWriteArrayList<CfH2Pool.PendingRequest>()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime) },
            post = { _, request ->
                request.sentAt = TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
                started.add(request)
                awaitCancellation()
            },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            repeat(8) { channel.send(packet(it + 1)) }
            runCurrent()
            repeat(3) {
                advanceTimeBy(4_000)
                channel.recoverOnce()
                runCurrent()
            }
            assertEquals(2, channel.pendingRequests().count { it.replay })
            assertEquals(2, started.count { it.rotating })
            assertEquals(10, started.size)
            assertEquals(8, channel.pendingRequests().size)
        } finally {
            channel.close()
            runCurrent()
            assertEquals(64, lane.slots.availablePermits)
            lane.close()
        }
    }

    @Test fun saturatedLaneRotatesEligibleOtherChannelForWaitingNativeAdmission() = runTest {
        val tracker = tracker()
        val started = CopyOnWriteArrayList<CfH2Pool.PendingRequest>()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime) },
            post = { _, request ->
                request.sentAt = TimeUnit.MILLISECONDS.toNanos(testScheduler.currentTime)
                started.add(request)
                awaitCancellation()
            },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channels = List(9) { pool.Channel(lane, 2) }
        try {
            channels.take(8).forEachIndexed { index, channel ->
                repeat(8) { channel.send(packet(index * 8 + it + 1)) }
            }
            runCurrent()
            val waiting = async { channels.last().send(packet(100)) }
            runCurrent()
            assertFalse(waiting.isCompleted)
            assertTrue(channels.last().replayBodies().isEmpty())
            advanceTimeBy(4_000)
            channels.last().recoverOnce()
            runCurrent()
            assertTrue(waiting.isCompleted)
            waiting.await()
            assertEquals(listOf(100), channels.last().replayBodies().map(::id))
            assertEquals(1, started.count { it.rotating })
            assertEquals(64, channels.sumOf { it.pendingRequests().size })
        } finally {
            channels.forEach { it.close() }
            runCurrent()
            assertEquals(64, lane.slots.availablePermits)
            assertEquals(32 * 1024 * 1024, lane.uploads.availableBytes)
            lane.close()
        }
    }

    @Test fun cancelledLaneAdmissionReturnsPartialReservationsAndNeverEntersHistory() = runTest {
        val tracker = tracker()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            post = { _, _ -> awaitCancellation() },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            repeat(64) { lane.slots.acquire() }
            val blocked = async { channel.send(packet(99)) }
            runCurrent()
            assertTrue(channel.replayBodies().isEmpty())
            assertTrue(channel.pendingRequests().isEmpty())
            channel.recoverOnce()
            blocked.cancelAndJoin()
            repeat(64) { lane.slots.release() }
            repeat(8) { channel.send(packet(it + 1)) }
            runCurrent()
            assertEquals(8, channel.pendingRequests().size)
            assertFalse(channel.replayBodies().any { id(it) == 99 })
        } finally {
            channel.close()
            runCurrent()
            assertEquals(64, lane.slots.availablePermits)
            assertEquals(32 * 1024 * 1024, lane.uploads.availableBytes)
            lane.close()
        }
    }

    @Test fun replayHistoryIsBoundedAndExcludesPlainAndOversizedPackets() = runTest {
        val tracker = tracker()
        val pool = CfH2Pool(tracker, CfH2Pool.Hooks(
            dispatcher = StandardTestDispatcher(testScheduler),
            post = { _, _ -> ByteArray(0) },
        ))
        val lane = pool.Lane("kws2.example.test", tracker.state.value.epoch)
        val channel = pool.Channel(lane, 2)
        try {
            repeat(20) {
                channel.send(packet(it + 1, 4096))
                runCurrent()
            }
            assertEquals((5..20).toList(), channel.replayBodies().map(::id))
            assertEquals(64 * 1024, channel.replayBodies().sumOf { it.size })
            channel.send(ByteArray(24))
            channel.send(packet(100, 64 * 1024 + 4))
            runCurrent()
            assertEquals((5..20).toList(), channel.replayBodies().map(::id))
        } finally {
            channel.close()
            runCurrent()
            lane.close()
        }
    }
}
