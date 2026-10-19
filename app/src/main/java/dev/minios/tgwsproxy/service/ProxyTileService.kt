package dev.minios.tgwsproxy.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.R

/**
 * Quick Settings tile for toggling the proxy on/off from the notification shade.
 */
class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null
    private var enabled: Boolean? = null

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        scope?.launch {
            try {
                combine(ConfigRepository(applicationContext).proxyEnabled, ProxyService.serviceState) { intent, _ -> intent }
                    .collect {
                        enabled = it
                        updateTile()
                    }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                enabled = null
                updateTile()
                dev.minios.tgwsproxy.diagnostics.DiagnosticLogger.failure("tile_intent_read_failed", error)
            }
        }
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        ProxyService.toggle(applicationContext)
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val serviceState = ProxyService.serviceState.value
        tile.state = when (enabled) {
            true -> Tile.STATE_ACTIVE
            false -> Tile.STATE_INACTIVE
            null -> Tile.STATE_UNAVAILABLE
        }
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when (serviceState) {
                ProxyServiceState.RUNNING -> getString(R.string.tile_active)
                ProxyServiceState.STOPPED -> getString(R.string.tile_inactive)
                ProxyServiceState.STARTING -> getString(R.string.proxy_starting)
                ProxyServiceState.RESTARTING -> getString(R.string.proxy_restarting)
                ProxyServiceState.STOPPING -> getString(R.string.proxy_stopping)
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }
}
