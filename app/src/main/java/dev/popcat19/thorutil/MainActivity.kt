package dev.popcat19.thorutil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.ayn.AynCard
import dev.popcat19.thorutil.feedback.FeedbackCard
import dev.popcat19.thorutil.launcher.LauncherCard
import dev.popcat19.thorutil.recents.RecentsCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val openChangelogSignal = mutableIntStateOf(0)
    private val uiActive = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        uiActive.value = true
    }

    override fun onStop() {
        uiActive.value = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeeperService.sync(applicationContext)
        if (intent?.getBooleanExtra(Changelog.EXTRA_OPEN_CHANGELOG, false) == true) {
            openChangelogSignal.intValue++
        }
        setContent {
            val scheme = runCatching { dynamicDarkColorScheme(this) }.getOrElse { darkColorScheme() }
            MaterialTheme(colorScheme = scheme) {
                Scaffold { pad -> Screen(Modifier.padding(pad), openChangelogSignal.intValue, uiActive.value) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Changelog.EXTRA_OPEN_CHANGELOG, false)) {
            openChangelogSignal.intValue++
        }
    }
}

@Composable
private fun Screen(modifier: Modifier, openChangelogSignal: Int, uiActive: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(Priv.Backend.NONE) }
    var diag by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var prefsEpoch by remember { mutableIntStateOf(0) }
    var showChangelog by remember { mutableStateOf(false) }
    var showAllChangelog by remember { mutableStateOf(false) }

    val pending = remember(prefsEpoch) { Changelog.pending(ctx) }

    LaunchedEffect(openChangelogSignal) {
        if (openChangelogSignal > 0) {
            showAllChangelog = pending.isEmpty()
            showChangelog = true
        }
    }

    LaunchedEffect(tick) {
        backend = withContext(Dispatchers.IO) { Priv.backend() }
        diag = withContext(Dispatchers.IO) { Priv.diagnose() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ThorUtil", style = MaterialTheme.typography.headlineMedium)

        if (pending.isNotEmpty()) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.size} new preference options", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Legacy defaults stay intact until opted in. Review and cherry-pick any new behaviors.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            showAllChangelog = false
                            showChangelog = true
                        }) { Text("Review changes") }
                        OutlinedButton(onClick = {
                            Changelog.markSeen(ctx, pending)
                            prefsEpoch++
                        }) { Text("Dismiss") }
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Privilege", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (backend) {
                        Priv.Backend.SHIZUKU -> "✓ Shizuku"
                        Priv.Backend.ROOT -> "✓ Root"
                        Priv.Backend.NONE -> "✗ None. Start Shizuku and grant access, or grant root."
                    }
                )
                if (diag.isNotEmpty()) Text(diag, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(1) }
                        scope.launch { delay(1500); tick++ }
                    }) { Text("Request Shizuku") }
                    OutlinedButton(onClick = { Priv.recheck(); tick++ }) { Text("Recheck") }
                }
            }
        }

        PinCard(prefsEpoch)
        RotationCard(prefsEpoch)
        PipCard(prefsEpoch)
        DensityCard(prefsEpoch)
        VolumeCard(prefsEpoch)
        FanCard(prefsEpoch, uiActive)
        PerfCard(prefsEpoch, uiActive)
        MouseCard(prefsEpoch, uiActive)
        AynCard(prefsEpoch, uiActive)
        RecentsCard(prefsEpoch, uiActive)
        LauncherCard(prefsEpoch)
        AutoLaunchCard(prefsEpoch)
        FeedbackCard(prefsEpoch)
        ExportCard(onImported = { prefsEpoch++ })

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Preference changelog", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Review all opt-in preference changes or re-apply defaults at any time.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    showAllChangelog = true
                    showChangelog = true
                }) { Text("Open preference changelog") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Mouse mapping", style = MaterialTheme.typography.titleMedium)
                Text("Left stick → pointer, right stick → scroll")
                Text("A → left, X → right, R3 → middle, B → back, Y → forward")
                Text("Hold R3 + left stick → middle-drag pan")
                Text("Double-press Select → toggle mouse mode")
                Text("Disable Odin mapping's own mouse mode while this runs.")
                OdinShortcutRow()
            }
        }
    }

    if (showChangelog) {
        val items = if (showAllChangelog || pending.isEmpty()) Changelog.changes else pending
        val title = if (showAllChangelog || pending.isEmpty()) "Preference changelog" else "New preference options"
        ChangelogDialog(
            ctx = ctx,
            items = items,
            title = title,
            onDismiss = {
                showChangelog = false
                prefsEpoch++
            },
            onApplied = {
                showChangelog = false
                prefsEpoch++
            },
        )
    }
}
