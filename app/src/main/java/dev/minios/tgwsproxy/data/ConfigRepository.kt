package dev.minios.tgwsproxy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.minios.tgwsproxy.proxy.ProxyConfig

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tg_ws_proxy_config")
private val SECRET_PATTERN = Regex("^[0-9a-fA-F]{32}$")
private const val POWER_PROFILE_VERSION = 1

internal data class PowerProfile(
    val keepCpuAwake: Boolean,
    val preconnectWebSockets: Boolean,
    val routeProbesEnabled: Boolean,
    val cfDomainRefreshEnabled: Boolean,
    val webSocketPingIntervalSeconds: Int,
)

internal val LEGACY_DEFAULT_POWER_PROFILE = PowerProfile(
    keepCpuAwake = true,
    preconnectWebSockets = true,
    routeProbesEnabled = true,
    cfDomainRefreshEnabled = true,
    webSocketPingIntervalSeconds = 30,
)

internal val BATTERY_SAVING_DEFAULT_POWER_PROFILE = PowerProfile(
    keepCpuAwake = false,
    preconnectWebSockets = false,
    routeProbesEnabled = false,
    cfDomainRefreshEnabled = false,
    webSocketPingIntervalSeconds = 180,
)

internal data class PowerProfileMigration(
    val profile: PowerProfile,
    val showNotice: Boolean,
)

internal fun resolvePowerProfileMigration(
    existingInstall: Boolean,
    current: PowerProfile,
): PowerProfileMigration {
    val migrateLegacyDefaults = existingInstall && current == LEGACY_DEFAULT_POWER_PROFILE
    return PowerProfileMigration(
        profile = if (!existingInstall || migrateLegacyDefaults) {
            BATTERY_SAVING_DEFAULT_POWER_PROFILE
        } else {
            current
        },
        showNotice = migrateLegacyDefaults,
    )
}

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
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val SHOW_TRAFFIC_IN_NOTIFICATION = booleanPreferencesKey("show_traffic_in_notification")
        val CF_PROXY_USER_DOMAIN = stringPreferencesKey("cf_proxy_user_domain")
        val SHOW_DETAILED_STATS = booleanPreferencesKey("show_detailed_stats")
        val APP_THEME = stringPreferencesKey("app_theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val POWER_PROFILE_VERSION = intPreferencesKey("power_profile_version")
        val POWER_PROFILE_NOTICE_PENDING = booleanPreferencesKey("power_profile_notice_pending")
    }

    private suspend fun preparePowerProfile() {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val appWasUpdated = packageInfo.lastUpdateTime > packageInfo.firstInstallTime
        context.dataStore.edit { prefs ->
            if ((prefs[Keys.POWER_PROFILE_VERSION] ?: 0) >= POWER_PROFILE_VERSION) return@edit

            val existingInstall = appWasUpdated && prefs.asMap().isNotEmpty()
            val current = PowerProfile(
                keepCpuAwake = prefs[Keys.KEEP_CPU_AWAKE] ?: LEGACY_DEFAULT_POWER_PROFILE.keepCpuAwake,
                preconnectWebSockets = prefs[Keys.PRECONNECT_WEBSOCKETS]
                    ?: LEGACY_DEFAULT_POWER_PROFILE.preconnectWebSockets,
                routeProbesEnabled = prefs[Keys.ROUTE_PROBES_ENABLED]
                    ?: LEGACY_DEFAULT_POWER_PROFILE.routeProbesEnabled,
                cfDomainRefreshEnabled = prefs[Keys.CF_DOMAIN_REFRESH_ENABLED]
                    ?: LEGACY_DEFAULT_POWER_PROFILE.cfDomainRefreshEnabled,
                webSocketPingIntervalSeconds = (
                    prefs[Keys.WS_PING_INTERVAL_SECONDS]
                        ?: LEGACY_DEFAULT_POWER_PROFILE.webSocketPingIntervalSeconds
                    ).coerceIn(30, 300),
            )
            val migration = resolvePowerProfileMigration(existingInstall, current)
            val resolved = migration.profile

            prefs[Keys.KEEP_CPU_AWAKE] = resolved.keepCpuAwake
            prefs[Keys.PRECONNECT_WEBSOCKETS] = resolved.preconnectWebSockets
            prefs[Keys.ROUTE_PROBES_ENABLED] = resolved.routeProbesEnabled
            prefs[Keys.CF_DOMAIN_REFRESH_ENABLED] = resolved.cfDomainRefreshEnabled
            prefs[Keys.WS_PING_INTERVAL_SECONDS] = resolved.webSocketPingIntervalSeconds
            prefs[Keys.POWER_PROFILE_NOTICE_PENDING] = migration.showNotice
            prefs[Keys.POWER_PROFILE_VERSION] = POWER_PROFILE_VERSION
        }
    }

    /**
     * Ensure the initial proxy secret is valid and persisted without blocking the caller thread.
     */
    private suspend fun ensureSecret(): String {
        val existing = context.dataStore.data.first()[Keys.SECRET]
        if (existing?.matches(SECRET_PATTERN) == true) return existing

        var resolved = ""
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.SECRET]
            resolved = if (current?.matches(SECRET_PATTERN) == true) {
                current
            } else {
                ProxyConfig.generateSecret().also { prefs[Keys.SECRET] = it }
            }
        }
        return resolved
    }

    /**
     * Flow of the current config.
     */
    val configFlow: Flow<ProxyConfig> = flow {
        preparePowerProfile()
        val stableSecret = ensureSecret()
        emitAll(context.dataStore.data.map { prefs ->
        ProxyConfig(
            host = prefs[Keys.HOST] ?: "127.0.0.1",
            port = (prefs[Keys.PORT] ?: 1443).coerceIn(1, 65535),
            secret = prefs[Keys.SECRET]
                ?.takeIf { it.matches(SECRET_PATTERN) }
                ?: stableSecret,
            dcRedirects = prefs[Keys.DC_REDIRECTS]?.let { deserializeDcRedirects(it) }
                ?: ProxyConfig().dcRedirects,
            bufferSize = (prefs[Keys.BUFFER_SIZE] ?: 256).coerceIn(4, 4096) * 1024,
            poolSize = (prefs[Keys.POOL_SIZE] ?: 4).coerceIn(0, 16),
            cfProxyEnabled = prefs[Keys.CF_PROXY_ENABLED] ?: true,
            cfProxyPriority = prefs[Keys.CF_PROXY_PRIORITY] ?: true,
            cfProxyFirst = prefs[Keys.CF_PROXY_FIRST] ?: false,
            autoOptimizeConnection = prefs[Keys.AUTO_OPTIMIZE_CONNECTION] ?: true,
            keepCpuAwake = prefs[Keys.KEEP_CPU_AWAKE] ?: false,
            preconnectWebSockets = prefs[Keys.PRECONNECT_WEBSOCKETS] ?: false,
            routeProbesEnabled = prefs[Keys.ROUTE_PROBES_ENABLED] ?: false,
            cfDomainRefreshEnabled = prefs[Keys.CF_DOMAIN_REFRESH_ENABLED] ?: false,
            webSocketPingIntervalSeconds = (prefs[Keys.WS_PING_INTERVAL_SECONDS] ?: 180).coerceIn(30, 300),
            startOnBoot = prefs[Keys.START_ON_BOOT] ?: false,
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
        })
    }

    /**
     * Get the current config snapshot.
     */
    suspend fun getConfig(): ProxyConfig = configFlow.first()

    val powerProfileNoticePending: Flow<Boolean> = context.dataStore.data
        .map { it[Keys.POWER_PROFILE_NOTICE_PENDING] ?: false }
        .distinctUntilChanged()

    suspend fun dismissPowerProfileNotice() {
        context.dataStore.edit { it[Keys.POWER_PROFILE_NOTICE_PENDING] = false }
    }

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
            // Start-on-boot is saved immediately by saveStartOnBoot(), outside UI debounce.
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

    suspend fun saveStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[Keys.START_ON_BOOT] = enabled }
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
