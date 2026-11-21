package dev.popcat19.thorutil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.popcat19.thorutil.ayn.AynCard
import dev.popcat19.thorutil.feedback.FeedbackCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        KeeperService.sync(applicationContext)
        setContent {
            val scheme = runCatching { dynamicDarkColorScheme(this) }.getOrElse { darkColorScheme() }
            MaterialTheme(colorScheme = scheme) {
                Scaffold { pad -> Screen(Modifier.padding(pad)) }
            }
        }
    }
}

@Composable
private fun Screen(modifier: Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var backend by remember { mutableStateOf(Priv.Backend.NONE) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(tick) {
        backend = withContext(Dispatchers.IO) { Priv.backend() }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ThorUtil", style = MaterialTheme.typography.headlineMedium)

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
                    OutlinedButton(onClick = { tick++ }) { Text("Recheck") }
                }
            }
        }

        PinCard()
        RotationCard()
        DensityCard()
        MouseCard()
        AynCard()
        FeedbackCard()

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
}

@Composable
private fun PinCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var managed by remember { mutableStateOf(Prefs.pinManaged(ctx)) }
    var mode by remember { mutableStateOf(Prefs.pinMode(ctx)) }
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
            Text("Current system state: ${live.label}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RotationCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf(Prefs.rotation(ctx)) }
    var overlay by remember { mutableStateOf(Rotation.canOverlay(ctx)) }
    var hint by remember { mutableStateOf(Prefs.rotationHint(ctx)) }
    var bottom by remember { mutableStateOf(Prefs.rotationBottom(ctx)) }

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
                    ) { Text(m.label) }
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
private fun MouseCard() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(Daemon.State(false, false)) }
    var onBoot by remember { mutableStateOf(Prefs.daemonOnBoot(ctx)) }
    var lockOff by remember { mutableStateOf(Prefs.lockSuspend(ctx)) }
    var dz by remember { mutableFloatStateOf(Prefs.deadzone(ctx)) }
    var ptr by remember { mutableFloatStateOf(Prefs.pointerSpeed(ctx)) }
    var scr by remember { mutableFloatStateOf(Prefs.scrollSpeed(ctx)) }

    suspend fun refresh() {
        state = withContext(Dispatchers.IO) { Daemon.state() }
    }
    LaunchedEffect(Unit) { while (true) { refresh(); delay(2000) } }

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
                Text("Start daemon at boot", modifier = Modifier.weight(1f))
                Switch(checked = onBoot, onCheckedChange = { onBoot = it; Prefs.setDaemonOnBoot(ctx, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Off on lockscreen", modifier = Modifier.weight(1f))
                Switch(checked = lockOff, onCheckedChange = {
                    lockOff = it
                    Prefs.setLockSuspend(ctx, it)
                    KeeperService.sync(ctx.applicationContext)
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

