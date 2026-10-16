package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Dns
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class DohAddress(val address: String, val ttlSeconds: Long)

internal class DnsOutageCooldown(
    private val durationMs: Long,
    private val probeIntervalMs: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val unavailableUntil = AtomicLong(0L)
    private val nextProbeAt = AtomicLong(0L)

    fun isBlocked(): Boolean = unavailableUntil.get() > clock()

    fun block() {
        val now = clock()
        unavailableUntil.set(now + durationMs)
        nextProbeAt.set(now + probeIntervalMs)
    }

    fun shouldSkipConnectionAttempt(): Boolean {
        val now = clock()
        if (unavailableUntil.get() <= now) return false
        while (true) {
            val nextProbe = nextProbeAt.get()
            if (nextProbe > now) return true
            if (nextProbeAt.compareAndSet(nextProbe, now + probeIntervalMs)) return false
        }
    }

    fun reset() {
        unavailableUntil.set(0L)
        nextProbeAt.set(0L)
    }
}

internal fun parseDohIpv4(body: String): DohAddress? {
    val answers = runCatching {
        Json.parseToJsonElement(body).jsonObject["Answer"]?.jsonArray
    }.getOrNull() ?: return null
    for (answer in answers) {
        val fields = answer.jsonObject
        if (fields["type"]?.jsonPrimitive?.longOrNull != 1L) continue
        val address = fields["data"]?.jsonPrimitive?.content ?: continue
        if (!isIpv4Address(address)) continue
        return DohAddress(
            address = address,
            ttlSeconds = fields["TTL"]?.jsonPrimitive?.longOrNull ?: 300L,
        )
    }
    return null
}

private fun isIpv4Address(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit) &&
                (part.toIntOrNull() ?: -1) in 0..255
    }
}

internal class CfDnsResolver(callFactory: Call.Factory? = null) {
    private data class CachedAddress(val address: String, val expiresAt: Long)
    private data class Endpoint(val url: String, val host: String)

    private val endpoints = listOf(
        Endpoint("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com"),
        Endpoint("https://dns.google/resolve", "dns.google"),
    )
    private val bootstrapAddresses = mapOf(
        "cloudflare-dns.com" to listOf(byteArrayOf(1, 1, 1, 1), byteArrayOf(1, 0, 0, 1)),
        "dns.google" to listOf(byteArrayOf(8, 8, 8, 8), byteArrayOf(8, 8, 4, 4)),
    )
    private val cache = ConcurrentHashMap<String, CachedAddress>()
    private val stateLock = Any()
    private var resolveLock = Any()
    private var generation = 0L
    private val outageCooldown = DnsOutageCooldown(
        durationMs = RESOLVER_COOLDOWN_MS,
        probeIntervalMs = SYSTEM_DNS_PROBE_INTERVAL_MS,
    )
    private val bootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            return bootstrapAddresses[hostname]?.map { InetAddress.getByAddress(hostname, it) }
                ?: Dns.SYSTEM.lookup(hostname)
        }
    }
    private val client = callFactory ?: OkHttpClient.Builder()
        .dns(bootstrapDns)
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    fun cached(hostname: String): String? {
        val cached = cache[hostname] ?: return null
        if (cached.expiresAt > System.currentTimeMillis()) return cached.address
        cache.remove(hostname, cached)
        return null
    }

    fun reset() = synchronized(stateLock) {
        generation++
        // New epochs must not wait for an old, potentially blocked DoH request.
        resolveLock = Any()
        cache.clear()
        outageCooldown.reset()
    }

    fun isUnavailable(): Boolean = outageCooldown.isBlocked()

    fun shouldSkipConnectionAttempt(): Boolean = outageCooldown.shouldSkipConnectionAttempt()

    fun markAvailable() = outageCooldown.reset()

    fun resolve(
        hostname: String,
        connectivity: ConnectivityTracker? = null,
        epoch: Long = connectivity?.state?.value?.epoch ?: 0L,
    ): String? {
        val (startedGeneration, lock) = synchronized(stateLock) { generation to resolveLock }
        fun isCurrent(): Boolean = (connectivity?.isCurrent(epoch) != false) &&
            synchronized(stateLock) { generation == startedGeneration }
        fun publish(requireInternet: Boolean = false, action: () -> Unit): Boolean {
            var published = false
            val guarded: () -> Unit = {
                synchronized(stateLock) {
                    if (generation == startedGeneration) {
                        action()
                        published = true
                    }
                }
            }
            if (connectivity == null && !requireInternet) guarded()
            else if (connectivity == null) return false
            else connectivity.recordIfCurrent(epoch, requireInternet, guarded)
            return published
        }
        if (!isCurrent()) return null
        return synchronized(lock) {
            if (!isCurrent()) return@synchronized null
            cached(hostname)?.let { return@synchronized it }
            if ((connectivity == null || connectivity.state.value.internetConfirmed) &&
                outageCooldown.isBlocked()) return@synchronized null
            val encodedHost = URLEncoder.encode(hostname, Charsets.UTF_8.name())
            var resolverReachable = false
            for (endpoint in endpoints) {
                if (!isCurrent()) return@synchronized null
                val request = Request.Builder()
                    .url("${endpoint.url}?name=$encodedHost&type=A")
                    .header("Accept", "application/dns-json")
                    .build()
                val call = client.newCall(request)
                val cancellation = AutoCloseable { call.cancel() }
                if (connectivity?.trackResource(epoch, cancellation) == false) return@synchronized null
                val answer = try {
                    runCatching {
                        call.execute().use { response ->
                            resolverReachable = true
                            if (!response.isSuccessful) null
                            else response.body?.string()?.let(::parseDohIpv4)
                        }
                    }.getOrNull()
                } finally {
                    connectivity?.releaseResource(cancellation)
                } ?: continue
                val ttlMs = answer.ttlSeconds.coerceIn(60L, 3_600L) * 1_000L
                if (!publish {
                    cache[hostname] = CachedAddress(answer.address, System.currentTimeMillis() + ttlMs)
                    outageCooldown.reset()
                }) return@synchronized null
                DiagnosticLogger.event(
                    "cf_dns_fallback_ready",
                    "host" to hostname,
                    "resolver" to endpoint.host,
                )
                return@synchronized answer.address
            }
            if (!isCurrent()) return@synchronized null
            if (!resolverReachable) {
                if (publish(requireInternet = true) { outageCooldown.block() }) DiagnosticLogger.event(
                    "cf_dns_fallback_failed",
                    "host" to hostname,
                    "reason" to "resolver_unavailable",
                    "cooldownMs" to RESOLVER_COOLDOWN_MS,
                )
            } else {
                DiagnosticLogger.event(
                    "cf_dns_fallback_failed",
                    "host" to hostname,
                    "reason" to "no_a_record",
                )
            }
            null
        }
    }

    private companion object {
        const val RESOLVER_COOLDOWN_MS = 30_000L
        const val SYSTEM_DNS_PROBE_INTERVAL_MS = 5_000L
    }
}
