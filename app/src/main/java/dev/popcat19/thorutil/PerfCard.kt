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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun PerfCard(epoch: Int = 0, uiActive: Boolean = true) {
    val ctx = LocalContext.current
    var enabled by remember(epoch) { mutableStateOf(Prefs.perfAdaptive(ctx)) }
    var live by remember { mutableStateOf<PerfDaemon.State?>(null) }

    LaunchedEffect(enabled, uiActive) {
        if (!enabled) {
            live = null
            return@LaunchedEffect
        }
        while (uiActive) {
            live = withContext(Dispatchers.IO) { PerfDaemon.state() }
            delay(2000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Adaptive performance", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Adaptive Odin profile")
                    Text(
                        "Scales Odin performance_mode (Normal / Performance / High) from live CPU and GPU demand without throttling peak clocks",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = enabled, onCheckedChange = {
                    enabled = it
                    Prefs.setPerfAdaptive(ctx, it)
                    Thread {
                        if (it) PerfDaemon.start(ctx) else PerfDaemon.stop(ctx)
                        KeeperService.sync(ctx.applicationContext)
                    }.start()
                })
            }
            live?.let {
                val status = if (!it.running) "Daemon not running"
                else "${PerfDaemon.modeName(it.mode)} · CPU ${it.cpuPct}% (peak ${it.corePct}%) · GPU ${it.gpuPct}%"
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
