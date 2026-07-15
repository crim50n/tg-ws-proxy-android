package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

object RouteHealthProbe {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val ROUNDS = 2

    suspend fun run(config: ProxyConfig, runtimeId: Long) {
        val directTargets = config.dcRedirects
            .filterKeys { it in setOf(2, 4) }
            .toList()
        if (directTargets.isEmpty()) return

        DiagnosticLogger.event(
            "route_probe_started",
            "dcCount" to directTargets.size,
            "rounds" to ROUNDS,
            "runtimeId" to runtimeId,
        )
        repeat(ROUNDS) { round ->
            coroutineScope {
                directTargets.flatMap { (dc, targetIp) ->
                    listOf(
                        async { probeDirectWebSocket(dc, targetIp, config.bufferSize, runtimeId) },
                        async { probeCloudflare(dc, config, runtimeId) },
                        async { probeTcp(dc, config.bufferSize, runtimeId) },
                    )
                }.awaitAll()
            }
            if (round + 1 < ROUNDS) delay(1_000)
        }
        DiagnosticLogger.event("route_probe_completed", "runtimeId" to runtimeId)
    }

    private suspend fun probeDirectWebSocket(dc: Int, targetIp: String, bufferSize: Int, runtimeId: Long) =
        probe(dc, "ws_direct", runtimeId) {
            RawWebSocket.connectToDc(
                dc = dc,
                isMedia = false,
                targetIp = targetIp,
                bufferSize = bufferSize,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
            ).close()
        }

    private suspend fun probeCloudflare(dc: Int, config: ProxyConfig, runtimeId: Long) =
        probe(dc, "cloudflare", runtimeId) {
        val domains = if (config.cfProxyUserDomain.isNotBlank()) {
            listOf(config.cfProxyUserDomain)
        } else {
            CfProxyDomains.getDomainsForDc(dc).take(2)
        }
        var lastError: Exception? = null
        for (domain in domains) {
            try {
                RawWebSocket.connectToDc(
                    dc = dc,
                    isMedia = false,
                    targetIp = domain,
                    bufferSize = config.bufferSize,
                    cfProxyDomain = domain,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                ).close()
                return@probe
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No Cloudflare domains available")
    }

    private suspend fun probeTcp(dc: Int, bufferSize: Int, runtimeId: Long) = probe(dc, "tcp", runtimeId) {
        val targetIp = MtProtoConstants.DC_IPS[dc]
            ?: throw IllegalStateException("No TCP target for DC $dc")
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.sendBufferSize = bufferSize
            socket.receiveBufferSize = bufferSize
            socket.connect(InetSocketAddress(targetIp, 443), CONNECT_TIMEOUT_MS)
        }
    }

    private suspend fun probe(dc: Int, route: String, runtimeId: Long, connect: () -> Unit) =
        withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        DiagnosticLogger.event(
            "upstream_attempt",
            "dc" to dc,
            "route" to route,
            "source" to "probe",
            "runtimeId" to runtimeId,
        )
        try {
            connect()
            DiagnosticLogger.event(
                "upstream_route_ready",
                "dc" to dc,
                "route" to route,
                "source" to "probe",
                "runtimeId" to runtimeId,
                "elapsedMs" to System.currentTimeMillis() - startedAt,
            )
        } catch (e: Exception) {
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to route,
                "source" to "probe",
                "runtimeId" to runtimeId,
                "stage" to "connect",
            )
        }
    }
}
