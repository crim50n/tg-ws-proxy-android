package dev.minios.tgwsproxy.proxy

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloudflare proxy domain management.
 * Fetches and caches domains from GitHub.
 * Follows the validated domain-pool behavior from the Python implementation.
 *
 * Uses a daemon thread for periodic refresh.
 */
object CfProxyDomains {
    private const val TAG = "CfProxyDomains"
    private const val DOMAINS_URL =
        "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
    private const val REFRESH_INTERVAL_MS = 3600_000L // 1 hour
    private const val MIN_VALID_DOMAINS = 3

    // Same encoded domains as Python _CFPROXY_ENC
    private val DEFAULT_ENCODED = listOf(
        "virkgj.com",
        "vmmzovy.com",
        "mkuosckvso.com",
        "zaewayzmplad.com",
        "twdmbzcm.com",
        "awzwsldi.com",
        "clngqrflngqin.com",
        "tjacxbqtj.com",
        "bxaxtxmrw.com",
        "dmohrsgmohcrwb.com",
        "vwbmtmoi.com",
        "khgrre.com",
        "ulihssf.com",
        "tmhqsdqmfpmk.com",
        "xwuwoqbm.com",
        "orgcnunpj.com",
        "zhkuldz.com",
        "zypoljnslxa.com",
        "efabnxaowuzs.com",
        "zaftuzsftqdq.com",
    )

    // Same suffix as Python _S = chr(46)+chr(99)+chr(111)+chr(46)+chr(117)+chr(107)
    private val SUFFIX = charArrayOf('.', 'c', 'o', '.', 'u', 'k').concatToString()

    private val domains = AtomicReference<List<String>>(decodeDefaults())
    private val activeDomains = ConcurrentHashMap<Int, String>()

    // Background refresh thread lifecycle.
    @Volatile
    private var refreshThread: Thread? = null
    @Volatile
    private var stopFlag = false

    /**
     * Decode domain using the same algorithm as Python _dd().
     * Only decodes domains ending in ".com" — the ".com" suffix is replaced
     * with ".co.uk" and each letter is shifted back by N positions,
     * where N = count of alphabetic chars in the part before ".com".
     */
    private fun decodeDomain(s: String): String {
        if (!s.endsWith(".com")) return s
        val prefix = s.dropLast(4) // remove ".com"
        val n = prefix.count { it.isLetter() }
        val decoded = prefix.map { c ->
            if (c.isLetter()) {
                val base = if (c > '`') 'a' else 'A'
                val shifted = ((c.code - base.code - n) % 26 + 26) % 26
                (base.code + shifted).toChar()
            } else {
                c
            }
        }.joinToString("")
        return decoded + SUFFIX
    }

    private fun decodeDefaults(): List<String> {
        return DEFAULT_ENCODED.map { decodeDomain(it) }
    }

    fun getDomains(): List<String> = domains.get()

    fun getDomainsForDc(dc: Int): List<String> {
        val available = domains.get()
        if (available.isEmpty()) return emptyList()
        val active = activeDomains[dc]
            ?.takeIf { it in available }
            ?: available.random().also { activeDomains[dc] = it }
        return listOf(active) + available.filter { it != active }.shuffled()
    }

    fun setActiveDomain(dc: Int, domain: String) {
        if (domain in domains.get()) activeDomains[dc] = domain
    }

    /**
     * Start the hourly background refresh thread.
     * Refreshes immediately on start, then every hour.
     * Calling again stops the previous thread and starts a new one.
     */
    fun startBackgroundRefresh() {
        // Stop existing thread (matching Python: _refresh_stop.set())
        stopFlag = true
        refreshThread?.interrupt()

        stopFlag = false
        val thread = Thread({
            // Refresh immediately on startup (matching Python _loop)
            doRefresh()
            // Then loop every hour
            while (!stopFlag) {
                try {
                    Thread.sleep(REFRESH_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (stopFlag) break
                doRefresh()
            }
        }, "cfproxy-domains-refresh")
        thread.isDaemon = true
        thread.start()
        refreshThread = thread
    }

    /**
     * Stop the background refresh thread.
     */
    fun stopBackgroundRefresh() {
        stopFlag = true
        refreshThread?.interrupt()
        refreshThread = null
    }

    /**
     * On-demand refresh (kept for backward compatibility).
     */
    fun refreshIfNeeded() {
        doRefresh()
    }

    /**
     * Actual refresh logic (matching Python refresh_cfproxy_domains).
     */
    private fun doRefresh() {
        try {
            val cacheBuster = (1..7).map { ('a'..'z').random() }.joinToString("")
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val request = Request.Builder()
                .url("$DOMAINS_URL?$cacheBuster")
                .header("User-Agent", "tg-ws-proxy")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return
                    val encoded = body.lines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }

                    // Decode each domain the same way Python does
                    val decoded = normalizeDomains(encoded.map { decodeDomain(it) })

                    if (decoded.size >= MIN_VALID_DOMAINS) {
                        domains.set(decoded)
                        activeDomains.entries.removeIf { it.value !in decoded }
                        Log.i(TAG, "CF proxy domain pool updated from GitHub (${decoded.size} domains)")
                    } else {
                        Log.w(TAG, "Ignoring CF proxy domain update: ${decoded.size} valid domain(s)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh CF proxy domains: ${e.message}")
        }
    }

    internal fun normalizeDomains(items: List<String>): List<String> {
        return items.asSequence()
            .map { it.trim().lowercase() }
            .filter { isValidDomain(it) }
            .distinct()
            .toList()
    }

    internal fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253 || domain.startsWith('.') || domain.endsWith('.')) {
            return false
        }
        val labels = domain.split('.')
        if (labels.size < 2) return false
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 || label.startsWith('-') || label.endsWith('-') ||
                        label.any { it.code > 0x7F || (!it.isLetterOrDigit() && it != '-') }
            }) {
            return false
        }
        val tld = labels.last()
        return tld.length >= 2 && tld.any { it.isLetter() }
    }
}
