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
        val CF_PROXY_USER_DOMAIN = stringPreferencesKey("cf_proxy_user_domain")
    }

    /**
     * Stable initial secret: generated once and persisted immediately.
     * This ensures MainScreen always sees a consistent secret.
     */
    private val stableSecret: String by lazy {
        val existing = runBlocking {
            context.dataStore.data.first()[Keys.SECRET]
        }
        if (existing != null) {
            existing
        } else {
            val generated = ProxyConfig.generateSecret()
            runBlocking {
                context.dataStore.edit { prefs ->
                    // Double-check: another thread might have written it
                    if (prefs[Keys.SECRET] == null) {
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
            port = prefs[Keys.PORT] ?: 1443,
            secret = prefs[Keys.SECRET] ?: stableSecret,
            dcRedirects = prefs[Keys.DC_REDIRECTS]?.let { deserializeDcRedirects(it) }
                ?: ProxyConfig().dcRedirects,
            bufferSize = (prefs[Keys.BUFFER_SIZE] ?: 256) * 1024,
            poolSize = prefs[Keys.POOL_SIZE] ?: 4,
            cfProxyEnabled = prefs[Keys.CF_PROXY_ENABLED] ?: true,
            cfProxyPriority = prefs[Keys.CF_PROXY_PRIORITY] ?: true,
            cfProxyUserDomain = prefs[Keys.CF_PROXY_USER_DOMAIN] ?: "",
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
            prefs[Keys.CF_PROXY_USER_DOMAIN] = config.cfProxyUserDomain
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
                result[dc] = parts[1]
            }
        }
        return result
    }
}
