package dev.minios.tgwsproxy.proxy

enum class RuntimeRouteMode {
    WS_CF_TCP,
    WS_TCP_CF,
    CF_WS_TCP,
    WS_TCP,
}

fun ProxyConfig.runtimeRouteMode(): RuntimeRouteMode = when {
    cfProxyEnabled && cfProxyFirst -> RuntimeRouteMode.CF_WS_TCP
    cfProxyEnabled && cfProxyPriority -> RuntimeRouteMode.WS_CF_TCP
    cfProxyEnabled -> RuntimeRouteMode.WS_TCP_CF
    else -> RuntimeRouteMode.WS_TCP
}
