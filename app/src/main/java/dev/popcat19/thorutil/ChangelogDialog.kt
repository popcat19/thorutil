package dev.popcat19.thorutil

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChangelogDialog(
    ctx: Context,
    items: List<PrefChange>,
    title: String,
    onDismiss: () -> Unit,
    onApplied: () -> Unit,
) {
    val picks = remember(items) {
        mutableStateMapOf<String, Boolean>().apply {
            for (c in items) put(c.id, c.isNew(ctx))
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "All new options default to legacy behavior. Toggle any changes to adopt; your choices are remembered unless an option changes again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { items.forEach { picks[it.id] = true } }) {
                        Text("Select all new")
                    }
                    OutlinedButton(onClick = { items.forEach { picks[it.id] = false } }) {
                        Text("Keep legacy")
                    }
                }
                items.forEachIndexed { i, c ->
                    if (i > 0) HorizontalDivider()
                    val wantNew = picks[c.id] == true
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${c.title} (v${c.version})", style = MaterialTheme.typography.labelLarge)
                            Text(c.summary, style = MaterialTheme.typography.bodySmall)
                            Text(
                                if (wantNew) c.newLabel else c.legacyLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (wantNew) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = wantNew, onCheckedChange = { picks[c.id] = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                for (c in items) {
                    if (picks[c.id] == true) c.applyNew(ctx) else c.applyLegacy(ctx)
                }
                Changelog.markSeen(ctx, items)
                Changelog.syncAfterApply(ctx)
                onApplied()
            }) { Text("Save choices") }
        },
        dismissButton = {
            TextButton(onClick = {
                Changelog.markSeen(ctx, items)
                onDismiss()
            }) { Text("Keep current") }
        },
    )
}
