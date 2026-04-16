package dev.minios.tgwsproxy.service

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.*
import dev.minios.tgwsproxy.R
import dev.minios.tgwsproxy.data.ConfigRepository
import dev.minios.tgwsproxy.proxy.TgWsProxyServer

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
        if (ProxyService.isRunning) {
            ProxyService.stop(applicationContext)
            updateTile()
        } else {
            scope?.cancel()
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope?.launch {
                val config = ConfigRepository(applicationContext).getConfig()
                ProxyService.start(applicationContext, config)
                // Give the service a moment to start
                delay(500)
                withContext(Dispatchers.Main) { updateTile() }
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
        val running = ProxyService.isRunning
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        tile.subtitle = if (running) {
            getString(R.string.tile_active)
        } else {
            getString(R.string.tile_inactive)
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }
}
