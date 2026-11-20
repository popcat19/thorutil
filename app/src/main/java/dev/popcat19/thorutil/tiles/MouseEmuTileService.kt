package dev.popcat19.thorutil.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.popcat19.thorutil.Daemon

class MouseEmuTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        Thread {
            val s = Daemon.state()
            if (!s.running) {
                if (Daemon.start(applicationContext)) {
                    Thread.sleep(300)
                    Daemon.toggle()
                }
            } else {
                Daemon.toggle()
            }
            Thread.sleep(200)
            refresh()
        }.start()
    }

    private fun refresh() {
        Thread {
            val s = Daemon.state()
            val tile = qsTile ?: return@Thread
            tile.state = if (s.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            tile.subtitle = when {
                !s.running -> "Stopped"
                s.active -> "On"
                else -> "Off"
            }
            tile.updateTile()
        }.start()
    }
}
