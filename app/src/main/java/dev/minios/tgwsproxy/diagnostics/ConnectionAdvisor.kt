package dev.minios.tgwsproxy.diagnostics

import dev.minios.tgwsproxy.proxy.ProxyConfig
import dev.minios.tgwsproxy.proxy.MtProtoConstants

enum class ConnectionAdvice {
    ENABLE_CLOUDFLARE,
    USE_CLOUDFLARE_FIRST,
    USE_DIRECT_FIRST,
}

fun analyzeConnectionMode(
    entries: List<DiagnosticEntry>,
    configuredCfEnabled: Boolean,
    configuredCfFirst: Boolean,
): ConnectionAdvice? {
    val runtimeStart = entries.indexOfLast { it.event == "runtime_starting" }
    val observationsReset = entries.indexOfLast {
        it.event == "network_changed" || it.event == "connection_advice_applied"
    }
    val analysisStart = maxOf(runtimeStart, observationsReset)
    val runtimeDetails = entries.getOrNull(runtimeStart)?.details.orEmpty()
    val runtimeId = runtimeDetails.longField("runtimeId")
    val runtimeEntries = (if (analysisStart >= 0) entries.drop(analysisStart) else entries).filter { entry ->
        !entry.hasField("source", "probe") ||
                runtimeId == null ||
                entry.details.longField("runtimeId") == runtimeId
    }
    val cfEnabled = runtimeDetails.booleanField("cfEnabled") ?: configuredCfEnabled
    val cfFirst = runtimeDetails.booleanField("cfFirst") ?: configuredCfFirst

    val webSocketFailures = runtimeEntries.filter {
        it.event == "upstream_attempt_failed" && it.hasRoute("ws_direct")
    }
    val tcpFailures = runtimeEntries.filter {
        it.event == "upstream_attempt_failed" && it.hasRoute("tcp")
    }
    val directFailures = webSocketFailures + tcpFailures
    val cloudflareFailures = runtimeEntries.filter {
        it.event == "upstream_attempt_failed" && it.hasRoute("cloudflare")
    }
    val webSocketSuccesses = runtimeEntries.count {
        it.event == "upstream_route_ready" && it.hasRoute("ws_direct")
    }
    val cloudflareSuccesses = runtimeEntries.count {
        it.event == "upstream_route_ready" && it.hasRoute("cloudflare")
    }

    if (!cfEnabled) {
        return if (
            directFailures.size >= 6 &&
            directFailures.distinctDcCount() >= 2 &&
            webSocketSuccesses == 0 &&
            cloudflareSuccesses >= 2
        ) {
            ConnectionAdvice.ENABLE_CLOUDFLARE.takeUnless {
                configuredCfEnabled && configuredCfFirst
            }
        } else {
            null
        }
    }

    if (!cfFirst) {
        return if (
            webSocketFailures.size >= 3 &&
            webSocketFailures.distinctDcCount() >= 2 &&
            webSocketSuccesses == 0 &&
            cloudflareSuccesses >= 3 &&
            cloudflareFailures.size * 4 <= cloudflareSuccesses
        ) {
            ConnectionAdvice.USE_CLOUDFLARE_FIRST.takeUnless {
                configuredCfEnabled && configuredCfFirst
            }
        } else {
            null
        }
    }

    return if (
        cloudflareFailures.size >= 3 &&
        cloudflareFailures.distinctDcCount() >= 2 &&
        cloudflareSuccesses == 0 &&
        webSocketSuccesses >= 3 &&
        webSocketFailures.size * 4 <= webSocketSuccesses
    ) {
        ConnectionAdvice.USE_DIRECT_FIRST.takeUnless {
            !configuredCfEnabled || !configuredCfFirst
        }
    } else {
        null
    }
}

fun ProxyConfig.withConnectionAdvice(advice: ConnectionAdvice): ProxyConfig = when (advice) {
    ConnectionAdvice.ENABLE_CLOUDFLARE,
    ConnectionAdvice.USE_CLOUDFLARE_FIRST -> copy(
        cfProxyEnabled = true,
        cfProxyPriority = true,
        cfProxyFirst = true,
    )
    ConnectionAdvice.USE_DIRECT_FIRST -> copy(
        cfProxyEnabled = true,
        cfProxyPriority = true,
        cfProxyFirst = false,
    )
}

fun ProxyConfig.withAutomaticRouteDefaults(): ProxyConfig = copy(
    dcRedirects = MtProtoConstants.DEFAULT_DC_REDIRECTS,
    cfProxyEnabled = true,
    cfProxyPriority = true,
    cfProxyFirst = false,
    cfProxyUserDomain = "",
).withAutomaticPerformanceDefaults()

fun ProxyConfig.withAutomaticPerformanceDefaults(): ProxyConfig = copy(
    bufferSize = 256 * 1024,
    poolSize = if (cfProxyFirst) 0 else 4,
)

fun requiresProxyRestart(previous: ProxyConfig, updated: ProxyConfig): Boolean {
    fun ProxyConfig.runtimeSettings(): ProxyConfig {
        val routing = if (autoOptimizeConnection) withAutomaticRouteDefaults() else this
        return routing.copy(
            showDetailedStats = false,
            appTheme = "system",
            dynamicColor = true,
        )
    }
    return previous.runtimeSettings() != updated.runtimeSettings()
}

private fun DiagnosticEntry.hasRoute(route: String): Boolean =
    details.split(' ').any { it == "route=$route" }

private fun String.booleanField(name: String): Boolean? =
    split(' ').firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.toBooleanStrictOrNull()

private fun String.longField(name: String): Long? =
    split(' ').firstOrNull { it.startsWith("$name=") }
        ?.substringAfter('=')
        ?.toLongOrNull()

private fun DiagnosticEntry.hasField(name: String, value: String): Boolean =
    details.split(' ').any { it == "$name=$value" }

private fun List<DiagnosticEntry>.distinctDcCount(): Int =
    mapNotNull { entry ->
        entry.details.split(' ')
            .firstOrNull { it.startsWith("dc=") }
            ?.substringAfter('=')
            ?.toIntOrNull()
    }.distinct().size
