package dev.minios.tgwsproxy.proxy

import java.io.IOException
import java.io.InputStream

/** Optional, strict PROXY v1 preamble. Addresses are not trusted for authorization. */
internal object ProxyProtocol {
    fun read(input: InputStream) {
        val line = StringBuilder()
        while (line.length < 108) {
            val byte = input.read()
            if (byte < 0) throw IOException("Incomplete PROXY preamble")
            if (byte !in 0x20..0x7e && byte != 13 && byte != 10) throw IOException("Invalid PROXY preamble")
            line.append(byte.toChar())
            if (line.endsWith("\r\n")) {
                val parts = line.toString().dropLast(2).split(' ')
                if (parts.size >= 2 && parts[0] == "PROXY" && parts[1] == "UNKNOWN") return
                if (parts.size != 6 || parts[0] != "PROXY" || parts[1] !in setOf("TCP4", "TCP6")) {
                    throw IOException("Invalid PROXY preamble")
                }
                val ipv6 = parts[1] == "TCP6"
                for (address in parts.subList(2, 4)) {
                    val valid = if (ipv6) ':' in address && ProxyConfig.isValidAddress(address) else
                        address.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 } }
                    if (!valid) throw IOException("Invalid PROXY address")
                }
                if (parts.subList(4, 6).any { (it.toIntOrNull() ?: -1) !in 0..65535 }) {
                    throw IOException("Invalid PROXY port")
                }
                return
            }
        }
        throw IOException("PROXY preamble too long")
    }
}
