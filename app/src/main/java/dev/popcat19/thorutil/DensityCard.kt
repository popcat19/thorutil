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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.Density.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DensityCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val info = remember { mutableStateMapOf<Panel, Density.Info>() }
    val draft = remember { mutableStateMapOf<Panel, Float>() }
    var presets by remember { mutableStateOf(Density.presets(ctx)) }
    var name by remember { mutableStateOf("") }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        Panel.entries.forEach { p -> Density.read(p)?.let { info[p] = it; draft[p] = it.effective.toFloat() } }
    }
    LaunchedEffect(Unit) { refresh() }
    fun act(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; refresh() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Display density", style = MaterialTheme.typography.titleMedium)
            if (info.isEmpty()) {
                Text("Needs Shizuku or root.")
                return@Column
            }
            Panel.entries.forEach { p ->
                val i = info[p] ?: return@forEach
                NumberSetting(
                    "${p.label}: ${i.effective} dpi (physical ${i.physical})",
                    draft[p] ?: i.effective.toFloat(),
                    Density.MIN.toFloat()..Density.MAX.toFloat(), 10f, 0, slider = false,
                ) { draft[p] = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    act { Panel.entries.forEach { p -> draft[p]?.let { if (it.toInt() != info[p]?.effective) Density.set(p, it.toInt()) } } }
                }) { Text("Apply") }
                OutlinedButton(onClick = { act { Panel.entries.forEach { Density.reset(it) } } }) { Text("Reset both") }
            }

            Text("Presets", style = MaterialTheme.typography.titleSmall)
            presets.forEach { pr ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${pr.name}: ${pr.top} / ${pr.bottom}", Modifier.weight(1f))
                    TextButton(onClick = {
                        act { Density.set(Panel.TOP, pr.top); Density.set(Panel.BOTTOM, pr.bottom) }
                    }) { Text("Apply") }
                    TextButton(onClick = {
                        presets = presets - pr
                        Density.savePresets(ctx, presets)
                    }) { Text("Delete") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, Modifier.weight(1f), singleLine = true, label = { Text("Preset name") })
                OutlinedButton(enabled = name.isNotBlank(), onClick = {
                    val t = draft[Panel.TOP]?.toInt() ?: return@OutlinedButton
                    val b = draft[Panel.BOTTOM]?.toInt() ?: return@OutlinedButton
                    presets = presets.filter { it.name != name.trim() } + Density.Preset(name.trim(), t, b)
                    Density.savePresets(ctx, presets)
                    name = ""
                }) { Text("Save") }
            }
        }
    }
}
