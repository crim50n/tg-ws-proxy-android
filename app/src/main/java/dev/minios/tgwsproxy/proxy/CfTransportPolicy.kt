package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

internal fun transportClockMs(): Long = System.nanoTime() / 1_000_000

internal fun isTransportTimeout(error: Exception): Boolean =
    generateSequence<Throwable>(error) { it.cause }.any { it is SocketTimeoutException }

internal class TransportDialNotStarted : IOException("Upstream dial could not start before its deadline")

private const val UTC_DAY_MS = 86_400_000L
private const val WORKER_FAILURE_COOLDOWN_MS = 60_000L

/** Whole-domain suppression for Workers, independent of per-mode timeout admission. */
internal class WorkerCooldownTracker(
    private val clock: () -> Long = ::transportClockMs,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private data class Cooldown(val until: Long, val dailyLimit: Boolean)
    private val retryAfter = mutableMapOf<String, Cooldown>()

    @Synchronized
    fun isCoolingDown(domain: String): Boolean {
        val key = domain.lowercase(Locale.ROOT)
        val cooldown = retryAfter[key] ?: return false
        if (cooldown.until > clock()) return true
        retryAfter.remove(key)
        return false
    }

    @Synchronized
    fun markFailure(domain: String, error: Exception): Long? {
        val rateLimited = error is WsHandshakeError && error.statusCode == 429
        val delay = when {
            rateLimited -> UTC_DAY_MS - Math.floorMod(wallClock(), UTC_DAY_MS)
            error is WsHandshakeError && error.statusCode in 500..599 -> WORKER_FAILURE_COOLDOWN_MS
            error is IOException && error !is TransportDialNotStarted && !isTransportTimeout(error) -> WORKER_FAILURE_COOLDOWN_MS
            else -> return null
        }
        return mark(domain, delay, dailyLimit = rateLimited)
    }

    @Synchronized
    fun markTimeoutsExhausted(domain: String): Long? = mark(domain, WORKER_FAILURE_COOLDOWN_MS)

    private fun mark(domain: String, delay: Long, dailyLimit: Boolean = false): Long? {
        val key = domain.lowercase(Locale.ROOT)
        val now = clock()
        val previous = retryAfter[key]
        if (previous != null && previous.until > now && (!dailyLimit || previous.dailyLimit)) return null
        retryAfter[key] = Cooldown(now + delay, dailyLimit)
        return delay
    }
}

/** One policy per route family and network epoch. Never waits for another client's dial. */
internal class CfTransportPolicy(
    private val lastSuccessWins: Boolean = false,
    private val clock: () -> Long = ::transportClockMs,
    private val timeoutCooldownMs: Long = 30_000,
    private val modeTimeoutMs: Long = 3_000,
) {
    private data class Key(val domain: String, val secure: Boolean)
    private enum class Unavailable { BUSY, TIMEOUT }
    internal data class Connected<T>(val value: T, val secure: Boolean)

    private val lock = Any()
    private val inFlight = mutableSetOf<Key>()
    private val retryAfter = mutableMapOf<Key, Long>()
    private var tlsSuccesses = 0L
    private var httpSuccesses = 0L
    private var lastSuccessfulMode = true

    fun prefersTls(): Boolean = synchronized(lock) {
        if (lastSuccessWins) lastSuccessfulMode else tlsSuccesses >= httpSuccesses
    }

    fun recordSuccess(secure: Boolean) = synchronized(lock) {
        lastSuccessfulMode = secure
        if (secure) tlsSuccesses++ else httpSuccesses++
    }

    fun tlsAvailable(domain: String): Boolean = synchronized(lock) {
        val key = key(domain, secure = true)
        key !in inFlight && retryAfter[key]?.let { it > clock() } != true
    }

    suspend fun <T : Any> connect(
        domain: String,
        allowTls: Boolean,
        deadline: Long,
        onSkipped: () -> Unit = {},
        onFailure: (Boolean, Exception) -> Unit = { _, _ -> },
        onTimeoutsExhausted: () -> Unit = {},
        dial: suspend (secure: Boolean, timeoutMs: Long) -> T,
    ): Connected<T>? {
        val firstSecure = allowTls && prefersTls()
        val modeCount = if (allowTls) 2 else 1
        var fullTimeouts = 0
        for (index in 0 until modeCount) {
            currentCoroutineContext().ensureActive()
            val secure = if (index == 0) firstSecure else !firstSecure
            val timeout = (deadline - clock()).coerceAtMost(modeTimeoutMs)
            if (timeout <= 0) return null
            val key = key(domain, secure)
            when (acquire(key)) {
                Unavailable.BUSY -> {
                    onSkipped()
                    return null // A concurrent attempt is not evidence of a timeout.
                }
                Unavailable.TIMEOUT -> {
                    onSkipped()
                    continue // A prior client observed a timeout on this mode.
                }
                null -> Unit
            }
            var timedOut = false
            try {
                return Connected(dial(secure, timeout), secure)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val timeoutFailure = isTransportTimeout(e)
                onFailure(secure, e)
                if (!timeoutFailure) return null
                timedOut = timeout == modeTimeoutMs
                if (timedOut) fullTimeouts++
            } finally {
                synchronized(lock) {
                    // A truncated final probe does not establish a full timeout for this mode.
                    if (timedOut) retryAfter[key] = clock() + timeoutCooldownMs
                    inFlight.remove(key)
                }
            }
        }
        if (fullTimeouts == modeCount) {
            currentCoroutineContext().ensureActive()
            onTimeoutsExhausted()
        }
        return null
    }

    private fun key(domain: String, secure: Boolean) = Key(domain.lowercase(Locale.ROOT), secure)

    private fun acquire(key: Key): Unavailable? = synchronized(lock) {
        val now = clock()
        retryAfter.entries.removeAll { it.value <= now }
        when {
            key in retryAfter -> Unavailable.TIMEOUT
            !inFlight.add(key) -> Unavailable.BUSY
            else -> null
        }
    }
}

private const val MAX_BLOCKING_TRANSPORT_OPERATIONS = 16
internal enum class TransportOperation { DIAL, RELAY_INIT }

// Stuck system lookups may keep worker threads, but never queue up more dials or occupy the
// general I/O dispatcher. Cleanup must also be able to run while the dial workers are blocked.
private class TransportExecutor {
    val dispatcher = Dispatchers.IO.limitedParallelism(MAX_BLOCKING_TRANSPORT_OPERATIONS)
    val capacity = Semaphore(MAX_BLOCKING_TRANSPORT_OPERATIONS)
}

private val transportDialExecutor = TransportExecutor()
// Established sockets must be able to send relay init even if all dial slots are occupied.
private val transportRelayInitExecutor = TransportExecutor()
private val transportCleanupDispatcher = Dispatchers.IO.limitedParallelism(MAX_BLOCKING_TRANSPORT_OPERATIONS)

internal fun dispatchTransportCleanup(cleanup: () -> Unit) {
    transportCleanupDispatcher.dispatch(EmptyCoroutineContext, Runnable { runCatching(cleanup) })
}

internal typealias TransportStageObserver = (stage: String, elapsedMs: Long, error: Exception?) -> Unit

internal inline fun <T> transportStage(stage: String, noinline observer: TransportStageObserver?, action: () -> T): T {
    if (observer == null) return action()
    val started = transportClockMs()
    var error: Exception? = null
    try {
        return action()
    } catch (e: Exception) {
        error = e
        throw e
    } finally {
        observer(stage, transportClockMs() - started, error)
    }
}

/** Blocking socket I/O (including system DNS) cannot keep the caller past its deadline. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun <T> boundedTransportAttempt(
    timeoutMs: Long,
    discard: (T) -> Unit = {},
    closeOnFailure: AutoCloseable? = null,
    operation: TransportOperation = TransportOperation.DIAL,
    block: (TransportAttemptResources) -> T,
): T {
    val executor = when (operation) {
        TransportOperation.DIAL -> transportDialExecutor
        TransportOperation.RELAY_INIT -> transportRelayInitExecutor
    }
    val resources = TransportAttemptResources(transportClockMs() + timeoutMs)
    closeOnFailure?.let(resources::register)
    val started = AtomicBoolean()
    var result: T? = null
    try {
        val completed = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T> { continuation ->
                continuation.invokeOnCancellation { resources.cancel() }
                if (!executor.capacity.tryAcquire()) {
                    continuation.resumeWithException(TransportDialNotStarted())
                    return@suspendCancellableCoroutine
                }
                // Do not create a structured child that would have to join a stuck DNS lookup.
                executor.dispatcher.dispatch(EmptyCoroutineContext, Runnable {
                    try {
                        if (!continuation.isActive) return@Runnable
                        started.set(true)
                        val value = block(resources)
                        continuation.resume(value) { _, rejected, _ ->
                            dispatchTransportCleanup { discard(rejected) }
                        }
                    } catch (e: Throwable) {
                        continuation.resumeWithException(e)
                    } finally {
                        executor.capacity.release()
                    }
                })
            }.let {
                result = it
                Result.success(it)
            }
        } ?: throw if (started.get()) {
            resources.upgradeRejection ?: SocketTimeoutException("Upstream connection deadline exceeded")
        } else {
            TransportDialNotStarted()
        }
        return completed.getOrThrow()
    } catch (e: Throwable) {
        resources.cancel()
        result?.let { rejected -> dispatchTransportCleanup { discard(rejected) } }
        throw e
    }
}

internal class TransportAttemptResources(private val deadline: Long) {
    private val resources = mutableSetOf<AutoCloseable>()
    @Volatile private var cancelled = false

    @Volatile
    var upgradeRejection: WsHandshakeError? = null
        private set

    fun recordUpgradeRejection(error: WsHandshakeError) {
        upgradeRejection = error
    }

    val isActive: Boolean get() = !cancelled
    fun remainingMs(): Long = deadline - transportClockMs()

    fun register(resource: AutoCloseable) {
        synchronized(resources) {
            if (!cancelled) {
                resources.add(resource)
                return
            }
        }
        dispatchTransportCleanup { resource.close() }
        throw CancellationException("Upstream connection cancelled")
    }

    fun release(resource: AutoCloseable) {
        synchronized(resources) { resources.remove(resource) }
    }

    fun cancel() {
        val toClose = synchronized(resources) {
            if (cancelled) return
            cancelled = true
            resources.toList().also { resources.clear() }
        }
        if (toClose.isNotEmpty()) dispatchTransportCleanup {
            toClose.forEach { runCatching { it.close() } }
        }
    }
}
