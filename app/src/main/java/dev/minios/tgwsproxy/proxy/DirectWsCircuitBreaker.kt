package dev.minios.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap

internal data class DirectWsStallResult(
    val strikes: Int,
    val activated: Boolean,
    val blockedForMs: Long,
)

internal class DirectWsCircuitBreaker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Slot(val dc: Int, val media: Boolean)
    private data class State(val strikes: Int, val lastStallAt: Long, val blockedUntil: Long)

    private val states = ConcurrentHashMap<Slot, State>()

    fun isBlocked(dc: Int, isMedia: Boolean): Boolean {
        val slot = Slot(dc, isMedia)
        val state = states[slot] ?: return false
        val now = clock()
        if (state.blockedUntil > now) return true
        if (now - state.lastStallAt > STRIKE_WINDOW_MS) states.remove(slot, state)
        return false
    }

    fun recordStall(dc: Int, isMedia: Boolean): DirectWsStallResult {
        val now = clock()
        val slot = Slot(dc, isMedia)
        var activated = false
        val state = states.compute(slot) { _, previous ->
            if (previous != null && previous.blockedUntil > now) {
                previous
            } else {
                val strikes = if (
                    previous != null &&
                    previous.blockedUntil <= now &&
                    now - previous.lastStallAt <= STRIKE_WINDOW_MS
                ) {
                    previous.strikes + 1
                } else {
                    1
                }
                activated = true
                val blockedUntil = now + if (strikes >= STRIKES_TO_LONG_BLOCK) {
                    LONG_BLOCK_DURATION_MS
                } else {
                    RETRY_BLOCK_DURATION_MS
                }
                State(strikes, now, blockedUntil)
            }
        }!!
        return DirectWsStallResult(
            strikes = state.strikes,
            activated = activated,
            blockedForMs = (state.blockedUntil - now).coerceAtLeast(0L),
        )
    }

    fun recordResponsive(dc: Int, isMedia: Boolean) {
        val slot = Slot(dc, isMedia)
        states.computeIfPresent(slot) { _, state ->
            if (state.blockedUntil > clock()) state else null
        }
    }

    fun reset() {
        states.clear()
    }

    companion object {
        internal const val STALL_TIMEOUT_MS = 15_000L
        internal const val MIN_PENDING_BYTES = 1_024L
        internal const val MIN_MEANINGFUL_DOWN_BYTES = 4_096L
        internal const val RETRY_BLOCK_DURATION_MS = 60_000L
        internal const val STRIKE_WINDOW_MS = 5 * 60_000L
        private const val STRIKES_TO_LONG_BLOCK = 2
        private const val LONG_BLOCK_DURATION_MS = 10 * 60_000L

        internal fun isStalled(
            pendingSince: Long,
            pendingBytesUp: Long,
            pendingBytesDown: Long,
            now: Long,
        ): Boolean = pendingSince > 0L &&
                pendingBytesUp >= MIN_PENDING_BYTES &&
                pendingBytesDown < MIN_MEANINGFUL_DOWN_BYTES &&
                now - pendingSince >= STALL_TIMEOUT_MS
    }
}
