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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.ayn.AynCard
import dev.popcat19.thorutil.feedback.FeedbackCard
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
    var tick by remember { mutableIntStateOf(0) }
    var changelogEpoch by remember { mutableIntStateOf(0) }
    var showChangelog by remember { mutableStateOf(false) }
    var showAllChangelog by remember { mutableStateOf(false) }

    val pending = remember(changelogEpoch) { Changelog.pending(ctx) }

    LaunchedEffect(openChangelogSignal) {
        if (openChangelogSignal > 0) {
            showAllChangelog = pending.isEmpty()
            showChangelog = true
        }
    }

    LaunchedEffect(tick) {
        backend = withContext(Dispatchers.IO) { Priv.backend() }
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
                    Text(
                        "${pending.size} new preference options",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
                            changelogEpoch++
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        runCatching { if (Shizuku.pingBinder()) Shizuku.requestPermission(1) }
                        scope.launch { delay(1500); tick++ }
                    }) { Text("Request Shizuku") }
                    OutlinedButton(onClick = { Priv.recheck(); tick++ }) { Text("Recheck") }
                }
            }
        }

        PinCard(changelogEpoch)
        RotationCard(changelogEpoch)
        DensityCard()
        VolumeCard()
        FanCard(changelogEpoch, uiActive)
        PerfCard(changelogEpoch, uiActive)
        MouseCard(changelogEpoch, uiActive)
        AynCard(changelogEpoch)
        AutoLaunchCard()
        FeedbackCard()
        ExportCard()

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
                changelogEpoch++
            },
            onApplied = {
                showChangelog = false
                changelogEpoch++
            },
        )
    }
}

@Composable
private fun PinCard(epoch: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var managed by remember(epoch) { mutableStateOf(Prefs.pinManaged(ctx)) }
    var mode by remember(epoch) { mutableStateOf(Prefs.pinMode(ctx)) }
    var rebindFix by remember(epoch) { mutableStateOf(Prefs.imeRebindFix(ctx)) }
    var live by remember { mutableStateOf(PinMode.current(ctx)) }

    fun commit(newManaged: Boolean, newMode: PinMode) {
        managed = newManaged
        mode = newMode
        Prefs.setPin(ctx, newManaged, newMode)
        scope.launch {
            withContext(Dispatchers.IO) {
                if (newManaged) PinMode.apply(newMode)
                KeeperService.sync(ctx.applicationContext)
            }
            delay(300)
            live = PinMode.current(ctx)
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep IME pin", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = managed, onCheckedChange = { commit(it, mode) })
            }
            val choices = listOf(PinMode.TOP, PinMode.BOTTOM)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                choices.forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = mode == m,
                        onClick = { commit(managed, m) },
                        shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                    ) { Text(m.label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Auto-rebind IME target")
                    Text("Fixes missing keyboard after rotation, unlock, or bottom-screen use", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = rebindFix, onCheckedChange = {
                    rebindFix = it
                    Prefs.setImeRebindFix(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Current system state: ${live.label}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { PinMode.rebindIme(ctx) }
                        live = PinMode.current(ctx)
                    }
                }) { Text("Rebind IME") }
            }
        }
    }
}

@Composable
private fun RotationCard(epoch: Int) {
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rotate hint when held the other way", Modifier.weight(1f))
                Switch(checked = hint, enabled = overlay, onCheckedChange = {
                    hint = it
                    Prefs.setRotationHint(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bottom screen follows", Modifier.weight(1f))
                Switch(checked = bottom, enabled = overlay, onCheckedChange = {
                    bottom = it
                    Prefs.setRotationBottom(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Include Reverse Portrait in tile & hint", Modifier.weight(1f))
                Switch(checked = revCycle, enabled = overlay, onCheckedChange = {
                    revCycle = it
                    Prefs.setRotationReverseInCycle(ctx, it)
                })
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

@Composable
private fun MouseCard(epoch: Int, uiActive: Boolean = true) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Daemon.State(false, false)) }
    var onBoot by remember(epoch) { mutableStateOf(Prefs.daemonOnBoot(ctx)) }
    var lockOff by remember(epoch) { mutableStateOf(Prefs.lockSuspend(ctx)) }
    var rotDpad by remember(epoch) { mutableStateOf(Prefs.mouseRotateDpad(ctx)) }
    var dz by remember { mutableFloatStateOf(Prefs.deadzone(ctx)) }
    var ptr by remember { mutableFloatStateOf(Prefs.pointerSpeed(ctx)) }
    var scr by remember { mutableFloatStateOf(Prefs.scrollSpeed(ctx)) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { Daemon.state() }
    }
    LaunchedEffect(uiActive) { while (uiActive) { refresh(); delay(2000) } }

    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        delay(300)
        refresh()
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mouse emulation", style = MaterialTheme.typography.titleMedium)
            Text(
                when {
                    !state.running -> "Daemon stopped"
                    state.active -> "Daemon running, mouse mode on"
                    else -> "Daemon running, mouse mode off"
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    Button(onClick = { act { Daemon.toggle() } }) { Text(if (state.active) "Mouse off" else "Mouse on") }
                    OutlinedButton(onClick = { act { Daemon.stop() } }) { Text("Stop daemon") }
                } else {
                    Button(onClick = { act { Daemon.start(ctx) } }) { Text("Start daemon") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Keep daemon running", modifier = Modifier.weight(1f))
                Switch(checked = onBoot, onCheckedChange = { onBoot = it; Prefs.setDaemonOnBoot(ctx, it); KeeperService.sync(ctx.applicationContext) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Off on lockscreen", modifier = Modifier.weight(1f))
                Switch(checked = lockOff, onCheckedChange = {
                    lockOff = it
                    Prefs.setLockSuspend(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rotate D-pad in portrait")
                    Text("Mouse mode only; games outside mouse mode stay untouched", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = rotDpad, onCheckedChange = {
                    rotDpad = it
                    Prefs.setMouseRotateDpad(ctx, it)
                    act { if (Daemon.state().running) Daemon.restart(ctx) }
                })
            }
            NumberSetting("Deadzone", dz, 0f..0.4f, 0.01f, 2) { dz = it }
            NumberSetting("Pointer speed", ptr, 200f..4000f, 50f, 0, "px/s") { ptr = it }
            NumberSetting("Scroll speed", scr, 1f..60f, 1f, 0, "detents/s") { scr = it }
            OutlinedButton(onClick = {
                Prefs.setTuning(ctx, dz, ptr, scr)
                act { if (Daemon.state().running) Daemon.restart(ctx) }
            }) { Text("Apply tuning") }
        }
    }
}

@Composable
private fun OdinShortcutRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var keys by remember { mutableStateOf<OdinShortcut.Keys?>(null) }
    suspend fun refresh() {
        keys = withContext(Dispatchers.IO) { OdinShortcut.read() }
    }
    LaunchedEffect(Unit) { refresh() }
    fun act(block: () -> Unit) = scope.launch {
        withContext(Dispatchers.IO) { block() }
        refresh()
    }
    val k = keys ?: return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Odin mouse hotkey: ${k.label}", modifier = Modifier.weight(1f))
        if (!k.cleared) {
            OutlinedButton(onClick = { act { OdinShortcut.clear(ctx) } }) { Text("Clear") }
        } else if (Prefs.odinKeys(ctx) != null) {
            OutlinedButton(onClick = { act { OdinShortcut.restore(ctx) } }) { Text("Restore") }
        }
    }
}
