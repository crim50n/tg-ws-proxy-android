package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call

class CfDnsResolverTest {
    private fun answer(request: okhttp3.Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("""{"Answer":[{"type":1,"TTL":120,"data":"104.21.1.2"}]}""".toResponseBody())
        .build()

    @Test
    fun unverifiedNetworkCanResolveButCannotPublishOutageCooldown() {
        val tracker = ConnectivityTracker()
        tracker.updateNetwork(1L, NetworkStatus.UNVERIFIED)
        var fail = true
        val resolver = CfDnsResolver(OkHttpClient.Builder().addInterceptor {
            if (fail) throw IOException("unreachable")
            answer(it.request())
        }.build())

        assertNull(resolver.resolve("example.com", tracker))
        assertFalse(resolver.isUnavailable())
        fail = false
        assertEquals("104.21.1.2", resolver.resolve("example.com", tracker))
        assertEquals("104.21.1.2", resolver.cached("example.com"))
        assertFalse(tracker.state.value.internetConfirmed)
    }

    @Test
    fun confirmedOutageBlocksUntilReset() {
        val tracker = ConnectivityTracker()
        tracker.updateNetwork(1L, NetworkStatus.ONLINE)
        val resolver = CfDnsResolver(OkHttpClient.Builder().addInterceptor {
            throw IOException("unreachable")
        }.build())
        assertNull(resolver.resolve("example.com", tracker))
        assertTrue(resolver.isUnavailable())
        resolver.reset()
        assertFalse(resolver.isUnavailable())
    }

    @Test
    fun completedCallIsReleasedAndOtherRuntimeResetDoesNotClearCache() {
        val tracker = ConnectivityTracker()
        val call = AtomicReference<Call>()
        val resolver = CfDnsResolver(OkHttpClient.Builder().addInterceptor {
            call.set(it.call())
            answer(it.request())
        }.build())
        assertEquals("104.21.1.2", resolver.resolve("example.com", tracker))
        tracker.updateNetwork(null, NetworkStatus.OFFLINE)
        assertFalse(call.get().isCanceled())
        CfDnsResolver().reset()
        assertEquals("104.21.1.2", resolver.cached("example.com"))
    }

    @Test
    fun networkLossCancelsDohWithoutWaitingForResolution() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val tracker = ConnectivityTracker()
        tracker.updateNetwork(1L, NetworkStatus.UNVERIFIED)
        val resolver = CfDnsResolver(OkHttpClient.Builder().addInterceptor {
            call.set(it.call())
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            answer(it.request())
        }.build())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val result = executor.submit<String?> { resolver.resolve("example.com", tracker) }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            executor.submit { tracker.updateNetwork(null, NetworkStatus.OFFLINE) }.get(1, TimeUnit.SECONDS)
            assertTrue(call.get().isCanceled())
            release.countDown()
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertNull(resolver.cached("example.com"))
            assertFalse(resolver.isUnavailable())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun resetDoesNotWaitForOldDohOrAllowItToPublish() {
        for (oldFails in listOf(false, true)) {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val resolver = CfDnsResolver(OkHttpClient.Builder().addInterceptor {
                if (it.request().url.queryParameter("name") == "old.example") {
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    if (oldFails) throw IOException("old network failed")
                }
                answer(it.request())
            }.build())
            val executor = Executors.newFixedThreadPool(2)
            try {
                val old = executor.submit<String?> { resolver.resolve("old.example") }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                executor.submit { resolver.reset() }.get(1, TimeUnit.SECONDS)
                val fresh = executor.submit<String?> { resolver.resolve("new.example") }
                assertEquals("104.21.1.2", fresh.get(1, TimeUnit.SECONDS))
                release.countDown()
                assertNull(old.get(5, TimeUnit.SECONDS))
                assertNull(resolver.cached("old.example"))
                assertFalse(resolver.isUnavailable())
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
    }

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
