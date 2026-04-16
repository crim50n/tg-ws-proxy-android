package dev.minios.tgwsproxy.proxy

import kotlinx.serialization.Serializable
import java.net.DatagramSocket
import java.net.InetSocketAddress as InetSockAddr
import java.security.SecureRandom

/**
 * Proxy configuration. Matches the Python ProxyConfig.
 */
@Serializable
data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = generateSecret(),
    val dcRedirects: Map<Int, String> = MtProtoConstants.DEFAULT_DC_REDIRECTS,
    val bufferSize: Int = 256 * 1024,
    val poolSize: Int = 4,
    val cfProxyEnabled: Boolean = true,
    val cfProxyPriority: Boolean = true,
    val cfProxyUserDomain: String = "",
) {
    /**
     * Get the secret as raw bytes (16 bytes from 32 hex chars).
     */
    fun secretBytes(): ByteArray {
        return secret.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /**
     * Get the active Cloudflare proxy domain (sticky, like Python).
     */
    fun getActiveCfDomain(): String? {
        if (!cfProxyEnabled) return null
        if (cfProxyUserDomain.isNotBlank()) return cfProxyUserDomain
        return CfProxyDomains.activeDomain
    }

    /**
     * Get all available CF proxy domains for fallback rotation.
     */
    fun getAllCfDomains(): List<String> {
        if (!cfProxyEnabled) return emptyList()
        if (cfProxyUserDomain.isNotBlank()) return listOf(cfProxyUserDomain)
        return CfProxyDomains.getDomains()
    }

    /**
     * Generate a tg:// proxy link.
     * #33: Resolve 0.0.0.0 to actual LAN IP (matching Python get_link_host).
     */
    fun proxyLink(): String {
        val linkHost = getLinkHost(host)
        return "tg://proxy?server=$linkHost&port=$port&secret=dd$secret"
    }

    /**
     * Format DC redirects as text for settings display.
     */
    fun dcRedirectsText(): String {
        return dcRedirects.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}:${it.value}" }
    }

    companion object {
        fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * #33: Resolve host for proxy link — if 0.0.0.0, detect LAN IP.
         * Matches Python get_link_host() in utils.py.
         */
        fun getLinkHost(host: String): String {
            if (host == "0.0.0.0") {
                return try {
                    DatagramSocket().use { s ->
                        s.connect(InetSockAddr("8.8.8.8", 80))
                        s.localAddress.hostAddress ?: "127.0.0.1"
                    }
                } catch (_: Exception) {
                    "127.0.0.1"
                }
            }
            return host
        }

        /**
         * Parse DC redirects from text format (one "DC:IP" per line).
         */
        fun parseDcRedirects(text: String): Map<Int, String> {
            val result = mutableMapOf<Int, String>()
            for (line in text.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val parts = trimmed.split(":", limit = 2)
                if (parts.size == 2) {
                    val dc = parts[0].trim().toIntOrNull() ?: continue
                    val ip = parts[1].trim()
                    if (ip.isNotEmpty()) {
                        result[dc] = ip
                    }
                }
            }
            return result
        }
    }
}
