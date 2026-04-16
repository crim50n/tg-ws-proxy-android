package dev.minios.tgwsproxy.proxy

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-CTR cipher wrapper for MTProto re-encryption.
 */
class AesCtr(key: ByteArray, iv: ByteArray) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    fun update(data: ByteArray): ByteArray = cipher.update(data)
}

/**
 * Crypto context for a proxied connection.
 * Holds 4 AES-CTR ciphers: client decrypt/encrypt, telegram decrypt/encrypt.
 */
class CryptoCtx(
    val clientDecryptor: AesCtr,
    val clientEncryptor: AesCtr,
    val telegramDecryptor: AesCtr,
    val telegramEncryptor: AesCtr,
)

/**
 * MTProto obfuscation handshake parser and re-encryption setup.
 */
object MtProtoCrypto {
    private val ZERO_64 = ByteArray(64)

    /**
     * Derive AES key from prekey + secret using SHA-256.
     */
    fun deriveKey(prekey: ByteArray, secret: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prekey)
        md.update(secret)
        return md.digest()
    }

    /**
     * Parse a 64-byte MTProto obfuscation handshake.
     * Returns the protocol tag, DC index, and CryptoCtx, or null if invalid.
     */
    fun parseHandshake(
        initData: ByteArray,
        secret: ByteArray,
    ): HandshakeResult? {
        if (initData.size != MtProtoConstants.HANDSHAKE_LEN) return null

        // Extract prekey (bytes 8..39) and IV (bytes 40..55)
        val prekey = initData.sliceArray(MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
        val iv = initData.sliceArray(MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)

        // Derive decryption key
        val decKey = deriveKey(prekey, secret)
        val decryptor = AesCtr(decKey, iv)

        // Decrypt the handshake
        val decrypted = decryptor.update(initData)

        // Check protocol tag at position 56
        val protoTag = readInt32LE(decrypted, MtProtoConstants.PROTO_TAG_POS)
        if (protoTag != MtProtoConstants.TAG_ABRIDGED &&
            protoTag != MtProtoConstants.TAG_INTERMEDIATE &&
            protoTag != MtProtoConstants.TAG_PADDED_INTERMEDIATE
        ) {
            return null
        }

        // Extract DC index at position 60 (signed int16 LE)
        val dcIdx = readInt16LE(decrypted, MtProtoConstants.DC_IDX_POS)

        // Setup client-side crypto
        // Client decryptor: SHA256(prekey + secret), IV from handshake
        val clientDecKey = deriveKey(prekey, secret)
        val clientDecryptor = AesCtr(clientDecKey, iv)
        // Fast-forward past the initial 64 bytes
        clientDecryptor.update(ZERO_64)

        // Client encryptor: reverse prekey+IV, then SHA256(reversed + secret)
        val encPrekeyIv = initData.sliceArray(
            MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
        ).reversedArray()
        val encPrekey = encPrekeyIv.sliceArray(0 until MtProtoConstants.PREKEY_LEN)
        val encIv = encPrekeyIv.sliceArray(MtProtoConstants.PREKEY_LEN until MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)
        val clientEncKey = deriveKey(encPrekey, secret)
        val clientEncryptor = AesCtr(clientEncKey, encIv)

        return HandshakeResult(
            protoTag = protoTag,
            dcIndex = dcIdx,
            clientDecryptor = clientDecryptor,
            clientEncryptor = clientEncryptor,
        )
    }

    /**
     * Generate a relay init packet (64 bytes) for the upstream connection.
     * Avoids reserved patterns. Returns the init packet and the relay crypto context.
     *
     * IMPORTANT: Relay side uses raw keys (no SHA-256 hashing with secret),
     * unlike the client side which hashes prekey+secret.
     */
    fun generateRelayInit(protoTag: Int, dcIndex: Short): RelayInitResult {
        val rng = java.security.SecureRandom()
        while (true) {
            val relayInit = ByteArray(64).also { rng.nextBytes(it) }

            // Check reserved first bytes
            if (relayInit[0] in MtProtoConstants.RESERVED_FIRST_BYTES) continue

            // Check reserved starts (includes HEAD, POST, GET, 0xEEEEEEEE, 0xDDDDDDDD, TLS header)
            val first4 = relayInit.sliceArray(0 until 4)
            if (MtProtoConstants.RESERVED_STARTS.any { it.contentEquals(first4) }) continue

            // Check reserved continue (bytes 4..7 == 0x00000000)
            if (relayInit[4] == 0.toByte() && relayInit[5] == 0.toByte() &&
                relayInit[6] == 0.toByte() && relayInit[7] == 0.toByte()) continue

            // Extract raw key and IV (NO sha256 for relay side!)
            val encKey = relayInit.sliceArray(MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN)
            val encIv = relayInit.sliceArray(MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN)

            // Create encryptor with raw key
            val encryptor = AesCtr(encKey, encIv)

            // Prepare plaintext tail: proto_tag (4 bytes) + dc_idx (2 bytes) + random (2 bytes)
            val dcBytes = ByteArray(2)
            writeInt16LE(dcBytes, 0, dcIndex)
            val randomPad = ByteArray(2).also { rng.nextBytes(it) }
            val tailPlain = ByteArray(8)
            writeInt32LE(tailPlain, 0, protoTag)
            System.arraycopy(dcBytes, 0, tailPlain, 4, 2)
            System.arraycopy(randomPad, 0, tailPlain, 6, 2)

            // Get keystream by encrypting the full init, then XOR to get encrypted tail
            val encryptedFull = encryptor.update(relayInit.clone())
            val keystreamTail = ByteArray(8) { i ->
                (encryptedFull[56 + i].toInt() xor relayInit[56 + i].toInt()).toByte()
            }
            val encryptedTail = ByteArray(8) { i ->
                (tailPlain[i].toInt() xor keystreamTail[i].toInt()).toByte()
            }

            // Write encrypted tail into relay init at positions 56..63
            System.arraycopy(encryptedTail, 0, relayInit, MtProtoConstants.PROTO_TAG_POS, 8)

            // Create encryptor for actual data (same raw key), fast-forward past 64 bytes
            val tgEncryptor = AesCtr(encKey, encIv)
            tgEncryptor.update(ZERO_64)

            // Derive relay decryption keys: reverse prekey+IV, use raw key (no sha256)
            val revPrekeyIv = relayInit.sliceArray(
                MtProtoConstants.SKIP_LEN until MtProtoConstants.SKIP_LEN + MtProtoConstants.PREKEY_LEN + MtProtoConstants.IV_LEN
            ).reversedArray()
            val tgDecKey = revPrekeyIv.sliceArray(0 until MtProtoConstants.KEY_LEN)
            val tgDecIv = revPrekeyIv.sliceArray(MtProtoConstants.KEY_LEN until MtProtoConstants.KEY_LEN + MtProtoConstants.IV_LEN)
            val tgDecryptor = AesCtr(tgDecKey, tgDecIv)

            return RelayInitResult(
                initPacket = relayInit,
                telegramEncryptor = tgEncryptor,
                telegramDecryptor = tgDecryptor,
            )
        }
    }

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    fun readInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun readInt16LE(data: ByteArray, offset: Int): Short {
        return ((data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort()
    }

    fun writeInt32LE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun writeInt16LE(data: ByteArray, offset: Int, value: Short) {
        data[offset] = (value.toInt() and 0xFF).toByte()
        data[offset + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }
}

data class HandshakeResult(
    val protoTag: Int,
    val dcIndex: Short,
    val clientDecryptor: AesCtr,
    val clientEncryptor: AesCtr,
)

data class RelayInitResult(
    val initPacket: ByteArray,
    val telegramEncryptor: AesCtr,
    val telegramDecryptor: AesCtr,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
