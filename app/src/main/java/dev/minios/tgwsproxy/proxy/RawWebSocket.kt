package dev.minios.tgwsproxy.proxy

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal RFC 6455 WebSocket client implementation for binary frames.
 * Connects to Telegram's WebSocket endpoints over TLS.
 */
class RawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: InputStream,
    private val output: OutputStream,
) : AutoCloseable {

    private val random = SecureRandom()
    @Volatile
    private var closed = false

    companion object {
        private const val OPCODE_CONTINUATION = 0x0
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val MAX_HTTP_RESPONSE_BYTES = 32 * 1024
        private const val MAX_FRAME_PAYLOAD = 16 * 1024 * 1024
        private const val WS_ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // Global SSLContext — reused for all connections (matches Python's module-level _ssl_ctx)
        private val globalSslContext: SSLContext by lazy {
            SSLContext.getInstance("TLS").also {
                it.init(null, arrayOf<TrustManager>(TrustAllManager()), SecureRandom())
            }
        }

        /**
         * Connect to a WebSocket endpoint.
         * @param connectHost IP address or hostname to connect to (TCP level)
         * @param domain domain name for TLS SNI and HTTP Host header
         * @param path WebSocket path
         * @param port TCP port
         * @param connectTimeoutMs connection timeout in milliseconds
         * @param bufferSize socket buffer size
         */
        fun connect(
            connectHost: String,
            domain: String,
            path: String = "/apiws",
            port: Int = 443,
            connectTimeoutMs: Int = 10000,
            bufferSize: Int = 256 * 1024,
        ): RawWebSocket {
            // Reuse global TLS context (matching Python's module-level ssl.SSLContext)
            val factory = globalSslContext.socketFactory

            val socket = factory.createSocket() as SSLSocket
            val clampedTimeout = connectTimeoutMs.coerceIn(1, 10000)
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.sendBufferSize = bufferSize
                socket.receiveBufferSize = bufferSize
                socket.soTimeout = clampedTimeout

                // Set SNI hostname for TLS while connecting to the configured IP.
                val sslParams = socket.sslParameters
                sslParams.serverNames = listOf(javax.net.ssl.SNIHostName(domain))
                socket.sslParameters = sslParams

                socket.connect(InetSocketAddress(connectHost, port), clampedTimeout)
                socket.startHandshake()
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // Send WebSocket upgrade request with domain as Host
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val wsKey = android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)

            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("User-Agent: $USER_AGENT\r\n")
                append("\r\n")
            }
            val response: String
            try {
                output.write(request.toByteArray())
                output.flush()
                response = readHttpResponse(input)
            } catch (e: Exception) {
                try { socket.close() } catch (_: Exception) {}
                throw e
            }

            val lines = response.lineSequence().toList()
            val statusLine = lines.firstOrNull() ?: ""

            // Accept any HTTP version with status 101.
            val parts = statusLine.split(" ", limit = 3)
            val statusCode = if (parts.size >= 2) parts[1].toIntOrNull() ?: 0 else 0

            if (statusCode != 101) {
                socket.close()
                // Extract Location header for redirect detection
                val location = response.lines()
                    .firstOrNull { it.startsWith("Location:", ignoreCase = true) }
                    ?.substringAfter(":")?.trim()
                throw WsHandshakeError(statusCode, statusLine, location)
            }

            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val separator = line.indexOf(':')
                    if (separator <= 0) null else {
                        line.substring(0, separator).trim().lowercase() to
                                line.substring(separator + 1).trim()
                    }
                }
                .groupBy({ it.first }, { it.second })
            val expectedAccept = android.util.Base64.encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((wsKey + WS_ACCEPT_GUID).toByteArray(Charsets.US_ASCII)),
                android.util.Base64.NO_WRAP,
            )
            val connectionTokens = headers["connection"]
                .orEmpty()
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
            if (!headers["upgrade"].orEmpty().any { it.equals("websocket", ignoreCase = true) } ||
                "upgrade" !in connectionTokens ||
                expectedAccept !in headers["sec-websocket-accept"].orEmpty()
            ) {
                socket.close()
                throw WebSocketException("Invalid WebSocket upgrade response")
            }

            // Clear soTimeout for the data phase — no read timeout on established connections
            socket.soTimeout = 0

            return RawWebSocket(socket, input, output)
        }

        /**
         * Connect to a Telegram DC via WebSocket.
         * @param dc data center number
         * @param isMedia whether this is a media connection
         * @param targetIp the IP to connect to (from dc_redirects config)
         * @param bufferSize socket buffer size
         * @param cfProxyDomain optional CF proxy domain to use instead of direct WS
         */
        fun connectToDc(
            dc: Int,
            isMedia: Boolean,
            targetIp: String,
            bufferSize: Int = 256 * 1024,
            cfProxyDomain: String? = null,
            connectTimeoutMs: Int = 10000,
        ): RawWebSocket {
            val domains = if (cfProxyDomain != null) {
                val wsDc = if (dc == 203) 2 else dc
                listOf("kws${wsDc}.$cfProxyDomain")
            } else {
                MtProtoConstants.wsDomainsForDc(dc, isMedia)
            }

            // For CF proxy, we connect to the CF domain directly (DNS resolves to CF edge)
            // For direct WS, we connect to the redirect IP with domain as SNI
            val connectTarget = if (cfProxyDomain != null) domains.first() else targetIp

            var lastError: Exception? = null
            for (domain in domains) {
                try {
                    return connect(
                        connectHost = connectTarget,
                        domain = domain,
                        bufferSize = bufferSize,
                        connectTimeoutMs = connectTimeoutMs,
                    )
                } catch (e: WsHandshakeError) {
                    lastError = e
                    if (e.isRedirect) {
                        // Try next domain on redirect (matching Python behavior)
                        continue
                    }
                    // CF proxy mode can continue through the remaining domains.
                    // (matching Python's flat `except Exception` in _cfproxy_fallback).
                    // For direct WS, stop on non-redirect handshake errors.
                    if (cfProxyDomain != null) continue else break
                } catch (e: Exception) {
                    lastError = e
                    // Direct mode stops after connection errors; CF mode rotates.
                    // For direct WS, stop trying.
                    if (cfProxyDomain != null) continue else break
                }
            }
            throw lastError ?: WebSocketException("Failed to connect to DC $dc")
        }

        private fun readHttpResponse(input: InputStream): String {
            val sb = StringBuilder()
            var prev = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                sb.append(b.toChar())
                if (sb.length > MAX_HTTP_RESPONSE_BYTES) {
                    throw WebSocketException("WebSocket upgrade response too large")
                }
                if (prev == '\r'.code && b == '\n'.code && sb.length >= 4) {
                    val last4 = sb.substring(sb.length - 4)
                    if (last4 == "\r\n\r\n") break
                }
                prev = b
            }
            return sb.toString()
        }
    }

    /**
     * Send a binary frame.
     */
    fun sendBinary(data: ByteArray) {
        // Fail before writing to an already closed socket.
        if (closed) throw IOException("WebSocket closed")
        sendFrame(OPCODE_BINARY, data)
    }

    fun sendPing() {
        if (closed) throw IOException("WebSocket closed")
        sendFrame(OPCODE_PING, ByteArray(0))
    }

    /**
     * Send multiple binary frames in a batch (single flush).
     */
    fun sendBatch(frames: List<ByteArray>) {
        // Fail before writing to an already closed socket.
        if (closed) throw IOException("WebSocket closed")
        synchronized(output) {
            for (data in frames) {
                writeFrame(OPCODE_BINARY, data)
            }
            output.flush()
        }
    }

    /**
     * Read the next binary data frame, handling PING/PONG/CLOSE automatically.
     * Returns null if the connection is closed.
     */
    fun readBinary(): ByteArray? {
        var fragmentedOpcode: Int? = null
        var fragmentedPayload: ByteArrayOutputStream? = null
        while (!closed) {
            val frame = readFrame() ?: return null
            when (frame.opcode) {
                OPCODE_BINARY, OPCODE_TEXT -> {
                    if (fragmentedOpcode != null) {
                        throw WebSocketException("New data frame before fragmented message completed")
                    }
                    if (frame.fin) return frame.payload
                    fragmentedOpcode = frame.opcode
                    fragmentedPayload = ByteArrayOutputStream(frame.payload.size).apply {
                        write(frame.payload)
                    }
                }
                OPCODE_CONTINUATION -> {
                    val buffer = fragmentedPayload
                        ?: throw WebSocketException("Unexpected WebSocket continuation frame")
                    if (buffer.size() + frame.payload.size > MAX_FRAME_PAYLOAD) {
                        throw WebSocketException("Fragmented WebSocket message too large")
                    }
                    buffer.write(frame.payload)
                    if (frame.fin) {
                        fragmentedOpcode = null
                        fragmentedPayload = null
                        return buffer.toByteArray()
                    }
                }
                OPCODE_PING -> sendFrame(OPCODE_PONG, frame.payload)
                OPCODE_CLOSE -> {
                    closed = true
                    // Echo only the close status code.
                    try {
                        val closePayload = if (frame.payload.size >= 2) {
                            frame.payload.copyOf(2)
                        } else {
                            frame.payload
                        }
                        sendFrame(OPCODE_CLOSE, closePayload)
                    } catch (_: Exception) {
                    }
                    return null
                }
                OPCODE_PONG -> { /* ignore */ }
                else -> { /* ignore unknown opcodes */ }
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            sendFrame(OPCODE_CLOSE, ByteArray(0))
        } catch (_: Exception) {
        }
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    fun isClosed(): Boolean = closed || socket.isClosed

    /**
     * Check if the socket output is shut down (mid-close state).
     * Used by WsPool to detect connections that are being closed.
     */
    fun isOutputShutdown(): Boolean = socket.isOutputShutdown

    private fun sendFrame(opcode: Int, data: ByteArray) {
        synchronized(output) {
            writeFrame(opcode, data)
            output.flush()
        }
    }

    // Build the complete frame as one array to keep it in a single socket write.
    // With tcpNoDelay=true, two writes would produce two TCP segments.
    private fun writeFrame(opcode: Int, data: ByteArray) {
        val mask = ByteArray(4).also { random.nextBytes(it) }
        val masked = xorMask(data, mask)

        val frame = buildCompleteFrame(opcode, data.size, mask, masked)
        output.write(frame)
    }

    private fun buildCompleteFrame(opcode: Int, payloadLen: Int, mask: ByteArray, maskedPayload: ByteArray): ByteArray {
        val fin = 0x80
        val firstByte = (fin or opcode).toByte()
        val maskBit = 0x80

        val headerSize = when {
            payloadLen < 126 -> 6       // 2 + 4 mask
            payloadLen < 65536 -> 8     // 2 + 2 + 4 mask
            else -> 14                   // 2 + 8 + 4 mask
        }

        val frame = ByteArray(headerSize + payloadLen)
        var pos = 0

        frame[pos++] = firstByte
        when {
            payloadLen < 126 -> {
                frame[pos++] = (maskBit or payloadLen).toByte()
            }
            payloadLen < 65536 -> {
                frame[pos++] = (maskBit or 126).toByte()
                frame[pos++] = (payloadLen shr 8).toByte()
                frame[pos++] = (payloadLen and 0xFF).toByte()
            }
            else -> {
                frame[pos++] = (maskBit or 127).toByte()
                val len = payloadLen.toLong()
                frame[pos++] = (len shr 56).toByte()
                frame[pos++] = (len shr 48).toByte()
                frame[pos++] = (len shr 40).toByte()
                frame[pos++] = (len shr 32).toByte()
                frame[pos++] = (len shr 24).toByte()
                frame[pos++] = (len shr 16).toByte()
                frame[pos++] = (len shr 8).toByte()
                frame[pos++] = (len and 0xFF).toByte()
            }
        }

        // Mask key
        frame[pos++] = mask[0]
        frame[pos++] = mask[1]
        frame[pos++] = mask[2]
        frame[pos++] = mask[3]

        // Masked payload
        System.arraycopy(maskedPayload, 0, frame, pos, payloadLen)

        return frame
    }

    private fun readFrame(): WsFrame? {
        try {
            val b0 = input.read()
            if (b0 == -1) {
                closed = true
                return null
            }
            val b1 = input.read()
            if (b1 == -1) {
                closed = true
                return null
            }

            val opcode = b0 and 0x0F
            val fin = (b0 and 0x80) != 0
            if ((b0 and 0x70) != 0) {
                throw WebSocketException("Unsupported WebSocket RSV bits")
            }
            val masked = (b1 and 0x80) != 0
            if (masked) {
                throw WebSocketException("Masked server WebSocket frame")
            }
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val b2 = input.read()
                val b3 = input.read()
                if (b2 == -1 || b3 == -1) {
                    closed = true
                    return null
                }
                payloadLen = ((b2 shl 8) or b3).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val b = input.read()
                    if (b == -1) {
                        closed = true
                        return null
                    }
                    if (i == 0 && (b and 0x80) != 0) {
                        throw WebSocketException("Invalid WebSocket payload length")
                    }
                    len = (len shl 8) or b.toLong()
                }
                payloadLen = len
            }

            if (payloadLen > MAX_FRAME_PAYLOAD) {
                throw WebSocketException("WebSocket frame too large: $payloadLen")
            }
            val isControl = opcode >= OPCODE_CLOSE
            if (isControl && (!fin || payloadLen > 125)) {
                throw WebSocketException("Invalid WebSocket control frame")
            }

            val payload = ByteArray(payloadLen.toInt())
            readFully(input, payload)
            return WsFrame(fin, opcode, payload)
        } catch (e: Exception) {
            if (!closed) throw e
            return null
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var offset = 0
        while (offset < buf.size) {
            val read = input.read(buf, offset, buf.size - offset)
            if (read == -1) throw WebSocketException("Connection closed during read")
            offset += read
        }
    }

    private fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
        val result = data.copyOf()
        // XOR 4 bytes at a time for performance
        val maskInt = ((mask[0].toInt() and 0xFF) shl 24) or
                ((mask[1].toInt() and 0xFF) shl 16) or
                ((mask[2].toInt() and 0xFF) shl 8) or
                (mask[3].toInt() and 0xFF)

        var i = 0
        while (i + 4 <= result.size) {
            val v = ((result[i].toInt() and 0xFF) shl 24) or
                    ((result[i + 1].toInt() and 0xFF) shl 16) or
                    ((result[i + 2].toInt() and 0xFF) shl 8) or
                    (result[i + 3].toInt() and 0xFF)
            val xored = v xor maskInt
            result[i] = (xored shr 24).toByte()
            result[i + 1] = (xored shr 16).toByte()
            result[i + 2] = (xored shr 8).toByte()
            result[i + 3] = xored.toByte()
            i += 4
        }
        while (i < result.size) {
            result[i] = (result[i].toInt() xor mask[i % 4].toInt()).toByte()
            i++
        }
        return result
    }

    private data class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }
}

class WebSocketException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Structured WS handshake error matching Python WsHandshakeError.
 * Carries HTTP status code, status line, and Location header for redirect detection.
 */
class WsHandshakeError(
    val statusCode: Int,
    val statusLine: String,
    val location: String?,
) : Exception("WS handshake failed (HTTP $statusCode): $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in listOf(301, 302, 303, 307, 308)
}

/**
 * Trust manager that accepts all certificates (matching Python's ssl.CERT_NONE).
 */
private class TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
}
