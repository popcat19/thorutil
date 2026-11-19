package dev.popcat19.thoremu.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.popcat19.thoremu.PinKeeperService
import dev.popcat19.thoremu.PinMode
import dev.popcat19.thoremu.Prefs

// Cycles Off -> Bottom -> Top -> Off. Off also stops the keeper.
class ImePinTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val next = when {
            !Prefs.pinManaged(this) -> PinMode.BOTTOM
            Prefs.pinMode(this) == PinMode.BOTTOM -> PinMode.TOP
            else -> PinMode.OFF
        }
        Prefs.setPin(this, next != PinMode.OFF, if (next == PinMode.OFF) Prefs.pinMode(this) else next)
        Thread {
            PinMode.apply(next)
            PinKeeperService.sync(applicationContext)
        }.start()
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val managed = Prefs.pinManaged(this)
        tile.state = if (managed) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (managed) Prefs.pinMode(this).label else "Off"
        tile.updateTile()
    }
}
