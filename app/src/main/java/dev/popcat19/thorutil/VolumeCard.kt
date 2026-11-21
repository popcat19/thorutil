package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VolumeCard() {
    val ctx = LocalContext.current
    var link by remember { mutableStateOf(Prefs.volumeLink(ctx)) }
    var offset by remember { mutableStateOf(Prefs.volumeOffset(ctx)) }

    fun resync() = Thread { VolumeLink.sync(ctx.applicationContext, force = true) }.start()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bottom speaker volume", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Follow media volume")
                    Text("Writes ${VolumeLink.KEY} (0–${VolumeLink.MAX})", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = link, onCheckedChange = {
                    link = it
                    Prefs.setVolumeLink(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
                    if (it) resync()
                })
            }
            NumberSetting(
                label = "Offset",
                value = offset.toFloat(),
                range = -VolumeLink.MAX.toFloat()..VolumeLink.MAX.toFloat(),
                step = 1f,
                decimals = 0,
                unit = "steps",
                slider = false,
            ) {
                offset = it.toInt()
                Prefs.setVolumeOffset(ctx, offset)
                if (link) resync()
            }
        }
    }
}
