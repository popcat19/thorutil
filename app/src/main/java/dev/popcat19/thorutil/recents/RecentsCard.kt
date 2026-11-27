package dev.popcat19.thorutil.recents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Rotation
import dev.popcat19.thorutil.SwitchRow
import dev.popcat19.thorutil.feedback.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun RecentsCard(epoch: Int, uiActive: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember(epoch) { mutableStateOf(Prefs.recentsOverlay(ctx)) }
    var locked by remember(epoch) { mutableStateOf(Prefs.recentsLockedPackages(ctx).sorted()) }
    var overlay by remember { mutableStateOf(Rotation.canOverlay(ctx)) }
    var hasA11y by remember { mutableStateOf(Feedback.a11y != null) }
    var homeDouble by remember(epoch) { mutableStateOf(Prefs.aynHomeDoubleRecents(ctx)) }

    LaunchedEffect(uiActive) {
        while (uiActive) {
            locked = Prefs.recentsLockedPackages(ctx).sorted()
            overlay = Rotation.canOverlay(ctx)
            hasA11y = Feedback.a11y != null
            homeDouble = Prefs.aynHomeDoubleRecents(ctx)
            delay(1500)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Per-screen recents", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                title = "Custom recents overlay",
                subtitle = "Replaces stock Recents with a gamepad and touch switcher on the active screen. Respects AYN + Y screen lock.",
                checked = enabled,
            ) { on ->
                enabled = on
                Prefs.setRecentsOverlay(ctx, on)
                if (!on) {
                    RecentsOverlay.dismiss()
                } else if (!hasA11y && !overlay) {
                    scope.launch {
                        withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                        overlay = Rotation.canOverlay(ctx)
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (!hasA11y && !overlay) {
                        scope.launch {
                            withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                            overlay = Rotation.canOverlay(ctx)
                            RecentsOverlay.open(ctx)
                        }
                    } else {
                        RecentsOverlay.open(ctx)
                    }
                }) { Text("Open now") }
                if (enabled && !hasA11y && !overlay) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Rotation.grantOverlay(ctx) }
                            overlay = Rotation.canOverlay(ctx)
                        }
                    }) { Text("Grant overlay") }
                }
            }

            if (enabled) {
                val trigger = if (homeDouble) "Home 2x" else "AYN + B"
                Text(
                    "Trigger: $trigger. D-pad / stick navigate, L1/R1 tabs (Top / Bottom / All), A open, X close, Y lock, Start move screen, Select app info, R2/R3 clear all (2x), B dismiss.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (locked.isNotEmpty()) {
                Text("Locked apps (protected from close / clear all)", style = MaterialTheme.typography.labelLarge)
                for (pkg in locked) {
                    val label = remember(pkg) { RecentsTasks.appLabel(ctx, pkg) }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (label != pkg) {
                                Text(
                                    pkg,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        TextButton(onClick = {
                            Prefs.toggleRecentsLockedPackage(ctx, pkg)
                            locked = Prefs.recentsLockedPackages(ctx).sorted()
                            RecentsOverlay.refreshIfOpen(ctx)
                        }) { Text("Unlock") }
                    }
                }
            }
        }
    }
}
