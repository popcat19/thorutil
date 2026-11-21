package dev.popcat19.thorutil.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.popcat19.thorutil.KeeperService
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Rotation
import dev.popcat19.thorutil.RotationMode

// Cycles the top display: Auto -> Portrait -> Landscape.
class RotationTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        if (!Rotation.canOverlay(this)) {
            Thread {
                Rotation.grantOverlay(this)
                if (Rotation.canOverlay(this)) cycle()
            }.start()
            return
        }
        cycle()
    }

    private fun cycle() {
        Prefs.setRotation(this, Prefs.rotation(this).next())
        KeeperService.sync(applicationContext)
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val mode = Prefs.rotation(this)
        tile.state = if (mode == RotationMode.AUTO) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle = mode.label
        tile.updateTile()
    }
}
