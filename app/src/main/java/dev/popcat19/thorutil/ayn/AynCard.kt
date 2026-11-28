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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.SwitchRow
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
        val v = next.joinToString(":").filter { it.isLetterOrDigit() || it in "._/:$" }
        val cmd = if (v.isEmpty()) "settings delete secure $KEY" else "settings put secure $KEY '$v'"
        return Priv.sh("$cmd && settings put secure accessibility_enabled 1").code == 0
    }
}

@Composable
fun AynCard(epoch: Int = 0, uiActive: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(AynService.enabled(ctx)) }
    var focusCombos by remember(epoch) { mutableStateOf(Prefs.aynFocusCombos(ctx)) }
    var focusMovesPad by remember(epoch) { mutableStateOf(Prefs.aynFocusMovesPad(ctx)) }
    var homeDoubleRecents by remember(epoch) { mutableStateOf(Prefs.aynHomeDoubleRecents(ctx)) }
    var backGestures by remember(epoch) { mutableStateOf(Prefs.aynBackGestures(ctx)) }
    var homeSkipBdsa by remember(epoch) { mutableStateOf(Prefs.aynHomeSkipBdsa(ctx)) }
    var screenLock by remember(epoch) { mutableStateOf(Prefs.aynScreenLock(ctx)) }
    var scopedShoulders by remember(epoch) { mutableStateOf(Prefs.aynScopedShoulders(ctx)) }
    var locked by remember { mutableStateOf(AynActions.lockedDisplay()) }

    LaunchedEffect(uiActive) {
        while (uiActive) {
            on = AynService.enabled(ctx)
            locked = AynActions.lockedDisplay()
            delay(2000)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

            SwitchRow(
                title = "Focus combos (AYN + L1/R1/Select)",
                subtitle = "Disable so combos cannot unfocus a running game",
                checked = focusCombos,
            ) {
                focusCombos = it
                Prefs.setAynFocusCombos(ctx, it)
            }
            if (focusCombos) {
                SwitchRow(
                    title = "Focus combos steer gamepad",
                    subtitle = "Off: move mouse pointer only; keep pad focus locked",
                    checked = focusMovesPad,
                ) {
                    focusMovesPad = it
                    Prefs.setAynFocusMovesPad(ctx, it)
                }
            }
            SwitchRow(
                title = "Home 2x → Recents",
                subtitle = "Frees AYN + B",
                checked = homeDoubleRecents,
            ) {
                homeDoubleRecents = it
                Prefs.setAynHomeDoubleRecents(ctx, it)
            }
            SwitchRow(
                title = "Back 2x → Prev app · Back hold → Swap",
                subtitle = "Frees Back + L1/R1 and AYN + Start",
                checked = backGestures,
            ) {
                backGestures = it
                Prefs.setAynBackGestures(ctx, it)
            }
            SwitchRow(
                title = "Scoped Home/Back (hold + L1/R1)",
                subtitle = "L1 top, R1 bottom, ignores touch focus. Home + shoulder 2x opens recents there; Back + shoulder 2x goes to the previous app there; Home + L3 swaps focus, Back + L3 swaps windows.",
                checked = scopedShoulders,
            ) {
                scopedShoulders = it
                Prefs.setAynScopedShoulders(ctx, it)
            }
            SwitchRow(
                title = "Home skips AYN/BDSA overlay",
                subtitle = "Lone Home targets top screen when BDSA is open on bottom",
                checked = homeSkipBdsa,
            ) {
                homeSkipBdsa = it
                Prefs.setAynHomeSkipBdsa(ctx, it)
            }
            SwitchRow(
                title = "Screen lock (AYN + Y)",
                subtitle = "Lock screen & pad focus; routes lone Home/Back/Prev app to other screen",
                checked = screenLock,
            ) {
                screenLock = it
                Prefs.setAynScreenLock(ctx, it)
                if (!it) {
                    locked = null
                    Bg.post { AynActions.unlock(ctx, notify = false) }
                }
            }
            if (locked != null) {
                val label = if (locked == AynActions.BOTTOM) "bottom" else "top"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Locked: $label screen",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { AynActions.unlock(ctx, notify = true) }
                            locked = AynActions.lockedDisplay()
                        }
                    }) { Text("Unlock") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 4.dp))

            AynBinds.aynBinds(ctx).forEach { (code, a) ->
                Text("AYN + ${AynBinds.label(code)} → ${a.label}", style = MaterialTheme.typography.bodySmall)
            }
            AynBinds.HOME.forEach { (code, d) ->
                Text("Home + ${AynBinds.label(code)} → Home on ${if (d == AynActions.BOTTOM) "bottom" else "top"}", style = MaterialTheme.typography.bodySmall)
            }
            if (homeDoubleRecents) {
                Text("Home 2x → Recents toggle", style = MaterialTheme.typography.bodySmall)
            }
            if (backGestures) {
                Text("Back 2x → Previous app (target screen)", style = MaterialTheme.typography.bodySmall)
                Text("Back hold → Swap windows", style = MaterialTheme.typography.bodySmall)
            } else {
                AynBinds.BACK.forEach { (code, a) ->
                    Text("Back + ${AynBinds.label(code)} → ${a.label}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Lone AYN tap → AYN menu · Lone Back → back on target screen", style = MaterialTheme.typography.bodySmall)
            Text("Clear other apps' AYN binds first.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
