package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.ProtocolException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Telegram's authenticated Fake TLS envelope, not a TLS implementation.
 *
 * Ported from tg-ws-proxy 6690b788432fd0f59b0f38e228494bcd6bede1ca, fake_tls.py.
 * Authentication covers the complete first record. Payload encryption is still the
 * caller's MTProto responsibility. No socket, DNS lookup, or masking relay is created here.
 */
object FakeTls {
    const val MAX_PAYLOAD = 16_384
    private const val RANDOM_OFFSET = 11
    private const val RANDOM_LENGTH = 32
    private const val TIMESTAMP_TOLERANCE = 120L
    private const val REPLAY_WINDOW_MILLIS = 241_000L
    private const val REPLAY_CACHE_SIZE = 65_536
    private val defaultCodec = Codec()

    data class Streams(val input: InputStream, val output: OutputStream)

    enum class Rejection { MALFORMED, AUTHENTICATION, TIMESTAMP, REPLAY }

    /**
     * Nothing has been written. Relay [initialData] and the remaining original input
     * to [domain]:443, or close the connection. Never retry as unauthenticated TLS.
     */
    class MaskingRequired(
        val domain: String,
        val initialData: ByteArray,
        val reason: Rejection,
    ) : IOException("Fake TLS handshake requires masking: $reason")

    /**
     * Blocking handshake; [initialPrefix] is the already-consumed 0..5 record-header bytes.
     * The caller owns socket deadlines, connection lifetime, and masking on rejection.
     * A shared, synchronized replay cache survives service/server instance restarts.
     */
    @Throws(IOException::class)
    fun accept(
        input: InputStream,
        output: OutputStream,
        secret: ByteArray,
        domain: String,
        initialPrefix: ByteArray = byteArrayOf(),
    ): Streams = defaultCodec.accept(input, output, secret, domain, initialPrefix)

    /** Injectable entropy and clocks keep wire-format and expiry tests deterministic. */
    internal class Codec(
        private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
        private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        private val random: SecureRandom = SecureRandom(),
        private val delay: (Long) -> Unit = { Thread.sleep(it) },
        private val appDataLength: () -> Int = { 64 + random.nextInt(97) },
        private val replayCapacity: Int = REPLAY_CACHE_SIZE,
    ) {
        private val replay = LinkedHashMap<RandomKey, Long>()

        init {
            require(replayCapacity > 0)
        }

        fun accept(
            input: InputStream,
            output: OutputStream,
            secret: ByteArray,
            domain: String,
            initialPrefix: ByteArray = byteArrayOf(),
        ): Streams {
            require(secret.size == 16) { "Fake TLS requires the 16-byte MTProxy secret" }
            require(domain.isNotBlank()) { "A masking domain is required" }
            require(initialPrefix.size <= 5) { "Only a record-header prefix may be supplied" }
            val header = ByteArray(5)
            initialPrefix.copyInto(header)
            readFully(input, header, initialPrefix.size, 5 - initialPrefix.size)
            fun reject(data: ByteArray, reason: Rejection): Nothing =
                throw MaskingRequired(domain, data, reason)

            val length = u16(header, 3)
            if (header[0] != 0x16.toByte() || header[1] != 3.toByte() ||
                header[2].toInt() !in 1..3 || length !in 41..MAX_PAYLOAD
            ) {
                // The body has not been consumed, and belongs to the masking relay.
                reject(header, Rejection.MALFORMED)
            }
            val hello = ByteArray(5 + length)
            header.copyInto(hello)
            readFully(input, hello, 5, length)
            val info = try {
                parseHello(hello)
            } catch (_: ProtocolException) {
                reject(hello, Rejection.MALFORMED)
            }
            val clientRandom = hello.copyOfRange(RANDOM_OFFSET, RANDOM_OFFSET + RANDOM_LENGTH)
            val zeroed = hello.copyOf()
            zeroed.fill(0, RANDOM_OFFSET, RANDOM_OFFSET + RANDOM_LENGTH)
            val expected = hmac(secret, zeroed)
            if (!MessageDigest.isEqual(expected.copyOf(28), clientRandom.copyOf(28))) {
                reject(hello, Rejection.AUTHENTICATION)
            }
            var timestamp = 0L
            for (i in 0..3) {
                timestamp = timestamp or
                    (((clientRandom[28 + i].toInt() xor expected[28 + i].toInt()) and 255).toLong() shl (8 * i))
            }
            val now = nowSeconds()
            if (timestamp < now - TIMESTAMP_TOLERANCE || timestamp > now + TIMESTAMP_TOLERANCE) {
                reject(hello, Rejection.TIMESTAMP)
            }
            if (!firstSeen(clientRandom)) reject(hello, Rejection.REPLAY)
            val response = serverHello(secret, clientRandom, info)
            try {
                delay(8L + random.nextInt(17))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Fake TLS handshake interrupted").apply { initCause(e) }
            }
            output.write(response)
            output.flush()
            return Streams(RecordInput(input), RecordOutput(output))
        }

        @Synchronized
        private fun firstSeen(value: ByteArray): Boolean {
            val now = monotonicMillis()
            val entries = replay.entries.iterator()
            while (entries.hasNext()) {
                if (entries.next().value > now) break
                entries.remove()
            }
            // Only the first 28 bytes are authenticated. The timestamp suffix can
            // be changed without the secret, so it must not distinguish replays.
            val key = RandomKey(value.copyOf(28))
            if (replay.containsKey(key)) return false
            if (replay.size >= replayCapacity) {
                val oldest = replay.entries.iterator()
                oldest.next()
                oldest.remove()
            }
            replay[key] = now + REPLAY_WINDOW_MILLIS
            return true
        }

        private fun serverHello(secret: ByteArray, clientRandom: ByteArray, info: HelloInfo): ByteArray {
            // Upstream's no-X25519-provider fallback: a field-shaped public value.
            // No shared TLS keys are derived in this protocol.
            val key = ByteArray(32).also(random::nextBytes)
            key[31] = (key[31].toInt() and 0x7f).toByte()
            val hybrid = info.shares[0x11ec] == 1216
            val exchange = if (hybrid) ByteArray(1088).also(random::nextBytes) + key else key
            val extensions = ByteArrayOutputStream().apply {
                word(0x002b)
                word(2)
                word(0x0304)
                word(0x0033)
                word(4 + exchange.size)
                word(if (hybrid) 0x11ec else 0x001d)
                word(exchange.size)
                write(exchange)
            }.toByteArray()
            val suite = listOf(0x1301, 0x1303, 0x1302).firstOrNull { it in info.suites } ?: 0x1301
            val body = ByteArrayOutputStream().apply {
                word(0x0303)
                write(ByteArray(32))
                write(info.session.size)
                write(info.session)
                word(suite)
                write(0)
                word(extensions.size)
                write(extensions)
            }.toByteArray()
            val message = byteArrayOf(2, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
            val appLength = appDataLength()
            require(appLength in 64..MAX_PAYLOAD)
            val response = record(0x16, message) +
                byteArrayOf(0x14, 3, 3, 0, 1, 1) +
                record(0x17, ByteArray(appLength).also(random::nextBytes))
            hmac(secret, clientRandom, response).copyInto(response, RANDOM_OFFSET)
            return response
        }
    }

    private class RandomKey(private val bytes: ByteArray) {
        override fun equals(other: Any?) = other is RandomKey && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    private data class HelloInfo(val session: ByteArray, val suites: Set<Int>, val shares: Map<Int, Int>)

    private fun parseHello(data: ByteArray): HelloInfo {
        if (data[5] != 1.toByte() || (data[6].toInt() and 255) != 0 ||
            u16(data, 7) != data.size - 9
        ) throw ProtocolException("Invalid ClientHello message")
        val cursor = Cursor(data, 43, data.size)
        val sessionLength = cursor.byte()
        if (sessionLength > 32) throw ProtocolException("Invalid session ID")
        val session = cursor.bytes(sessionLength)
        val suiteLength = cursor.word()
        if (suiteLength < 2 || suiteLength % 2 != 0) throw ProtocolException("Invalid cipher suites")
        val suitesCursor = cursor.section(suiteLength)
        val suites = mutableSetOf<Int>()
        while (suitesCursor.remaining > 0) suites.add(suitesCursor.word())
        val compressionLength = cursor.byte()
        if (compressionLength < 1) throw ProtocolException("Missing compression methods")
        cursor.bytes(compressionLength)
        val shares = mutableMapOf<Int, Int>()
        if (cursor.remaining > 0) {
            val extensions = cursor.section(cursor.word())
            if (cursor.remaining != 0) throw ProtocolException("Trailing ClientHello data")
            val seen = mutableSetOf<Int>()
            while (extensions.remaining > 0) {
                val type = extensions.word()
                val extension = extensions.section(extensions.word())
                if (!seen.add(type)) throw ProtocolException("Duplicate extension")
                if (type == 0x0033) {
                    val entries = extension.section(extension.word())
                    if (extension.remaining != 0) throw ProtocolException("Invalid key shares")
                    while (entries.remaining > 0) {
                        val group = entries.word()
                        val size = entries.word()
                        entries.bytes(size)
                        if (group !in shares) shares[group] = size
                    }
                }
            }
        }
        // Match upstream: only a 32-byte session ID is echoed; otherwise use zeros.
        return HelloInfo(if (sessionLength == 32) session else ByteArray(32), suites, shares)
    }

    private class Cursor(private val data: ByteArray, private var position: Int, private val end: Int) {
        val remaining: Int get() = end - position
        fun byte(): Int {
            if (remaining < 1) throw ProtocolException("Truncated ClientHello")
            return data[position++].toInt() and 255
        }
        fun word(): Int = (byte() shl 8) or byte()
        fun section(length: Int): Cursor {
            if (length > remaining) throw ProtocolException("Truncated ClientHello vector")
            val result = Cursor(data, position, position + length)
            position += length
            return result
        }
        fun bytes(length: Int): ByteArray {
            if (length > remaining) throw ProtocolException("Truncated ClientHello vector")
            return data.copyOfRange(position, position + length).also { position += length }
        }
    }

    /** Incoming lengths remain bounded by the 16-bit header, as in upstream. */
    internal class RecordInput(private val source: InputStream) : InputStream() {
        private var left = 0
        private var ended = false
        override fun read(): Int {
            val single = ByteArray(1)
            return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            checkBounds(bytes, offset, length)
            if (length == 0) return 0
            if (ended) return -1
            var skipped = 0
            while (left == 0) {
                val header = ByteArray(5)
                val first = source.read()
                if (first < 0) {
                    ended = true
                    return -1
                }
                header[0] = first.toByte()
                readFully(source, header, 1, 4)
                if (header[1] != 3.toByte() || header[2] != 3.toByte()) {
                    throw ProtocolException("Invalid Fake TLS record version")
                }
                val size = u16(header, 3)
                when (first) {
                    0x14 -> {
                        if (size != 1 || source.read() != 1) throw ProtocolException("Invalid change-cipher-spec")
                    }
                    0x17 -> left = size
                    else -> throw ProtocolException("Unexpected Fake TLS record type")
                }
                if (left == 0 && ++skipped > 32) throw ProtocolException("Too many empty Fake TLS records")
            }
            val count = source.read(bytes, offset, minOf(length, left))
            if (count < 0) throw EOFException("Truncated Fake TLS record")
            if (count == 0) {
                val first = source.read()
                if (first < 0) throw EOFException("Truncated Fake TLS record")
                bytes[offset] = first.toByte()
                left--
                return 1
            }
            left -= count
            return count
        }
        override fun close() = source.close()
    }

    internal class RecordOutput(private val destination: OutputStream) : OutputStream() {
        @Synchronized
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            checkBounds(bytes, offset, length)
            var position = offset
            val end = offset + length
            while (position < end) {
                val size = minOf(MAX_PAYLOAD, end - position)
                destination.write(byteArrayOf(0x17, 3, 3, (size shr 8).toByte(), size.toByte()))
                destination.write(bytes, position, size)
                position += size
            }
        }
        @Synchronized
        override fun flush() = destination.flush()
        @Synchronized
        override fun close() = destination.close()
    }

    private fun checkBounds(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) throw IndexOutOfBoundsException()
    }

    private fun readFully(input: InputStream, data: ByteArray, offset: Int, length: Int) {
        var position = offset
        while (position < offset + length) {
            val count = input.read(data, position, offset + length - position)
            if (count < 0) throw EOFException("Truncated Fake TLS input")
            if (count == 0) {
                val next = input.read()
                if (next < 0) throw EOFException("Truncated Fake TLS input")
                data[position++] = next.toByte()
            } else {
                position += count
            }
        }
    }

    private fun u16(data: ByteArray, position: Int): Int =
        ((data[position].toInt() and 255) shl 8) or (data[position + 1].toInt() and 255)

    private fun hmac(secret: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(secret, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    private fun ByteArrayOutputStream.word(value: Int) {
        write(value shr 8)
        write(value)
    }

    private fun record(type: Int, payload: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), 3, 3, (payload.size shr 8).toByte(), payload.size.toByte()) + payload
}
