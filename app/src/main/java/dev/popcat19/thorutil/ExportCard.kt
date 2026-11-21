package dev.popcat19.thorutil

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf(*Export.Section.entries.toTypedArray()) }

    fun say(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val json = Export.export(ctx, picked.toSet())
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) } }
            }
            say(if (r.isSuccess) "Exported" else "Export failed: ${r.exceptionOrNull()?.message}")
        }
    }

    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val json = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    val n = Export.import(ctx, json, picked.toSet())
                    KeeperService.sync(ctx.applicationContext)
                    if (Daemon.state().running) Daemon.restart(ctx)
                    if (FanDaemon.state().running) FanDaemon.restart(ctx)
                    n
                }
            }
            r.onSuccess { say("Imported $it settings, reopen to refresh") }
                .onFailure { say("Import failed: ${it.message}") }
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Config", style = MaterialTheme.typography.titleMedium)
            Export.Section.entries.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(s in picked, { if (it) picked.add(s) else picked.remove(s) })
                    Text(s.label)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save.launch("thorutil-config.json") }, enabled = picked.isNotEmpty()) { Text("Export") }
                OutlinedButton(onClick = { open.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = picked.isNotEmpty()) { Text("Import") }
            }
        }
    }
}
