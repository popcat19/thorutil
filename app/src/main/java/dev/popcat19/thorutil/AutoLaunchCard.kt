package dev.popcat19.thorutil

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AutoLaunchCard(epoch: Int = 0) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var apps by remember(epoch) { mutableStateOf(AutoLaunch.list(ctx)) }
    var showPicker by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun update(next: List<ManagedApp>) {
        apps = next
        AutoLaunch.save(ctx, next)
        KeeperService.sync(ctx.applicationContext)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Boot & background apps", style = MaterialTheme.typography.titleMedium)
            Text(
                "Whitelists battery/standby, launches at boot then returns home, and watches keep-alive apps (equalizers, proxies, VPNs).",
                style = MaterialTheme.typography.bodySmall,
            )
            if (apps.isEmpty()) {
                Text("No managed apps yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                apps.forEachIndexed { idx, app ->
                    if (idx > 0) HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.pkg, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { update(apps.filterNot { it.pkg == app.pkg }) }) {
                                Text("Remove")
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    update(apps.map { if (it.pkg == app.pkg) it.copy(onBoot = !it.onBoot) else it })
                                },
                            ) {
                                Checkbox(
                                    checked = app.onBoot,
                                    onCheckedChange = { v ->
                                        update(apps.map { if (it.pkg == app.pkg) it.copy(onBoot = v) else it })
                                    },
                                )
                                Text("Boot", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    update(apps.map { if (it.pkg == app.pkg) it.copy(keepAlive = !it.keepAlive) else it })
                                },
                            ) {
                                Checkbox(
                                    checked = app.keepAlive,
                                    onCheckedChange = { v ->
                                        update(apps.map { if (it.pkg == app.pkg) it.copy(keepAlive = v) else it })
                                    },
                                )
                                Text("Keep alive", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showPicker = true }) { Text("Add app") }
                if (apps.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        status = "Applying whitelist & launching..."
                        scope.launch {
                            withContext(Dispatchers.IO) { AutoLaunch.runBootLaunch(ctx) }
                            status = "Whitelist applied and boot apps checked"
                        }
                    }) { Text("Run now") }
                }
            }
        }
    }

    if (showPicker) {
        var installed by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
        LaunchedEffect(Unit) {
            installed = withContext(Dispatchers.IO) { AutoLaunch.installedLaunchable(ctx) }
        }
        val existing = remember(apps) { apps.map { it.pkg }.toSet() }
        var query by remember { mutableStateOf("") }
        val filtered = remember(installed, existing, query) {
            val list = installed ?: return@remember emptyList()
            val q = query.trim().lowercase()
            list.filter { (pkg, label) ->
                pkg !in existing && (q.isEmpty() || label.lowercase().contains(q) || pkg.lowercase().contains(q))
            }
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Add background app") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search apps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (installed == null) {
                        Text("Loading installed apps...", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            items(filtered, key = { it.first }) { (pkg, label) ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val added = ManagedApp(pkg = pkg, label = label)
                                            update(apps + added)
                                            Bg.post { AutoLaunch.whitelist(pkg) }
                                            showPicker = false
                                        }
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(pkg, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("Close") }
            },
        )
    }
}
