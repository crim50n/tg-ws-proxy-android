package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MsgSplitterTest {
    @Test
    fun abridgedShortAndExtendedHeadersWithQuickack() {
        for (quickack in listOf(false, true)) {
            for (words in listOf(1, 126, 127, 256)) {
                assertSplit(MtProtoConstants.TAG_ABRIDGED, listOf(abridgedPacket(words, quickack)))
            }
        }
    }

    @Test
    fun intermediateAndPaddedPacketsWithQuickackAndUnalignedPayloads() {
        for (tag in listOf(MtProtoConstants.TAG_INTERMEDIATE, MtProtoConstants.TAG_PADDED_INTERMEDIATE)) {
            for (quickack in listOf(false, true)) {
                assertSplit(tag, listOf(intermediatePacket(13, quickack), intermediatePacket(32)))
            }
        }
    }

    @Test
    fun splitHeadersAndBodiesAtEveryByteBoundary() {
        val cases = listOf(
            MtProtoConstants.TAG_ABRIDGED to abridgedPacket(1, true),
            MtProtoConstants.TAG_ABRIDGED to abridgedPacket(127, true),
            MtProtoConstants.TAG_INTERMEDIATE to intermediatePacket(12, true),
            MtProtoConstants.TAG_PADDED_INTERMEDIATE to intermediatePacket(13, true),
        )
        for ((tag, packet) in cases) {
            for (boundary in 1 until packet.size) {
                assertSplit(tag, listOf(packet), listOf(boundary, packet.size - boundary))
            }
        }
    }

    @Test
    fun multiplePacketsAndPartialNextPacketStayByteAligned() {
        for (tag in listOf(MtProtoConstants.TAG_ABRIDGED, MtProtoConstants.TAG_INTERMEDIATE, MtProtoConstants.TAG_PADDED_INTERMEDIATE)) {
            val packets = if (tag == MtProtoConstants.TAG_ABRIDGED) {
                listOf(abridgedPacket(2), abridgedPacket(127, true), abridgedPacket(3, true))
            } else {
                listOf(intermediatePacket(12), intermediatePacket(17, true), intermediatePacket(20))
            }
            assertSplit(tag, packets)
            assertSplit(tag, packets, listOf(packets[0].size + 2, 3, packets.sumOf { it.size } - packets[0].size - 5))
            assertSplit(tag, packets, List(packets.sumOf { it.size }) { 1 })
        }
    }

    @Test
    fun rejectsOversizedPayloadsAsSoonAsHeaderIsComplete() {
        val cases = listOf(
            MtProtoConstants.TAG_ABRIDGED to byteArrayOf(0xff.toByte(), 1, 0, 0x40),
            MtProtoConstants.TAG_INTERMEDIATE to intermediateHeader(MsgSplitter.MAX_PACKET_SIZE + 1),
            MtProtoConstants.TAG_PADDED_INTERMEDIATE to intermediateHeader((MsgSplitter.MAX_PACKET_SIZE + 1) or Int.MIN_VALUE),
        )
        for ((tag, header) in cases) {
            val splitter = MsgSplitter(tag)
            assertTrue(splitter.split(byteArrayOf(42), header.copyOfRange(0, 1)).isEmpty())
            assertThrows(ProxyException::class.java) {
                splitter.split(ByteArray(3), header.copyOfRange(1, 4))
            }
        }
    }

    @Test
    fun acceptsMaximumPayloadSize() {
        for (tag in listOf(MtProtoConstants.TAG_ABRIDGED, MtProtoConstants.TAG_INTERMEDIATE, MtProtoConstants.TAG_PADDED_INTERMEDIATE)) {
            val header = if (tag == MtProtoConstants.TAG_ABRIDGED) {
                byteArrayOf(0x7f, 0, 0, 0x40)
            } else {
                intermediateHeader(MsgSplitter.MAX_PACKET_SIZE)
            }
            val plain = header + ByteArray(MsgSplitter.MAX_PACKET_SIZE)
            val encrypted = ByteArray(plain.size) { 0x55 }
            val splitter = MsgSplitter(tag)
            assertArrayEquals(encrypted, splitter.split(encrypted, plain).single())
            assertTrue(splitter.flush().isEmpty())
        }
    }

    @Test
    fun eofFlushPreservesIncompleteHeadersAndBodies() {
        for ((tag, packet) in listOf(
            MtProtoConstants.TAG_ABRIDGED to abridgedPacket(127),
            MtProtoConstants.TAG_INTERMEDIATE to intermediatePacket(12),
            MtProtoConstants.TAG_PADDED_INTERMEDIATE to intermediatePacket(13),
        )) {
            for (length in listOf(1, 2, 3, 4, packet.size - 1)) {
                val splitter = MsgSplitter(tag)
                val plain = packet.copyOf(length)
                val encrypted = ByteArray(length) { (it xor 0x5a).toByte() }
                assertTrue(splitter.split(encrypted, plain).isEmpty())
                assertTrue(splitter.split(ByteArray(0), ByteArray(0)).isEmpty())
                assertArrayEquals(encrypted, splitter.flush().single())
                assertTrue(splitter.flush().isEmpty())
                assertArrayEquals(packet, splitter.split(packet, packet).single())
            }
        }
    }

    @Test
    fun eofFlushContainsOnlyTailAfterCompletePacket() {
        val packet = intermediatePacket(12)
        val plain = packet + intermediatePacket(20).copyOf(7)
        val encrypted = ByteArray(plain.size) { (it + 100).toByte() }
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE)
        assertArrayEquals(encrypted.copyOfRange(0, packet.size), splitter.split(encrypted, plain).single())
        assertArrayEquals(encrypted.copyOfRange(packet.size, encrypted.size), splitter.flush().single())
        assertTrue(splitter.flush().isEmpty())
    }

    @Test
    fun unknownProtocolAndZeroLengthsDisableSplittingWithoutLosingBytes() {
        for ((tag, invalid) in listOf(
            0 to byteArrayOf(1, 2, 3),
            MtProtoConstants.TAG_ABRIDGED to byteArrayOf(0x80.toByte()),
            MtProtoConstants.TAG_ABRIDGED to byteArrayOf(0xff.toByte(), 0, 0, 0),
            MtProtoConstants.TAG_INTERMEDIATE to intermediateHeader(Int.MIN_VALUE),
            MtProtoConstants.TAG_PADDED_INTERMEDIATE to intermediateHeader(0),
        )) {
            val splitter = MsgSplitter(tag)
            val encrypted = ByteArray(invalid.size) { 0x55 }
            assertArrayEquals(encrypted, splitter.split(encrypted, invalid).single())
            assertArrayEquals(byteArrayOf(42), splitter.split(byteArrayOf(42), byteArrayOf(0)).single())
            assertTrue(splitter.flush().isEmpty())
        }
    }

    @Test
    fun invalidHeaderAfterCompletePacketFlushesBufferedRemainder() {
        val packet = intermediatePacket(12)
        val invalidTail = intermediateHeader(0) + byteArrayOf(1, 2, 3)
        val plain = packet + invalidTail
        val encrypted = ByteArray(plain.size) { (it + 100).toByte() }
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE)
        val boundary = packet.size + 2
        assertArrayEquals(encrypted.copyOfRange(0, packet.size), splitter.split(
            encrypted.copyOfRange(0, boundary), plain.copyOfRange(0, boundary),
        ).single())
        assertArrayEquals(encrypted.copyOfRange(packet.size, encrypted.size), splitter.split(
            encrypted.copyOfRange(boundary, encrypted.size), plain.copyOfRange(boundary, plain.size),
        ).single())
        assertArrayEquals(byteArrayOf(42), splitter.split(byteArrayOf(42), byteArrayOf(0)).single())
        assertTrue(splitter.flush().isEmpty())
    }

    @Test
    fun validatesAlignmentBeforeChangingBufferOrPassingThrough() {
        for (tag in listOf(0, MtProtoConstants.TAG_INTERMEDIATE)) {
            val splitter = MsgSplitter(tag)
            assertThrows(IllegalArgumentException::class.java) { splitter.split(byteArrayOf(1), ByteArray(0)) }
            assertThrows(IllegalArgumentException::class.java) { splitter.split(ByteArray(0), byteArrayOf(1)) }
            assertTrue(splitter.flush().isEmpty())
        }
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE)
        assertTrue(splitter.split(byteArrayOf(42), byteArrayOf(12)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { splitter.split(byteArrayOf(1), ByteArray(0)) }
        assertArrayEquals(byteArrayOf(42), splitter.flush().single())
        val passthrough = MsgSplitter(0)
        passthrough.split(byteArrayOf(1), byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { passthrough.split(byteArrayOf(1), ByteArray(0)) }
    }

    @Test
    fun bufferedChunksDoNotAliasCallerArrays() {
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE)
        val plain = intermediateHeader(12)
        val encrypted = byteArrayOf(1, 2, 3, 4)
        assertTrue(splitter.split(encrypted, plain).isEmpty())
        plain.fill(0)
        encrypted.fill(0)
        val parts = splitter.split(ByteArray(12) { 42 }, ByteArray(12))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4) + ByteArray(12) { 42 }, parts.single())
    }

    private fun assertSplit(tag: Int, packets: List<ByteArray>, chunkSizes: List<Int> = listOf(packets.sumOf { it.size })) {
        val plain = packets.fold(ByteArray(0)) { acc, packet -> acc + packet }
        val relay = MtProtoCrypto.generateRelayInit(tag, 2)
        val encrypted = relay.telegramEncryptor.update(plain)
        val splitter = MsgSplitter(tag)
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        for (size in chunkSizes) {
            parts += splitter.split(encrypted.copyOfRange(offset, offset + size), plain.copyOfRange(offset, offset + size))
            offset += size
        }
        assertEquals(plain.size, offset)
        assertEquals(packets.size, parts.size)
        offset = 0
        for ((index, packet) in packets.withIndex()) {
            assertArrayEquals(encrypted.copyOfRange(offset, offset + packet.size), parts[index])
            offset += packet.size
        }
        assertTrue(splitter.flush().isEmpty())
    }

    private fun abridgedPacket(words: Int, quickack: Boolean = false): ByteArray {
        val flag = if (quickack) 0x80 else 0
        val header = if (words < 127) byteArrayOf((words or flag).toByte()) else {
            byteArrayOf((0x7f or flag).toByte(), words.toByte(), (words shr 8).toByte(), (words shr 16).toByte())
        }
        return header + ByteArray(words * 4) { (it * 31).toByte() }
    }

    private fun intermediatePacket(size: Int, quickack: Boolean = false): ByteArray =
        intermediateHeader(size or if (quickack) Int.MIN_VALUE else 0) + ByteArray(size) { (it * 31).toByte() }

    private fun intermediateHeader(length: Int): ByteArray = ByteArray(4).also {
        MtProtoCrypto.writeInt32LE(it, 0, length)
    }
}
