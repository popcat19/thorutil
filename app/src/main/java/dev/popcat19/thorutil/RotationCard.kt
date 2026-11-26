package dev.popcat19.thorutil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RotationCard(epoch: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(epoch) { mutableStateOf(Prefs.rotation(ctx)) }
    var overlay by remember { mutableStateOf(Rotation.canOverlay(ctx)) }
    var hint by remember(epoch) { mutableStateOf(Prefs.rotationHint(ctx)) }
    var bottom by remember(epoch) { mutableStateOf(Prefs.rotationBottom(ctx)) }
    var revCycle by remember(epoch) { mutableStateOf(Prefs.rotationReverseInCycle(ctx)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Display rotation", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                RotationMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        enabled = overlay,
                        onClick = {
                            mode = m
                            Prefs.setRotation(ctx, m)
                            KeeperService.sync(ctx.applicationContext)
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, RotationMode.entries.size),
                    ) { Text(m.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            SwitchRow("Rotate hint when held the other way", hint, enabled = overlay) {
                hint = it
                Prefs.setRotationHint(ctx, it)
                KeeperService.sync(ctx.applicationContext)
            }
            SwitchRow("Bottom screen follows", bottom, enabled = overlay) {
                bottom = it
                Prefs.setRotationBottom(ctx, it)
                KeeperService.sync(ctx.applicationContext)
            }
            SwitchRow("Include Reverse Portrait in tile & hint", revCycle, enabled = overlay) {
                revCycle = it
                Prefs.setRotationReverseInCycle(ctx, it)
            }
            if (!overlay) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                        overlay = Rotation.canOverlay(ctx)
                    }
                }) { Text("Grant overlay") }
            }
        }
    }
}
