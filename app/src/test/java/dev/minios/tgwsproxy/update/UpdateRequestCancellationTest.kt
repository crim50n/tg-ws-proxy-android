package dev.minios.tgwsproxy.update

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateRequestCancellationTest {
    @Test
    fun cancellationWhileCallbackIsPendingCancelsCall() = runTest {
        val call = FakeCall()
        val request = async { call.awaitResponse() }
        runCurrent()
        assertTrue(call.isExecuted())
        assertFalse(call.isCanceled())
        assertFalse(request.isCompleted)

        request.cancel()
        runCurrent()

        assertTrue(request.isCancelled)
        assertEquals(1, call.cancelCount)
    }

    @Test
    fun responseArrivingAfterCancellationIsClosed() = runTest {
        val call = FakeCall()
        val body = TrackingResponseBody()
        var delivered = false
        val request = launch {
            call.awaitResponse().use { delivered = true }
        }
        runCurrent()
        request.cancel()
        runCurrent()

        call.respond(body)
        runCurrent()

        assertFalse(delivered)
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun cancellationAfterCallbackBeforeDispatchClosesUndeliveredResponse() = runTest {
        val call = FakeCall()
        val body = TrackingResponseBody()
        var delivered = false
        val request = launch {
            call.awaitResponse().use { delivered = true }
        }
        runCurrent()

        // The test dispatcher queues delivery, leaving the cancellation race deterministic.
        call.respond(body)
        assertFalse(delivered)
        assertEquals(0, body.closeCount)
        request.cancel()
        runCurrent()

        assertTrue(request.isCancelled)
        assertFalse(delivered)
        assertEquals(1, call.cancelCount)
        assertEquals(1, body.closeCount)
    }

    @Test
    fun successfulResponseRemainsOpenUntilCallerClosesIt() = runTest {
        val call = FakeCall()
        val body = TrackingResponseBody()
        val request = async { call.awaitResponse() }
        runCurrent()
        val response = call.respond(body)
        runCurrent()

        val delivered = request.await()
        assertSame(response, delivered)
        assertEquals(0, call.cancelCount)
        assertEquals(0, body.closeCount)
        delivered.close()
        assertEquals(1, body.closeCount)
    }

    @Test
    fun ioFailureIsPropagatedWithoutCancellingCall() = runTest {
        val call = FakeCall()
        val failure = IOException("request failed")
        var observed: IOException? = null
        val request = launch {
            try {
                call.awaitResponse().use { error("Expected an IO failure") }
            } catch (e: IOException) {
                observed = e
            }
        }
        runCurrent()
        call.fail(failure)
        runCurrent()

        assertTrue(request.isCompleted)
        assertFalse(request.isCancelled)
        assertEquals(IOException::class.java, observed?.javaClass)
        assertEquals(failure.message, observed?.message)
        assertEquals(0, call.cancelCount)
    }

    @Test
    fun failureAfterCancellationDoesNotResumeCaller() = runTest {
        val call = FakeCall()
        var observed: IOException? = null
        val request = launch {
            try {
                call.awaitResponse().use { error("Expected cancellation") }
            } catch (e: IOException) {
                observed = e
            }
        }
        runCurrent()
        request.cancel()
        runCurrent()
        call.fail(IOException("Canceled"))
        runCurrent()

        assertTrue(request.isCancelled)
        assertNull(observed)
        assertEquals(1, call.cancelCount)
    }

    @Test
    fun cancellingParentDuringBodyReadCancelsCallAndClosesResponse() {
        val readStarted = CountDownLatch(1)
        val readReleased = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val blockCompleted = AtomicBoolean(false)
        val observed = AtomicReference<IOException?>()
        val call = FakeCall { readReleased.countDown() }
        val body = TrackingResponseBody {
            readStarted.countDown()
            check(readReleased.await(5, TimeUnit.SECONDS)) { "Body read was not released" }
            if (call.isCanceled()) throw IOException("Canceled during body read")
        }
        val parent = Job()
        val request = CoroutineScope(parent + Dispatchers.IO).launch {
            try {
                call.useResponse { response ->
                    val content = requireNotNull(response.body).source().readUtf8()
                    blockCompleted.set(true)
                    content
                }
            } catch (e: IOException) {
                observed.set(e)
            }
        }
        request.invokeOnCompletion { completed.countDown() }
        try {
            assertTrue("Call was not enqueued", call.enqueued.await(5, TimeUnit.SECONDS))
            call.respond(body)
            assertTrue("Body read did not start", readStarted.await(5, TimeUnit.SECONDS))
            assertFalse(request.isCompleted)
            assertEquals(0, call.cancelCount)
            assertEquals(0, body.closeCount)

            parent.cancel()

            assertTrue("Cancellation did not release the body read", readReleased.await(5, TimeUnit.SECONDS))
            assertTrue(call.isCanceled())
            assertTrue("Request did not finish", completed.await(5, TimeUnit.SECONDS))
            assertTrue(request.isCancelled)
            assertFalse(blockCompleted.get())
            assertEquals("Canceled during body read", observed.get()?.message)
            assertEquals(1, body.closeCount)
        } finally {
            parent.cancel()
            readReleased.countDown()
            assertTrue("Request cleanup timed out", completed.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun useResponseReturnsBlockResultAndDisposesBody() = runBlocking {
        val call = FakeCall()
        val body = TrackingResponseBody()
        val request = async(Dispatchers.IO) {
            call.useResponse { response ->
                assertEquals(0, body.closeCount)
                "consumed: ${requireNotNull(response.body).source().readUtf8()}"
            }
        }
        try {
            assertTrue("Call was not enqueued", call.enqueued.await(5, TimeUnit.SECONDS))
            call.respond(body)

            assertEquals("consumed: {}", withTimeout(5_000) { request.await() })
            assertFalse(request.isCancelled)
            assertEquals(1, body.closeCount)
        } finally {
            request.cancel()
        }
    }

    private class FakeCall(private val onCancel: () -> Unit = {}) : Call {
        private val request = Request.Builder().url("https://example.com/update.json").build()
        private lateinit var callback: Callback
        @Volatile
        private var executed = false
        private val cancellations = AtomicInteger()
        val cancelCount: Int get() = cancellations.get()
        val enqueued = CountDownLatch(1)

        override fun request(): Request = request
        override fun execute(): Response = error("Only asynchronous calls are supported")
        override fun enqueue(responseCallback: Callback) {
            check(!executed)
            executed = true
            callback = responseCallback
            enqueued.countDown()
        }
        override fun cancel() {
            cancellations.incrementAndGet()
            onCancel()
        }
        override fun isExecuted(): Boolean = executed
        override fun isCanceled(): Boolean = cancelCount > 0
        override fun timeout(): Timeout = Timeout()
        override fun clone(): Call = FakeCall()

        fun respond(body: ResponseBody): Response {
            val response = Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build()
            callback.onResponse(this, response)
            return response
        }

        fun fail(failure: IOException) = callback.onFailure(this, failure)
    }

    private class TrackingResponseBody(private val beforeRead: () -> Unit = {}) : ResponseBody() {
        @Volatile
        var closeCount = 0
            private set
        private val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                beforeRead()
                return super.read(sink, byteCount)
            }

            override fun close() {
                closeCount++
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = 2
        override fun source(): BufferedSource = source
    }
}
