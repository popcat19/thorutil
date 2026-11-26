package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun PinCard(epoch: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var managed by remember(epoch) { mutableStateOf(Prefs.pinManaged(ctx)) }
    var mode by remember(epoch) { mutableStateOf(Prefs.pinMode(ctx)) }
    var rebindFix by remember(epoch) { mutableStateOf(Prefs.imeRebindFix(ctx)) }
    var live by remember(epoch) { mutableStateOf(PinMode.current(ctx)) }

    fun commit(newManaged: Boolean, newMode: PinMode) {
        managed = newManaged
        mode = newMode
        Prefs.setPin(ctx, newManaged, newMode)
        scope.launch {
            withContext(Dispatchers.IO) {
                if (newManaged) PinMode.apply(newMode)
                KeeperService.sync(ctx.applicationContext)
            }
            delay(300)
            live = PinMode.current(ctx)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep IME pin", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = managed, onCheckedChange = { commit(it, mode) })
            }
            val choices = listOf(PinMode.TOP, PinMode.BOTTOM)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { commit(managed, m) },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    ) { Text(m.label) }
                }
            }
            SwitchRow(
                title = "Auto-rebind IME target",
                subtitle = "Fixes missing keyboard after rotation, unlock, or bottom-screen use",
                checked = rebindFix,
            ) {
                rebindFix = it
                Prefs.setImeRebindFix(ctx, it)
                KeeperService.sync(ctx.applicationContext)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current system state: ${live.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { PinMode.rebindIme(ctx) }
                        live = PinMode.current(ctx)
                    }
                }) { Text("Rebind IME") }
            }
        }
    }
}
