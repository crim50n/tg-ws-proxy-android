package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MsgSplitterTest {
    @Test
    fun buffersPartialIntermediatePacket() {
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_INTERMEDIATE, 2)
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE, relay.initPacket)
        val plain = intermediatePacket(ByteArray(32) { it.toByte() })
        val encrypted = relay.telegramEncryptor.update(plain)

        assertTrue(splitter.split(encrypted.copyOfRange(0, 3)).isEmpty())
        val parts = splitter.split(encrypted.copyOfRange(3, encrypted.size))

        assertEquals(1, parts.size)
        assertArrayEquals(encrypted, parts.single())
    }

    @Test
    fun splitsMultipleIntermediatePackets() {
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_INTERMEDIATE, 2)
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE, relay.initPacket)
        val first = intermediatePacket(ByteArray(12) { 0x11 })
        val second = intermediatePacket(ByteArray(20) { 0x22 })
        val encrypted = relay.telegramEncryptor.update(first + second)

        val parts = splitter.split(encrypted)

        assertEquals(2, parts.size)
        assertArrayEquals(encrypted.copyOfRange(0, first.size), parts[0])
        assertArrayEquals(encrypted.copyOfRange(first.size, encrypted.size), parts[1])
    }

    @Test(expected = ProxyException::class)
    fun rejectsOversizedIntermediatePacket() {
        val relay = MtProtoCrypto.generateRelayInit(MtProtoConstants.TAG_INTERMEDIATE, 2)
        val splitter = MsgSplitter(MtProtoConstants.TAG_INTERMEDIATE, relay.initPacket)
        val header = ByteArray(4)
        MtProtoCrypto.writeInt32LE(header, 0, MsgSplitter.MAX_PACKET_SIZE + 1)

        splitter.split(relay.telegramEncryptor.update(header))
    }

    private fun intermediatePacket(payload: ByteArray): ByteArray {
        return ByteArray(4 + payload.size).also {
            MtProtoCrypto.writeInt32LE(it, 0, payload.size)
            System.arraycopy(payload, 0, it, 4, payload.size)
        }
    }
}
