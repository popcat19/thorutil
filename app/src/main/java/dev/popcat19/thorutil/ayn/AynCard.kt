package dev.popcat19.thorutil.ayn

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.Priv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AynService {
    private const val KEY = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

    private fun id(ctx: Context) = ComponentName(ctx, AynKeyService::class.java).flattenToString()

    private fun list(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, KEY) ?: "").split(':').filter { it.isNotBlank() }

    fun enabled(ctx: Context) = id(ctx) in list(ctx)

    // Shell holds WRITE_SECURE_SETTINGS, so no trip through the accessibility settings page.
    fun set(ctx: Context, on: Boolean): Boolean {
        val me = id(ctx)
        val next = list(ctx).filter { it != me } + if (on) listOf(me) else emptyList()
        val v = next.joinToString(":").filter { it.isLetterOrDigit() || it in "._/:\$" }
        val cmd = if (v.isEmpty()) "settings delete secure $KEY" else "settings put secure $KEY '$v'"
        return Priv.sh("$cmd && settings put secure accessibility_enabled 1").code == 0
    }
}

@Composable
fun AynCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(AynService.enabled(ctx)) }
    LaunchedEffect(Unit) { while (true) { on = AynService.enabled(ctx); delay(2000) } }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("AYN combos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = { want ->
                    scope.launch {
                        withContext(Dispatchers.IO) { AynService.set(ctx, want) }
                        delay(300)
                        on = AynService.enabled(ctx)
                    }
                })
            }
            AynBinds.DEFAULT.forEach { (code, a) -> Text("AYN + ${AynBinds.label(code)} → ${a.label}") }
            Text("Lone AYN tap → AYN menu", style = MaterialTheme.typography.bodySmall)
            Text("Clear other apps' AYN binds first.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
