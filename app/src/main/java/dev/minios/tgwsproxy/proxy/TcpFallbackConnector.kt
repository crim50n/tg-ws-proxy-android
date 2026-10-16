package dev.minios.tgwsproxy.proxy

import android.os.SystemClock
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CancellationException

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

    fun connect(
        ip: String,
        port: Int,
        bufferSize: Int,
        relayInit: ByteArray,
        canRecordFailure: () -> Boolean = { true },
        isActive: () -> Boolean = { true },
    ): Socket? {
        val endpoint = Endpoint(ip, port)
        val socket = synchronized(lock) {
            if (!enabled || !isActive() || endpoint in connecting ||
                (failures[endpoint]?.retryAfter ?: 0L) > clock()
            ) return null
            socketFactory().also { connecting[endpoint] = it }
        }
        var adopted = false
        try {
            if (!isActive()) throw CancellationException("TCP fallback cancelled")
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.connect(InetSocketAddress(ip, port), 10_000)
            socket.getOutputStream().apply {
                write(relayInit)
                flush()
            }
            if (!isActive()) throw CancellationException("TCP fallback cancelled")
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
                    if (e !is CancellationException && isActive() && canRecordFailure()) {
                        val count = ((failures[endpoint]?.count ?: 0) + 1).coerceAtMost(8)
                        val delay = (30_000L shl (count - 1)).coerceAtMost(3_600_000L)
                        failures[endpoint] = Failure(count, clock() + delay)
                    }
                }
            }
            throw e
        } finally {
            if (!adopted) try { socket.close() } catch (_: Exception) {}
        }
    }
}
