package dev.minios.tgwsproxy.proxy

import java.io.IOException
import java.io.InputStream

/** Best-effort wire drain before closing a rejected client. */
internal fun drainRejectedHandshake(
    input: InputStream,
    setReadTimeout: (Int) -> Unit,
    clockMs: () -> Long = ::transportClockMs,
) {
    val startedAt = clockMs()
    var remainingBytes = 64 * 1024
    val buffer = ByteArray(4096)
    try {
        while (remainingBytes > 0) {
            val remainingMs = 10_000L - (clockMs() - startedAt)
            if (remainingMs <= 0) return
            setReadTimeout(remainingMs.toInt())
            val count = input.read(buffer, 0, minOf(buffer.size, remainingBytes))
            if (count <= 0) return
            remainingBytes -= count
        }
    } catch (_: IOException) {
        // A timeout or peer disconnect ends the drain; the caller closes the socket.
    }
}
