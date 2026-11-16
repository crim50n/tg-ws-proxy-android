package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.*
import org.junit.Test

class NativeHttpCodecTest {
    private fun decode(bytes: ByteArray, tag: Int) = NativeHttpCodec.readPacket(ByteArrayInputStream(bytes), { it }, tag)

    @Test fun intermediateQuickAckBitIsNotSentToHttp() {
        val body = ByteArray(24) { (it + 1).toByte() }
        assertArrayEquals(body, decode(NativeHttpCodec.bytesLE(body.size or Int.MIN_VALUE) + body,
            MtProtoConstants.TAG_INTERMEDIATE))
    }

    @Test fun shortAndLongAbridgedPacketsRoundTrip() {
        for (size in listOf(24, 504, 508, 4096, NativeHttpCodec.MAX_PACKET)) {
            val body = ByteArray(size) { 0x42 }
            val framed = NativeHttpCodec.encodeReply(body, MtProtoConstants.TAG_ABRIDGED)
            framed[0] = (framed[0].toInt() or 128).toByte()
            assertArrayEquals(body, decode(framed, MtProtoConstants.TAG_ABRIDGED))
        }
    }

    @Test fun paddedPlainPacketUsesDeclaredPayloadLength() {
        val body = ByteArray(24)
        NativeHttpCodec.bytesLE(4).copyInto(body, 16)
        val padded = body + ByteArray(256) { 0x55 }
        assertArrayEquals(body, decode(NativeHttpCodec.bytesLE(padded.size) + padded,
            MtProtoConstants.TAG_PADDED_INTERMEDIATE))
    }

    @Test fun paddedEncryptedPacketUsesBlockAlignment() {
        val body = ByteArray(40) { 0x12 }
        val padded = body + ByteArray(15) { 0x55 }
        assertArrayEquals(body, decode(NativeHttpCodec.bytesLE(padded.size) + padded,
            MtProtoConstants.TAG_PADDED_INTERMEDIATE))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPlainPaddingLength() {
        val body = ByteArray(28)
        NativeHttpCodec.bytesLE(100).copyInto(body, 16)
        decode(NativeHttpCodec.bytesLE(body.size) + body, MtProtoConstants.TAG_PADDED_INTERMEDIATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedFrameBeforeReadingPayload() {
        decode(NativeHttpCodec.bytesLE(NativeHttpCodec.MAX_PACKET + 4), MtProtoConstants.TAG_INTERMEDIATE)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnalignedHttpReplies() {
        NativeHttpCodec.encodeReply(ByteArray(7), MtProtoConstants.TAG_INTERMEDIATE)
    }

    @Test(expected = EOFException::class)
    fun incompletePayloadFailsWithoutEmittingPacket() {
        decode(NativeHttpCodec.bytesLE(24) + ByteArray(12), MtProtoConstants.TAG_INTERMEDIATE)
    }

    @Test fun mtprotoTransportErrorIsDeliveredWithNativeFraming() {
        val error = NativeHttpCodec.bytesLE(-429)
        assertArrayEquals(byteArrayOf(1) + error, NativeHttpCodec.encodeReply(error, MtProtoConstants.TAG_ABRIDGED))
        assertArrayEquals(NativeHttpCodec.bytesLE(4) + error,
            NativeHttpCodec.encodeReply(error, MtProtoConstants.TAG_PADDED_INTERMEDIATE))
    }

    @Test fun fragmentedInputDecryptsEveryByteExactlyOnce() {
        val body = ByteArray(24) { it.toByte() }
        val encoded = NativeHttpCodec.bytesLE(body.size) + body
        var decrypted = 0
        val input = object : ByteArrayInputStream(encoded) {
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, minOf(1, len))
        }
        assertArrayEquals(body, NativeHttpCodec.readPacket(input, {
            decrypted += it.size
            it
        }, MtProtoConstants.TAG_INTERMEDIATE))
        assertEquals(encoded.size, decrypted)
    }
}
