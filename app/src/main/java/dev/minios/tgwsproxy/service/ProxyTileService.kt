package dev.minios.tgwsproxy.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import dev.minios.tgwsproxy.R

/**
 * Quick Settings tile for toggling the proxy on/off from the notification shade.
 */
class ProxyTileService : TileService() {

    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        when (ProxyService.serviceState.value) {
            ProxyServiceState.RUNNING,
            ProxyServiceState.STARTING,
            ProxyServiceState.RESTARTING -> {
                ProxyService.stop(applicationContext)
                updateTile()
            }
            ProxyServiceState.STOPPING -> return
            ProxyServiceState.STOPPED -> {
                scope?.cancel()
                scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                scope?.launch {
                    ProxyService.start(applicationContext)
                    // Give the service a moment to start
                    delay(500)
                    withContext(Dispatchers.Main) { updateTile() }
                }
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val serviceState = ProxyService.serviceState.value
        tile.state = when (serviceState) {
            ProxyServiceState.RUNNING -> Tile.STATE_ACTIVE
            ProxyServiceState.STOPPED -> Tile.STATE_INACTIVE
            ProxyServiceState.STARTING,
            ProxyServiceState.RESTARTING,
            ProxyServiceState.STOPPING -> Tile.STATE_UNAVAILABLE
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
