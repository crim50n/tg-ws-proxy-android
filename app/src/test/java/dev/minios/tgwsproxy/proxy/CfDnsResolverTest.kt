package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfDnsResolverTest {
    @Test
    fun parsesFirstValidIpv4Answer() {
        val response = """
            {
              "Status": 0,
              "Answer": [
                {"name":"example.com","type":5,"TTL":120,"data":"alias.example.com"},
                {"name":"alias.example.com","type":1,"TTL":240,"data":"104.21.1.2"}
              ]
            }
        """.trimIndent()

        assertEquals(DohAddress("104.21.1.2", 240L), parseDohIpv4(response))
    }

    @Test
    fun rejectsMalformedOrNonAddressAnswers() {
        assertNull(parseDohIpv4("not json"))
        assertNull(parseDohIpv4("""{"Answer":[{"type":28,"data":"2001:db8::1"}]}"""))
        assertNull(parseDohIpv4("""{"Answer":[{"type":1,"data":"999.1.2.3"}]}"""))
    }

    @Test
    fun blocksAllResolutionAttemptsDuringSharedOutageCooldown() {
        var now = 1_000L
        val cooldown = DnsOutageCooldown(
            durationMs = 30_000L,
            probeIntervalMs = 5_000L,
            clock = { now },
        )

        assertFalse(cooldown.isBlocked())
        cooldown.block()
        assertTrue(cooldown.isBlocked())
        assertTrue(cooldown.shouldSkipConnectionAttempt())

        now += 5_000L
        assertFalse(cooldown.shouldSkipConnectionAttempt())
        assertTrue(cooldown.shouldSkipConnectionAttempt())

        now += 24_999L
        assertTrue(cooldown.isBlocked())
        now += 1L
        assertFalse(cooldown.isBlocked())

        cooldown.block()
        cooldown.reset()
        assertFalse(cooldown.isBlocked())
    }
}
