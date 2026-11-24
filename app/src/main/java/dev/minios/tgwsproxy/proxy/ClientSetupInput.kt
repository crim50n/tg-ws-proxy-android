package dev.minios.tgwsproxy.proxy

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The single wire input underneath both plain MTProto and Fake TLS. Only during
 * upstream setup, a watcher reads ahead on the raw socket; the bridge then sees
 * those bytes first through the original framing and crypto state.
 */
internal class ClientSetupInput(
    private val socket: Socket,
    private val prefetchLimit: Int = 64 * 1024,
) : InputStream() {
    private val wire = socket.getInputStream()
    private var pending: ByteArray? = null
    private var size = 0
    private var position = 0
    private var eof = false

    init {
        require(prefetchLimit > 0)
    }

    /** Call once per client. No other reader may use this input until setup returns. */
    suspend fun <T : Any> duringUpstreamSetup(discard: (T) -> Unit, connect: suspend () -> T): T {
        if (socket.isClosed || socket.isInputShutdown) throw disconnected()
        // Keep ownership across coroutineScope's cancellable return to its caller.
        var unclaimed: T? = null
        try {
            val result = coroutineScope {
                val setupJob = coroutineContext.job
                val watcher = launch(Dispatchers.Default) {
                    // Fast paths (e.g. a warm pool hit) need not touch the read timeout.
                    delay(PROBE_TIMEOUT_MS.toLong())
                    try {
                        watch { setupJob.cancel(it) }
                    } catch (e: IOException) {
                        setupJob.cancel(disconnected(e))
                    }
                }
                try {
                    connect().also { unclaimed = it }
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    throw e
                } finally {
                    watcher.cancel()
                }
                // coroutineScope waits for even an in-flight blocking read before
                // allowing the bridge to read or the caller to reuse the socket.
            }
            currentCoroutineContext().ensureActive()
            unclaimed = null
            return result
        } finally {
            unclaimed?.let { runCatching { discard(it) } }
        }
    }

    private suspend fun watch(onDisconnect: (CancellationException) -> Unit) {
        val previousTimeout = socket.soTimeout
        socket.soTimeout = PROBE_TIMEOUT_MS
        try {
            while (currentCoroutineContext().isActive && !eof && size < prefetchLimit) {
                // Yield the limited blocking dispatcher after each read. On overflow,
                // leave the rest in the kernel: FIN/RST behind unread data isn't
                // reliably observable with a blocking Socket until a reader drains it.
                val timedOut = withContext(probeIo) {
                    try {
                        if (prefetch() < 0) {
                            eof = true
                            // Like asyncio's reader.at_eof(), a clean FIN with
                            // pending bytes is eligible to continue to the bridge.
                            if (size == 0) onDisconnect(disconnected())
                        }
                        false
                    } catch (_: SocketTimeoutException) {
                        true
                    } catch (e: IOException) {
                        // Unlike a clean FIN, a reset cancels even with queued bytes.
                        onDisconnect(disconnected(e))
                        false
                    }
                }
                if (timedOut) delay(PROBE_TIMEOUT_MS.toLong())
            }
        } finally {
            if (size == 0) pending = null
            if (!socket.isClosed) socket.soTimeout = previousTimeout
        }
    }

    private fun prefetch(): Int {
        val current = pending
        val target = when {
            current == null -> ByteArray(minOf(prefetchLimit, 4096))
            size == current.size -> current.copyOf(size + minOf(size, prefetchLimit - size))
            else -> current
        }
        pending = target
        val count = wire.read(target, size, target.size - size)
        if (count > 0) size += count
        return count
    }

    override fun read(): Int {
        if (position == size) return if (eof) -1 else wire.read()
        return (pending!![position++].toInt() and 0xff).also { releaseConsumedPrefix() }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
        if (length == 0) return 0
        if (position == size) return if (eof) -1 else wire.read(bytes, offset, length)
        val count = minOf(length, size - position)
        pending!!.copyInto(bytes, offset, position, position + count)
        position += count
        releaseConsumedPrefix()
        return count
    }

    private fun releaseConsumedPrefix() {
        if (position == size) {
            pending = null
            position = 0
            size = 0
        }
    }

    override fun close() = wire.close()

    private companion object {
        const val PROBE_TIMEOUT_MS = 25
        // Watchers don't consume the regular IO parallelism budget and can't
        // create one blocking thread per client waiting for an upstream.
        val probeIo = Dispatchers.IO.limitedParallelism(8)

        fun disconnected(cause: IOException? = null) =
            CancellationException("Local client disconnected during upstream setup").apply {
                if (cause != null) initCause(cause)
            }
    }
}
