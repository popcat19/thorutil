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
fun PipCard(epoch: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(epoch) { mutableStateOf(Prefs.pipFixMode(ctx)) }
    var overlay by remember { mutableStateOf(Rotation.canOverlay(ctx)) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Bottom screen PiP", style = MaterialTheme.typography.titleMedium)
            Text(
                when (mode) {
                    PipFixMode.OFF -> "Stock Android 13 ignores taps on bottom-screen PiP windows."
                    PipFixMode.OVERLAY -> "Tap bottom PiP for play/pause, expand, move to top, or close. Double-tap expands."
                    PipFixMode.MOVE_TOP -> "Moves bottom-screen PiP to the top screen so native drag, resize, and menu work."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                PipFixMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = {
                            mode = m
                            Prefs.setPipFixMode(ctx, m)
                            if (m == PipFixMode.OVERLAY && !overlay) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                                    overlay = Rotation.canOverlay(ctx)
                                    KeeperService.sync(ctx.applicationContext)
                                }
                            } else {
                                KeeperService.sync(ctx.applicationContext)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(i, PipFixMode.entries.size),
                    ) { Text(m.shortLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            if (mode == PipFixMode.OVERLAY && !overlay) {
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                        overlay = Rotation.canOverlay(ctx)
                        KeeperService.sync(ctx.applicationContext)
                    }
                }) { Text("Grant overlay") }
            }
        }
    }
}
