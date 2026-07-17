package dev.minios.tgwsproxy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import dev.minios.tgwsproxy.proxy.ProxyConfig

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tg_ws_proxy_config")

/**
 * Persistent configuration storage using DataStore.
 */
class ConfigRepository(private val context: Context) {

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val SECRET = stringPreferencesKey("secret")
        val DC_REDIRECTS = stringPreferencesKey("dc_redirects")
        val BUFFER_SIZE = intPreferencesKey("buffer_size")
        val POOL_SIZE = intPreferencesKey("pool_size")
        val CF_PROXY_ENABLED = booleanPreferencesKey("cf_proxy_enabled")
        val CF_PROXY_PRIORITY = booleanPreferencesKey("cf_proxy_priority")
        val CF_PROXY_FIRST = booleanPreferencesKey("cf_proxy_first")
        val AUTO_OPTIMIZE_CONNECTION = booleanPreferencesKey("auto_optimize_connection")
        val KEEP_CPU_AWAKE = booleanPreferencesKey("keep_cpu_awake")
        val PRECONNECT_WEBSOCKETS = booleanPreferencesKey("preconnect_websockets")
        val ROUTE_PROBES_ENABLED = booleanPreferencesKey("route_probes_enabled")
        val CF_DOMAIN_REFRESH_ENABLED = booleanPreferencesKey("cf_domain_refresh_enabled")
        val WS_PING_INTERVAL_SECONDS = intPreferencesKey("ws_ping_interval_seconds")
        val SHOW_TRAFFIC_IN_NOTIFICATION = booleanPreferencesKey("show_traffic_in_notification")
        val CF_PROXY_USER_DOMAIN = stringPreferencesKey("cf_proxy_user_domain")
        val SHOW_DETAILED_STATS = booleanPreferencesKey("show_detailed_stats")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
    }

    /**
     * Stable initial secret: generated once and persisted immediately.
     * This ensures MainScreen always sees a consistent secret.
     */
    private val stableSecret: String by lazy {
        val existing = runBlocking {
            context.dataStore.data.first()[Keys.SECRET]
        }
        if (existing?.matches(Regex("^[0-9a-fA-F]{32}$")) == true) {
            existing
        } else {
            val generated = ProxyConfig.generateSecret()
            runBlocking {
                context.dataStore.edit { prefs ->
                    val current = prefs[Keys.SECRET]
                    if (current?.matches(Regex("^[0-9a-fA-F]{32}$")) != true) {
                        prefs[Keys.SECRET] = generated
                    }
                }
            }
            generated
        }
    }

    /**
     * Flow of the current config.
     */
    val configFlow: Flow<ProxyConfig> = context.dataStore.data.map { prefs ->
        ProxyConfig(
            host = prefs[Keys.HOST] ?: "127.0.0.1",
            port = (prefs[Keys.PORT] ?: 1443).coerceIn(1, 65535),
            secret = prefs[Keys.SECRET]
                ?.takeIf { it.matches(Regex("^[0-9a-fA-F]{32}$")) }
                ?: stableSecret,
            dcRedirects = prefs[Keys.DC_REDIRECTS]?.let { deserializeDcRedirects(it) }
                ?: ProxyConfig().dcRedirects,
            bufferSize = (prefs[Keys.BUFFER_SIZE] ?: 256).coerceIn(4, 4096) * 1024,
            poolSize = (prefs[Keys.POOL_SIZE] ?: 4).coerceIn(0, 16),
            cfProxyEnabled = prefs[Keys.CF_PROXY_ENABLED] ?: true,
            cfProxyPriority = prefs[Keys.CF_PROXY_PRIORITY] ?: true,
            cfProxyFirst = prefs[Keys.CF_PROXY_FIRST] ?: false,
            autoOptimizeConnection = prefs[Keys.AUTO_OPTIMIZE_CONNECTION] ?: true,
            keepCpuAwake = prefs[Keys.KEEP_CPU_AWAKE] ?: true,
            preconnectWebSockets = prefs[Keys.PRECONNECT_WEBSOCKETS] ?: true,
            routeProbesEnabled = prefs[Keys.ROUTE_PROBES_ENABLED] ?: true,
            cfDomainRefreshEnabled = prefs[Keys.CF_DOMAIN_REFRESH_ENABLED] ?: true,
            webSocketPingIntervalSeconds = (prefs[Keys.WS_PING_INTERVAL_SECONDS] ?: 30).coerceIn(30, 300),
            showTrafficInNotification = prefs[Keys.SHOW_TRAFFIC_IN_NOTIFICATION] ?: true,
            cfProxyUserDomain = prefs[Keys.CF_PROXY_USER_DOMAIN]
                ?.trim()
                ?.lowercase()
                ?.takeIf { dev.minios.tgwsproxy.proxy.CfProxyDomains.isValidDomain(it) }
                ?: "",
            showDetailedStats = prefs[Keys.SHOW_DETAILED_STATS] ?: false,
            appTheme = prefs[Keys.APP_THEME]?.takeIf { it in setOf("system", "light", "dark") } ?: "system",
            dynamicColor = prefs[Keys.DYNAMIC_COLOR] ?: true,
        )
    }

    /**
     * Get the current config snapshot.
     */
    suspend fun getConfig(): ProxyConfig = configFlow.first()

    /**
     * Save config to DataStore.
     */
    suspend fun saveConfig(config: ProxyConfig) {
        context.dataStore.edit { prefs ->
            prefs[Keys.HOST] = config.host
            prefs[Keys.PORT] = config.port
            prefs[Keys.SECRET] = config.secret
            prefs[Keys.DC_REDIRECTS] = serializeDcRedirects(config.dcRedirects)
            prefs[Keys.BUFFER_SIZE] = config.bufferSize / 1024
            prefs[Keys.POOL_SIZE] = config.poolSize
            prefs[Keys.CF_PROXY_ENABLED] = config.cfProxyEnabled
            prefs[Keys.CF_PROXY_PRIORITY] = config.cfProxyPriority
            prefs[Keys.CF_PROXY_FIRST] = config.cfProxyFirst
            prefs[Keys.AUTO_OPTIMIZE_CONNECTION] = config.autoOptimizeConnection
            prefs[Keys.KEEP_CPU_AWAKE] = config.keepCpuAwake
            prefs[Keys.PRECONNECT_WEBSOCKETS] = config.preconnectWebSockets
            prefs[Keys.ROUTE_PROBES_ENABLED] = config.routeProbesEnabled
            prefs[Keys.CF_DOMAIN_REFRESH_ENABLED] = config.cfDomainRefreshEnabled
            prefs[Keys.WS_PING_INTERVAL_SECONDS] = config.webSocketPingIntervalSeconds.coerceIn(30, 300)
            prefs[Keys.SHOW_TRAFFIC_IN_NOTIFICATION] = config.showTrafficInNotification
            prefs[Keys.CF_PROXY_USER_DOMAIN] = config.cfProxyUserDomain
            prefs[Keys.SHOW_DETAILED_STATS] = config.showDetailedStats
            prefs[Keys.APP_THEME] = config.appTheme
            prefs[Keys.DYNAMIC_COLOR] = config.dynamicColor
        }
    }

    suspend fun saveAppearance(appTheme: String, dynamicColor: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.APP_THEME] = appTheme
            prefs[Keys.DYNAMIC_COLOR] = dynamicColor
        }
    }

    private fun serializeDcRedirects(redirects: Map<Int, String>): String {
        return redirects.entries.joinToString(";") { "${it.key}:${it.value}" }
    }

    private fun deserializeDcRedirects(text: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for (entry in text.split(";")) {
            val parts = entry.split(":", limit = 2)
            if (parts.size == 2) {
                val dc = parts[0].toIntOrNull() ?: continue
                val address = parts[1].trim()
                if (dc in setOf(1, 2, 3, 4, 5, 203) && ProxyConfig.isValidAddress(address)) {
                    result[dc] = address
                }
            }
        }
        return result
    }
}
