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

        // Append to buffers
        cipherBuf = cipherBuf + chunk
        plainBuf = plainBuf + shadowDecryptor.update(chunk)

        val parts = mutableListOf<ByteArray>()
        while (cipherBuf.isNotEmpty()) {
            val packetLen = nextPacketLen()
            if (packetLen == null) {
                // Not enough data yet — wait for more
                break
            }
            if (packetLen <= 0) {
                // Unrecognized protocol — send remaining as single chunk, disable splitting
                parts.add(cipherBuf.copyOf())
                cipherBuf = ByteArray(0)
                plainBuf = ByteArray(0)
                disabled = true
                break
            }
            // Extract one complete packet
            parts.add(cipherBuf.copyOfRange(0, packetLen))
            cipherBuf = cipherBuf.copyOfRange(packetLen, cipherBuf.size)
            plainBuf = plainBuf.copyOfRange(packetLen, plainBuf.size)
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
    private fun nextPacketLen(): Int? {
        if (plainBuf.isEmpty()) return null
        return when (proto) {
            MtProtoConstants.TAG_ABRIDGED -> nextAbridgedLen()
            MtProtoConstants.TAG_INTERMEDIATE,
            MtProtoConstants.TAG_PADDED_INTERMEDIATE -> nextIntermediateLen()
            else -> 0 // Unknown protocol, disable splitting
        }
    }

    /**
     * Parse abridged protocol length header.
     * First byte: if < 0x7F, payload length = byte * 4 (header = 1 byte)
     *             if 0x7F or 0xFF, next 3 bytes = LE length * 4 (header = 4 bytes)
     */
    private fun nextAbridgedLen(): Int? {
        val first = plainBuf[0].toInt() and 0xFF
        val headerLen: Int
        val payloadLen: Int

        if (first == 0x7F || first == 0xFF) {
            if (plainBuf.size < 4) return null
            payloadLen = ((plainBuf[1].toInt() and 0xFF) or
                    ((plainBuf[2].toInt() and 0xFF) shl 8) or
                    ((plainBuf[3].toInt() and 0xFF) shl 16)) * 4
            headerLen = 4
        } else {
            payloadLen = (first and 0x7F) * 4
            headerLen = 1
        }

        if (payloadLen <= 0) return 0
        val packetLen = headerLen + payloadLen
        return if (plainBuf.size < packetLen) null else packetLen
    }

    /**
     * Parse intermediate protocol length header.
     * First 4 bytes = LE uint32, masked with 0x7FFFFFFF = payload length.
     */
    private fun nextIntermediateLen(): Int? {
        if (plainBuf.size < 4) return null
        val payloadLen = (MtProtoCrypto.readInt32LE(plainBuf, 0).toLong() and 0x7FFFFFFFL).toInt()
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        return if (plainBuf.size < packetLen) null else packetLen
    }
}
