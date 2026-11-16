package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ProtocolException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTlsTest {
    private val secret = ByteArray(16) { it.toByte() }
    private val now = 1_700_000_000L
    private val domain = "example.org"

    @Test
    fun `known independent hello and server HMAC vector matches upstream wire format`() {
        // Independently calculated using Python stdlib struct/hmac/hashlib, not this codec.
        val hello = hex(
            "16030100820100007e03038e4183e0a4e12484b34e2b2e52a8fd06d443c75caca26a33" +
                "af4350d63d60cfc720a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7" +
                "b8b9babbbcbdbebf00041302130301000031002b0003020304003300260024001d0020" +
                "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"
        )
        assertArrayEquals(hello, hello())
        val output = ByteArrayOutputStream()
        codec().accept(ByteArrayInputStream(hello), output, secret, domain)
        val response = output.toByteArray()
        assertEquals(202, response.size)
        assertArrayEquals(
            hex("139dac0d34d691bb2fd32a17d0a578e1e37f443cf308106aac257824b2bfb69b"),
            response.copyOfRange(11, 43)
        )
        assertArrayEquals(
            hex("943786b224a121e568d8468ae4e0c0f586c1e57cad6e6146c5c9f5930854a2e6"),
            MessageDigest.getInstance("SHA-256").digest(response)
        )
        assertResponseHmac(hello, response)
        assertEquals(listOf(0x16, 0x14, 0x17), records(response).map { it.first })
        assertArrayEquals(ByteArray(32) { (160 + it).toByte() }, response.copyOfRange(44, 76))
    }

    @Test
    fun `handshake prefix and partial reads leave framed MTProto input intact`() {
        val hello = hello()
        val payload = ByteArray(64) { (it * 3).toByte() }
        val trailing = record(0x14, byteArrayOf(1)) +
            record(0x17, payload.copyOfRange(0, 19)) +
            record(0x17, payload.copyOfRange(19, 64))
        for (prefixSize in 0..5) {
            val raw = FragmentedInput(hello.copyOfRange(prefixSize, hello.size) + trailing)
            val streams = codec().accept(
                raw, ByteArrayOutputStream(), secret, domain, hello.copyOf(prefixSize)
            )
            assertArrayEquals(payload, streams.input.readBytes())
        }
    }

    @Test
    fun `unauthenticated and stale hellos require masking without response`() {
        val original = hello()
        val badHmac = original.copyOf().apply { this[11] = (this[11].toInt() xor 1).toByte() }
        assertRejected(badHmac, FakeTls.Rejection.AUTHENTICATION)
        assertRejected(hello(timestamp = now - 121), FakeTls.Rejection.TIMESTAMP)
        assertRejected(hello(timestamp = now + 121), FakeTls.Rejection.TIMESTAMP)
        val wrongSecret = ByteArray(16) { (it + 1).toByte() }
        val output = ByteArrayOutputStream()
        val error = assertThrows(FakeTls.MaskingRequired::class.java) {
            codec().accept(ByteArrayInputStream(original), output, wrongSecret, domain)
        }
        assertEquals(FakeTls.Rejection.AUTHENTICATION, error.reason)
        assertEquals(0, output.size())
    }

    @Test
    fun `timestamp tolerance is inclusive and unsigned little endian`() {
        for (timestamp in listOf(now - 120, now, now + 120)) {
            codec().accept(ByteArrayInputStream(hello(timestamp)), ByteArrayOutputStream(), secret, domain)
        }
        val future = 0xf0000000L
        codec(clock = { future }).accept(
            ByteArrayInputStream(hello(timestamp = future)), ByteArrayOutputStream(), secret, domain
        )
    }

    @Test
    fun `repeat hello must be masked and authentication failure cannot poison replay cache`() {
        val codec = codec()
        val valid = hello()
        val tampered = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertRejected(tampered, FakeTls.Rejection.AUTHENTICATION, codec)
        codec.accept(ByteArrayInputStream(valid), ByteArrayOutputStream(), secret, domain)
        assertRejected(valid, FakeTls.Rejection.REPLAY, codec)
    }

    @Test
    fun `timestamp-only mutation cannot bypass replay detection`() {
        val original = hello()
        val mutated = original.copyOf().apply { this[39] = (this[39].toInt() xor 1).toByte() }
        // Changing the low timestamp bit still authenticates and remains in tolerance.
        codec().accept(ByteArrayInputStream(mutated), ByteArrayOutputStream(), secret, domain)
        val codec = codec()
        codec.accept(ByteArrayInputStream(original), ByteArrayOutputStream(), secret, domain)
        assertRejected(mutated, FakeTls.Rejection.REPLAY, codec)
    }

    @Test
    fun `replay cache expires by monotonic time and bounds authenticated entries`() {
        var monotonic = 0L
        val codec = codec(monotonic = { monotonic }, capacity = 2)
        val a = hello()
        codec.accept(ByteArrayInputStream(a), ByteArrayOutputStream(), secret, domain)
        monotonic = 240_999
        assertRejected(a, FakeTls.Rejection.REPLAY, codec)
        monotonic = 241_000
        codec.accept(ByteArrayInputStream(a), ByteArrayOutputStream(), secret, domain)
        // Distinct authenticated content, not merely distinct timestamp suffixes.
        val b = hello(session = ByteArray(32) { it.toByte() })
        val c = hello(session = ByteArray(32) { (it + 1).toByte() })
        for (hello in listOf(b, c, a)) {
            codec.accept(
                ByteArrayInputStream(hello), ByteArrayOutputStream(), secret, domain
            )
        }
    }

    @Test
    fun `parallel acceptance permits only one response for a hello`() {
        val codec = FakeTls.Codec(nowSeconds = { now }, delay = {}, appDataLength = { 64 })
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (0 until 8).map {
                executor.submit(Callable {
                    start.await()
                    try {
                        codec.accept(ByteArrayInputStream(hello()), ByteArrayOutputStream(), secret, domain)
                        true
                    } catch (e: FakeTls.MaskingRequired) {
                        assertEquals(FakeTls.Rejection.REPLAY, e.reason)
                        false
                    }
                })
            }
            start.countDown()
            assertEquals(1, tasks.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `oversized first record rejects before body and preserves header for masking`() {
        val header = byteArrayOf(0x16, 3, 1, 0x40, 1)
        val tail = byteArrayOf(9, 8, 7)
        val input = ByteArrayInputStream(header + tail)
        val output = ByteArrayOutputStream()
        val error = assertThrows(FakeTls.MaskingRequired::class.java) {
            codec().accept(input, output, secret, domain)
        }
        assertEquals(FakeTls.Rejection.MALFORMED, error.reason)
        assertArrayEquals(header, error.initialData)
        assertArrayEquals(tail, input.readBytes())
        assertEquals(0, output.size())
    }

    @Test
    fun `maximum first-record boundary is accepted without consuming next record`() {
        val baseLength = hello().size - 5
        val large = hello(padding = FakeTls.MAX_PAYLOAD - baseLength - 4)
        assertEquals(FakeTls.MAX_PAYLOAD + 5, large.size)
        val raw = ByteArrayInputStream(large + record(0x17, byteArrayOf(42)))
        val streams = codec().accept(raw, ByteArrayOutputStream(), secret, domain)
        assertEquals(42, streams.input.read())
        assertEquals(-1, streams.input.read())
    }

    @Test
    fun `malformed hello vectors and truncated handshake fail closed`() {
        val original = hello()
        val malformed = listOf(
            original.copyOf().apply { this[5] = 2 }, // Not a ClientHello.
            original.copyOf().apply { this[8] = (this[8] - 1).toByte() }, // Message length.
            original.copyOf().apply { this[43] = 33 }, // Oversized session ID.
            original.copyOf().apply { this[77] = 3 }, // Odd cipher-suite vector.
            original.copyOf().apply { this[84] = 0x7f }, // Oversized extensions vector.
            original.copyOf().apply { this[95] = 0x7f }, // Oversized key-share extension.
            original.copyOf().apply { this[97] = 0x7f }, // Oversized key-share vector.
            original.copyOf().apply { this[101] = 0x7f }, // Oversized key-exchange value.
        )
        malformed.forEach { assertRejected(it, FakeTls.Rejection.MALFORMED) }
        for (size in listOf(0, 1, 4, 5, 42, original.size - 1)) {
            val output = ByteArrayOutputStream()
            assertThrows(EOFException::class.java) {
                codec().accept(ByteArrayInputStream(original.copyOf(size)), output, secret, domain)
            }
            assertEquals(0, output.size())
        }
    }

    @Test
    fun `hybrid key share and cipher preference match current upstream`() {
        val cases = listOf(
            Triple(listOf(0x1302, 0x1303, 0x1301), 1216, 0x1301),
            Triple(listOf(0x1302, 0x1303), 1216, 0x1303),
            Triple(listOf(0x1302), 1215, 0x1302),
            Triple(listOf(0x002f), 32, 0x1301),
        )
        for ((suites, shareSize, expectedSuite) in cases) {
            val hello = hello(suites = suites, shares = listOf(0x11ec to shareSize))
            val output = ByteArrayOutputStream()
            codec().accept(ByteArrayInputStream(hello), output, secret, domain)
            val response = output.toByteArray()
            assertResponseHmac(hello, response)
            assertEquals(expectedSuite, word(response, 76))
            val hybrid = shareSize == 1216
            assertEquals(if (hybrid) 0x11ec else 0x001d, word(response, 91))
            assertEquals(if (hybrid) 1120 else 32, word(response, 93))
            val keyLastByte = response[95 + word(response, 93) - 1].toInt() and 255
            assertTrue(keyLastByte < 128)
            assertEquals(listOf(0x16, 0x14, 0x17), records(response).map { it.first })
        }
    }

    @Test
    fun `nonstandard session ID uses upstream zero session fallback`() {
        val output = ByteArrayOutputStream()
        codec().accept(
            ByteArrayInputStream(hello(session = byteArrayOf())), output, secret, domain
        )
        assertEquals(32, output.toByteArray()[43].toInt())
        assertArrayEquals(ByteArray(32), output.toByteArray().copyOfRange(44, 76))
    }

    @Test
    fun `server appdata profile boundaries have exactly one authenticated record`() {
        for (size in listOf(64, FakeTls.MAX_PAYLOAD)) {
            val hello = hello()
            val output = ByteArrayOutputStream()
            codec(appData = size).accept(ByteArrayInputStream(hello), output, secret, domain)
            val response = output.toByteArray()
            assertEquals(size, records(response).last().second.size)
            assertResponseHmac(hello, response)
        }
    }

    @Test
    fun `write splitting boundaries round trip with fragmented reads`() {
        for (size in listOf(0, 1, 16_383, 16_384, 16_385, 32_768, 65_535)) {
            val payload = ByteArray(size) { (it * 17).toByte() }
            val raw = ByteArrayOutputStream()
            val framed = FakeTls.RecordOutput(raw)
            framed.write(payload)
            framed.flush()
            val frames = records(raw.toByteArray())
            assertTrue(frames.all { it.first == 0x17 && it.second.size in 1..16_384 })
            assertEquals((size + 16_383) / 16_384, frames.size)
            assertArrayEquals(payload, FakeTls.RecordInput(FragmentedInput(raw.toByteArray())).readBytes())
        }
    }

    @Test
    fun `record input handles full unsigned length without allocating a record buffer`() {
        val payload = ByteArray(65_535) { it.toByte() }
        val input = FakeTls.RecordInput(FragmentedInput(record(0x17, payload)))
        assertArrayEquals(payload, input.readBytes())
        assertEquals(-1, input.read())
    }

    @Test
    fun `empty records and CCS are skipped while zero-length reads follow InputStream contract`() {
        val raw = record(0x14, byteArrayOf(1)) + record(0x17, byteArrayOf()) +
            record(0x17, byteArrayOf(0xff.toByte()))
        val input = FakeTls.RecordInput(ByteArrayInputStream(raw))
        assertEquals(0, input.read(ByteArray(0)))
        assertEquals(255, input.read())
        assertEquals(-1, input.read())
        assertEquals(0, input.read(ByteArray(0)))
    }

    @Test
    fun `malformed or truncated application records never silently expose raw bytes`() {
        val malformed = listOf(
            byteArrayOf(0x17, 3, 1, 0, 1, 42),
            record(0x16, byteArrayOf(42)),
            record(0x14, byteArrayOf(2)),
            record(0x14, byteArrayOf()),
        )
        malformed.forEach {
            assertThrows(ProtocolException::class.java) {
                FakeTls.RecordInput(ByteArrayInputStream(it)).readBytes()
            }
        }
        val truncated = listOf(
            byteArrayOf(0x17), byteArrayOf(0x17, 3, 3, 0),
            byteArrayOf(0x17, 3, 3, 0, 2, 42),
        )
        truncated.forEach {
            assertThrows(EOFException::class.java) {
                FakeTls.RecordInput(ByteArrayInputStream(it)).readBytes()
            }
        }
        val emptyFlood = ByteArrayOutputStream().apply {
            repeat(33) { write(record(0x17, byteArrayOf())) }
        }.toByteArray()
        assertThrows(ProtocolException::class.java) {
            FakeTls.RecordInput(ByteArrayInputStream(emptyFlood)).read()
        }
    }

    @Test
    fun `stream wrappers preserve close flush and array bounds contracts`() {
        var flushed = false
        var outputClosed = false
        var inputClosed = false
        val destination = object : ByteArrayOutputStream() {
            override fun flush() { flushed = true }
            override fun close() { outputClosed = true }
        }
        val source = object : ByteArrayInputStream(byteArrayOf()) {
            override fun close() { inputClosed = true }
        }
        val output = FakeTls.RecordOutput(destination)
        output.write(byteArrayOf(7, 8, 9), 1, 1)
        assertArrayEquals(record(0x17, byteArrayOf(8)), destination.toByteArray())
        assertThrows(IndexOutOfBoundsException::class.java) { output.write(ByteArray(2), 1, 2) }
        val input = FakeTls.RecordInput(source)
        assertThrows(IndexOutOfBoundsException::class.java) { input.read(ByteArray(2), -1, 1) }
        assertFalse(flushed)
        output.flush()
        output.close()
        input.close()
        assertTrue(flushed)
        assertTrue(outputClosed)
        assertTrue(inputClosed)
    }

    private fun codec(
        clock: () -> Long = { now },
        monotonic: () -> Long = { 0L },
        capacity: Int = 65_536,
        appData: Int = 64,
    ) = FakeTls.Codec(
        nowSeconds = clock,
        monotonicMillis = monotonic,
        random = CountingRandom(),
        delay = {},
        appDataLength = { appData },
        replayCapacity = capacity,
    )

    private fun assertRejected(
        hello: ByteArray,
        reason: FakeTls.Rejection,
        codec: FakeTls.Codec = codec(),
    ) {
        val output = ByteArrayOutputStream()
        val error = assertThrows(FakeTls.MaskingRequired::class.java) {
            codec.accept(ByteArrayInputStream(hello), output, secret, domain)
        }
        assertEquals(reason, error.reason)
        assertEquals(domain, error.domain)
        assertArrayEquals(hello, error.initialData)
        assertEquals(0, output.size())
    }

    private fun hello(
        timestamp: Long = now,
        suites: List<Int> = listOf(0x1302, 0x1303),
        shares: List<Pair<Int, Int>> = listOf(0x001d to 32),
        session: ByteArray = ByteArray(32) { (160 + it).toByte() },
        padding: Int? = null,
    ): ByteArray {
        val shareData = ByteArrayOutputStream().apply {
            for ((group, length) in shares) {
                word(group)
                word(length)
                write(ByteArray(length) { (it + 1).toByte() })
            }
        }.toByteArray()
        val extensions = ByteArrayOutputStream().apply {
            word(0x002b)
            word(3)
            write(byteArrayOf(2, 3, 4))
            word(0x0033)
            word(2 + shareData.size)
            word(shareData.size)
            write(shareData)
            if (padding != null) {
                word(0x0015)
                word(padding)
                write(ByteArray(padding))
            }
        }.toByteArray()
        val body = ByteArrayOutputStream().apply {
            word(0x0303)
            write(ByteArray(32))
            write(session.size)
            write(session)
            word(suites.size * 2)
            suites.forEach { word(it) }
            write(byteArrayOf(1, 0))
            word(extensions.size)
            write(extensions)
        }.toByteArray()
        val message = byteArrayOf(1, 0, (body.size shr 8).toByte(), body.size.toByte()) + body
        val hello = record(0x16, message).apply { this[2] = 1 }
        val random = hmac(hello)
        for (i in 0..3) random[28 + i] = (random[28 + i].toInt() xor (timestamp shr (8 * i)).toInt()).toByte()
        random.copyInto(hello, 11)
        return hello
    }

    private fun assertResponseHmac(hello: ByteArray, response: ByteArray) {
        val random = response.copyOfRange(11, 43)
        val zeroed = response.copyOf().apply { fill(0, 11, 43) }
        assertArrayEquals(hmac(hello.copyOfRange(11, 43) + zeroed), random)
    }

    private fun hmac(data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256"))
        doFinal(data)
    }

    private fun records(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val result = mutableListOf<Pair<Int, ByteArray>>()
        var offset = 0
        while (offset < bytes.size) {
            assertTrue(offset + 5 <= bytes.size)
            assertEquals(3, bytes[offset + 1].toInt())
            assertEquals(3, bytes[offset + 2].toInt())
            val length = word(bytes, offset + 3)
            assertTrue(offset + 5 + length <= bytes.size)
            result.add((bytes[offset].toInt() and 255) to bytes.copyOfRange(offset + 5, offset + 5 + length))
            offset += 5 + length
        }
        return result
    }

    private fun record(type: Int, payload: ByteArray) =
        byteArrayOf(type.toByte(), 3, 3, (payload.size shr 8).toByte(), payload.size.toByte()) + payload

    private fun word(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private fun ByteArrayOutputStream.word(value: Int) {
        write(value shr 8)
        write(value)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private class CountingRandom : SecureRandom() {
        private var next = 0
        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (next++).toByte()
        }
        override fun nextInt(bound: Int) = 0
    }

    private class FragmentedInput(bytes: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(bytes)
        private var count = 0
        override fun read() = delegate.read()
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            delegate.read(bytes, offset, minOf(length, 1 + count++ % 7))
    }
}
