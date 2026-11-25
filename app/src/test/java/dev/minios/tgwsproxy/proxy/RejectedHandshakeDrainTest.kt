package dev.minios.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Test

class RejectedHandshakeDrainTest {
    @Test
    fun continuousInputStopsAt64KiB() {
        val input = ByteArrayInputStream(ByteArray(128 * 1024))
        drainRejectedHandshake(input, {}, clockMs = { 0L })
        assertEquals(64 * 1024, input.available())
    }

    @Test
    fun staggeredReadsCannotExtendTheTotalDeadline() {
        var now = 0L
        var reads = 0
        val timeouts = mutableListOf<Int>()
        val input = object : InputStream() {
            override fun read(): Int = error("Expected a buffered read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                now += minOf(3_000, timeouts.last())
                if (timeouts.last() < 3_000) throw SocketTimeoutException()
                return 1
            }
        }
        drainRejectedHandshake(input, { timeouts += it }, clockMs = { now })
        assertEquals(4, reads)
        assertEquals(listOf(10_000, 7_000, 4_000, 1_000), timeouts)
        assertEquals(10_000L, now)
    }

    @Test
    fun aSuccessfulReadAtTheDeadlineDoesNotStartAnotherRead() {
        var now = 0L
        var reads = 0
        val input = object : InputStream() {
            override fun read(): Int = error("Expected a buffered read")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                reads++
                now = 10_000L
                return 1
            }
        }
        drainRejectedHandshake(input, {}, clockMs = { now })
        assertEquals(1, reads)
    }
}
