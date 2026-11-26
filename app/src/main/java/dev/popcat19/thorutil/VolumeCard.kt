package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun VolumeCard(epoch: Int = 0) {
    val ctx = LocalContext.current
    var link by remember(epoch) { mutableStateOf(Prefs.volumeLink(ctx)) }
    var offset by remember(epoch) { mutableStateOf(Prefs.volumeOffset(ctx)) }

    fun resync() = Bg.post { VolumeLink.sync(ctx.applicationContext, force = true) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bottom speaker volume", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = "Follow media volume",
                subtitle = "Writes ${VolumeLink.KEY} (0–${VolumeLink.MAX})",
                checked = link,
            ) {
                link = it
                Prefs.setVolumeLink(ctx, it)
                KeeperService.sync(ctx.applicationContext)
                if (it) resync()
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
