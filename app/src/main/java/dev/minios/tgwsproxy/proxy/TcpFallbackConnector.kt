package dev.minios.tgwsproxy.proxy

import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

private const val TCP_CONNECT_TIMEOUT_MS = 10_000
private const val TCP_RELAY_INIT_TIMEOUT_MS = 10_000L

internal class TcpFallbackConnector(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val socketFactory: () -> Socket = ::Socket,
) {
    private data class Endpoint(val ip: String, val port: Int)
    private data class Failure(val count: Int, val retryAfter: Long)

    private val lock = Any()
    private val connecting = mutableMapOf<Endpoint, Socket>()
    private val failures = mutableMapOf<Endpoint, Failure>()
    private var enabled = true

    fun reset(enabled: Boolean = true) {
        synchronized(lock) {
            this.enabled = enabled
            connecting.values.forEach { try { it.close() } catch (_: Exception) {} }
            connecting.clear()
            failures.clear()
        }
    }

    suspend fun connect(
        ip: String,
        port: Int,
        bufferSize: Int,
        relayInit: ByteArray,
        canRecordFailure: () -> Boolean = { true },
        isActive: () -> Boolean = { true },
        relayInitTimeoutMs: Long = TCP_RELAY_INIT_TIMEOUT_MS,
        onStage: TransportStageObserver? = null,
    ): Socket? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val endpoint = Endpoint(ip, port)
        val socket = synchronized(lock) {
            if (!enabled || !isActive() || endpoint in connecting ||
                (failures[endpoint]?.retryAfter ?: 0L) > clock()
            ) return null
            socketFactory().also { connecting[endpoint] = it }
        }
        val closing = AtomicBoolean()
        val closeSocket = AutoCloseable {
            if (closing.compareAndSet(false, true)) runCatching { socket.close() }
        }
        fun checkActive(attempt: TransportAttemptResources? = null) {
            context.ensureActive()
            if (attempt?.isActive == false || !isActive()) throw CancellationException("TCP fallback cancelled")
        }
        var adopted = false
        try {
            boundedTransportAttempt(TCP_CONNECT_TIMEOUT_MS.toLong(), closeOnFailure = closeSocket) { attempt ->
                checkActive(attempt)
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.sendBufferSize = bufferSize
                socket.receiveBufferSize = bufferSize
                val endpoint = transportStage("address_resolution", onStage) { InetSocketAddress(ip, port) }
                checkActive(attempt)
                transportStage("tcp_connect", onStage) { socket.connect(endpoint, TCP_CONNECT_TIMEOUT_MS) }
            }
            checkActive()
            synchronized(lock) {
                if (connecting[endpoint] !== socket) throw IOException("TCP fallback reset")
            }
            boundedTransportAttempt(relayInitTimeoutMs, closeOnFailure = closeSocket) { attempt ->
                checkActive(attempt)
                transportStage("relay_init", onStage) {
                    socket.getOutputStream().apply {
                        write(relayInit)
                        flush()
                    }
                }
            }
            checkActive()
            synchronized(lock) {
                // A reset closes pending sockets; late results cannot enter the new runtime.
                if (connecting[endpoint] !== socket) throw IOException("TCP fallback reset")
                connecting.remove(endpoint)
                failures.remove(endpoint)
                adopted = true
            }
            return socket
        } catch (e: Exception) {
            synchronized(lock) {
                if (connecting[endpoint] === socket) {
                    connecting.remove(endpoint)
                    if (e !is CancellationException && e !is TransportDialNotStarted &&
                        context.isActive && isActive() && canRecordFailure()
                    ) {
                        val count = ((failures[endpoint]?.count ?: 0) + 1).coerceAtMost(8)
                        val delay = (30_000L shl (count - 1)).coerceAtMost(3_600_000L)
                        failures[endpoint] = Failure(count, clock() + delay)
                    }
                }
            }
            throw e
        } finally {
            if (!adopted) dispatchTransportCleanup { closeSocket.close() }
        }
    }
}
