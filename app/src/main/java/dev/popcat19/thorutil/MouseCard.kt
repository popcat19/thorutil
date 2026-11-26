package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MouseCard(epoch: Int, uiActive: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Daemon.State(false, false)) }
    var onBoot by remember(epoch) { mutableStateOf(Prefs.daemonOnBoot(ctx)) }
    var lockOff by remember(epoch) { mutableStateOf(Prefs.lockSuspend(ctx)) }
    var rotDpad by remember(epoch) { mutableStateOf(Prefs.mouseRotateDpad(ctx)) }
    var dz by remember(epoch) { mutableFloatStateOf(Prefs.deadzone(ctx)) }
    var ptr by remember(epoch) { mutableFloatStateOf(Prefs.pointerSpeed(ctx)) }
    var scr by remember(epoch) { mutableFloatStateOf(Prefs.scrollSpeed(ctx)) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { Daemon.state() }
    }
    LaunchedEffect(uiActive) { while (uiActive) { refresh(); delay(2000) } }

    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        delay(300)
        refresh()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mouse emulation", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !state.running -> "Daemon stopped"
                    state.active -> "Daemon running, mouse mode on"
                    else -> "Daemon running, mouse mode off"
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    Button(onClick = { act { Daemon.toggle() } }) { Text(if (state.active) "Mouse off" else "Mouse on") }
                    OutlinedButton(onClick = { act { Daemon.stop() } }) { Text("Stop daemon") }
                } else {
                    Button(onClick = { act { Daemon.start(ctx) } }) { Text("Start daemon") }
                }
            }
            SwitchRow("Keep daemon running", onBoot) {
                onBoot = it
                Prefs.setDaemonOnBoot(ctx, it)
                KeeperService.sync(ctx.applicationContext)
            }
            SwitchRow("Off on lockscreen", lockOff) {
                lockOff = it
                Prefs.setLockSuspend(ctx, it)
                KeeperService.sync(ctx.applicationContext)
            }
            SwitchRow(
                title = "Rotate D-pad in portrait",
                subtitle = "Mouse mode only; games outside mouse mode stay untouched",
                checked = rotDpad,
            ) {
                rotDpad = it
                Prefs.setMouseRotateDpad(ctx, it)
                act { if (Daemon.state().running) Daemon.restart(ctx) }
            }
            NumberSetting("Deadzone", dz, 0f..0.4f, 0.01f, 2) { dz = it }
            NumberSetting("Pointer speed", ptr, 200f..4000f, 50f, 0, "px/s") { ptr = it }
            NumberSetting("Scroll speed", scr, 1f..60f, 1f, 0, "detents/s") { scr = it }
            OutlinedButton(onClick = {
                Prefs.setTuning(ctx, dz, ptr, scr)
                act { if (Daemon.state().running) Daemon.restart(ctx) }
            }) { Text("Apply tuning") }
        }
    }
}

@Composable
fun OdinShortcutRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<OdinShortcut.Keys?>(null) }
    suspend fun refresh() {
        keys = withContext(Dispatchers.IO) { OdinShortcut.read(ctx) }
    }
    LaunchedEffect(Unit) { refresh() }
    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        refresh()
    }
    val k = keys ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Odin mouse hotkey: ${k.label}", modifier = Modifier.weight(1f))
        if (!k.cleared) {
            OutlinedButton(onClick = { act { OdinShortcut.clear(ctx) } }) { Text("Clear") }
        } else if (Prefs.odinKeys(ctx) != null) {
            OutlinedButton(onClick = { act { OdinShortcut.restore(ctx) } }) { Text("Restore") }
        }
    }
}
