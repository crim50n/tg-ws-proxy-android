package dev.minios.tgwsproxy.proxy

/**
 * MTProto protocol constants matching the Python implementation.
 */
object MtProtoConstants {
    const val HANDSHAKE_LEN = 64
    const val SKIP_LEN = 8
    const val PREKEY_LEN = 32
    const val KEY_LEN = 32
    const val IV_LEN = 16
    const val PROTO_TAG_POS = 56
    const val DC_IDX_POS = 60

    // Protocol tags (as Int, big-endian)
    const val TAG_ABRIDGED: Int = 0xEFEFEFEF.toInt()
    const val TAG_INTERMEDIATE: Int = 0xEEEEEEEE.toInt()
    const val TAG_PADDED_INTERMEDIATE: Int = 0xDDDDDDDD.toInt()

    // Reserved first bytes
    val RESERVED_FIRST_BYTES = setOf(0xEF.toByte())
    val RESERVED_STARTS = listOf(
        "HEAD".toByteArray(),
        "POST".toByteArray(),
        "GET ".toByteArray(),
        byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
        byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
        byteArrayOf(0x16, 0x03, 0x01, 0x02),
    )

    // DC IPs (fallback for direct TCP)
    val DC_IPS = mapOf(
        1 to "149.154.175.50",
        2 to "149.154.167.51",
        3 to "149.154.175.100",
        4 to "149.154.167.91",
        5 to "149.154.171.5",
        203 to "91.105.192.100",
    )

    // Default DC redirects for WebSocket routing
    val DEFAULT_DC_REDIRECTS = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    )

    // WebSocket domains
    fun wsDomainsForDc(dc: Int, isMedia: Boolean): List<String> {
        val wsDc = if (dc == 203) 2 else dc
        return if (isMedia) {
            listOf("kws${wsDc}-1.web.telegram.org", "kws${wsDc}.web.telegram.org")
        } else {
            listOf("kws${wsDc}.web.telegram.org", "kws${wsDc}-1.web.telegram.org")
        }
    }

    // All valid DCs for pool warmup
    val ALL_DCS = listOf(1, 2, 3, 4, 5, 203)

    /**
     * Format byte counters using compact binary units.
     * - All units use 1 decimal place (including GB)
     * - Includes TB support
     * Python: for unit in ('B','KB','MB','GB'): if abs(n)<1024 return f"{n:.1f}{unit}"; n/=1024; return f"{n:.1f}TB"
     */
    fun humanBytes(bytes: Long): String {
        var n = bytes.toDouble()
        for (unit in arrayOf("B", "KB", "MB", "GB")) {
            if (kotlin.math.abs(n) < 1024) return "%.1f%s".format(n, unit)
            n /= 1024.0
        }
        return "%.1fTB".format(n)
    }
}
