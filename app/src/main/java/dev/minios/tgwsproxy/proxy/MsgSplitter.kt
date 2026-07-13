package dev.minios.tgwsproxy.proxy

/**
 * MTProto message splitter for splitting TCP stream into individual WS frames.
 * Parses protocol-specific length headers through a shadow AES-CTR decryptor.
 *
 * Matches the Python MsgSplitter in bridge.py exactly:
 * - Creates a shadow decryptor from relay_init to read length headers
 * - Maintains cipher_buf (encrypted) and plain_buf (decrypted) buffers
 * - Splits stream by abridged or intermediate protocol packet boundaries
 * - Falls back to passthrough mode on unrecognized protocol
 */
class MsgSplitter(
    protoTag: Int,
    relayInit: ByteArray,
) {
    companion object {
        internal const val MAX_PACKET_SIZE = 16 * 1024 * 1024
    }

    // Shadow decryptor — same key derivation as relay encryptor,
    // fast-forwarded past the 64-byte init (matching Python)
    private val shadowDecryptor: AesCtr

    private val proto: Int = protoTag
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

    init {
        // Extract key/IV from relay_init at same positions as relay encryptor
        val encKey = relayInit.sliceArray(
            MtProtoConstants.SKIP_LEN until
                    MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN
        )
        val encIv = relayInit.sliceArray(
            MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN until
                    MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        )
        shadowDecryptor = AesCtr(encKey, encIv)
        // Fast-forward past 64-byte init (matching Python: self._dec.update(ZERO_64))
        shadowDecryptor.update(ByteArray(MtProtoConstants.HANDSHAKE_LEN))
    }

    /**
     * Split encrypted data into individual MTProto transport packets.
     * Each returned ByteArray is a complete packet to be sent as a WS frame.
     */
    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipherBuf = append(cipherBuf, chunk)
        plainBuf = append(plainBuf, shadowDecryptor.update(chunk))

        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < cipherBuf.size) {
            val packetLen = nextPacketLen(offset)
            if (packetLen == null) {
                // Not enough data yet — wait for more
                break
            }
            if (packetLen <= 0) {
                // Unrecognized protocol — send remaining as single chunk, disable splitting
                parts.add(cipherBuf.copyOfRange(offset, cipherBuf.size))
                cipherBuf = ByteArray(0)
                plainBuf = ByteArray(0)
                disabled = true
                return parts
            }
            // Extract one complete packet
            parts.add(cipherBuf.copyOfRange(offset, offset + packetLen))
            offset += packetLen
        }

        if (offset > 0) {
            cipherBuf = cipherBuf.copyOfRange(offset, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(offset, plainBuf.size)
        }
        if (cipherBuf.size > MAX_PACKET_SIZE + 4) {
            throw ProxyException("MTProto splitter buffer too large")
        }
        return parts
    }

    /**
     * Flush remaining buffered data (called on stream EOF).
     */
    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf.copyOf()
        cipherBuf = ByteArray(0)
        plainBuf = ByteArray(0)
        return listOf(tail)
    }

    /**
     * Determine the length of the next complete packet in plainBuf.
     * Returns null if not enough data, 0 if protocol is unknown (disable splitting),
     * or the packet length in bytes.
     */
    private fun nextPacketLen(offset: Int): Int? {
        if (offset >= plainBuf.size) return null
        return when (proto) {
            MtProtoConstants.TAG_ABRIDGED -> nextAbridgedLen(offset)
            MtProtoConstants.TAG_INTERMEDIATE,
            MtProtoConstants.TAG_PADDED_INTERMEDIATE -> nextIntermediateLen(offset)
            else -> 0 // Unknown protocol, disable splitting
        }
    }

    /**
     * Parse abridged protocol length header.
     * First byte: if < 0x7F, payload length = byte * 4 (header = 1 byte)
     *             if 0x7F or 0xFF, next 3 bytes = LE length * 4 (header = 4 bytes)
     */
    private fun nextAbridgedLen(offset: Int): Int? {
        val first = plainBuf[offset].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int

        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size - offset < 4) return null
            payloadLen = ((plainBuf[offset + 1].toInt() and 0xFF) or
                    ((plainBuf[offset + 2].toInt() and 0xFF) shl 8) or
                    ((plainBuf[offset + 3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }

        if (payloadLen <= 0) return 0
        if (payloadLen > MAX_PACKET_SIZE) {
            throw ProxyException("MTProto packet too large: $payloadLen")
        }
        val packetLen = headerLen + payloadLen
        return if (plainBuf.size - offset < packetLen) null else packetLen
    }

    /**
     * Parse intermediate protocol length header.
     * First 4 bytes = LE uint32, masked with 0x7FFFFFFF = payload length.
     */
    private fun nextIntermediateLen(offset: Int): Int? {
        if (plainBuf.size - offset < 4) return null
        val payloadLen = MtProtoCrypto.readInt32LE(plainBuf, offset).toLong() and 0x7FFFFFFFL
        if (payloadLen <= 0) return 0
        if (payloadLen > MAX_PACKET_SIZE) {
            throw ProxyException("MTProto packet too large: $payloadLen")
        }
        val packetLen = 4 + payloadLen.toInt()
        return if (plainBuf.size - offset < packetLen) null else packetLen
    }

    private fun append(current: ByteArray, chunk: ByteArray): ByteArray {
        if (current.isEmpty()) return chunk.copyOf()
        return ByteArray(current.size + chunk.size).also {
            System.arraycopy(current, 0, it, 0, current.size)
            System.arraycopy(chunk, 0, it, current.size, chunk.size)
        }
    }
}
