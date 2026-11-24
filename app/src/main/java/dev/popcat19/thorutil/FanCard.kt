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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Point(val temp: Float, val pct: Float)

private fun parse(s: String): List<Point> = s.split(",").mapNotNull { p ->
    val (t, v) = p.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
    Point(t.toFloatOrNull() ?: return@mapNotNull null, v.toFloatOrNull() ?: return@mapNotNull null)
}

private fun format(points: List<Point>) =
    points.sortedBy { it.temp }.joinToString(",") { "${it.temp.toInt()}:${it.pct.toInt()}" }

@Composable
fun FanCard(epoch: Int = 0) {
    val ctx = LocalContext.current
    var managed by remember { mutableStateOf(Prefs.fanManaged(ctx)) }
    val points = remember {
        mutableStateListOf(*parse(Prefs.fanCurve(ctx)).ifEmpty { parse(Prefs.FAN_CURVE_DEFAULT) }.toTypedArray())
    }
    var tau by remember { mutableStateOf(Prefs.fanTau(ctx)) }
    var hyst by remember { mutableStateOf(Prefs.fanHyst(ctx)) }
    var up by remember { mutableStateOf(Prefs.fanSlewUp(ctx)) }
    var down by remember { mutableStateOf(Prefs.fanSlewDown(ctx)) }
    var guardC by remember { mutableStateOf(Prefs.fanGuardC(ctx)) }
    var guardS by remember { mutableStateOf(Prefs.fanGuardS(ctx).toFloat()) }
    var zeroBat by remember(epoch) { mutableStateOf(Prefs.fanZeroBattery(ctx)) }
    var zeroOff by remember { mutableStateOf(Prefs.fanZeroOffC(ctx)) }
    var zeroOn by remember { mutableStateOf(Prefs.fanZeroOnC(ctx)) }
    var live by remember { mutableStateOf<FanDaemon.State?>(null) }
    var dirty by remember { mutableStateOf(false) }

    LaunchedEffect(managed) {
        while (managed) {
            live = withContext(Dispatchers.IO) { FanDaemon.state() }
            delay(2000)
        }
        live = null
    }

    fun apply() = Thread {
        Prefs.setFanCurve(ctx, format(points))
        Prefs.setFanTau(ctx, tau)
        Prefs.setFanHyst(ctx, hyst)
        Prefs.setFanSlewUp(ctx, up)
        Prefs.setFanSlewDown(ctx, down)
        Prefs.setFanGuardC(ctx, guardC)
        Prefs.setFanGuardS(ctx, guardS.toInt())
        Prefs.setFanZeroBattery(ctx, zeroBat)
        Prefs.setFanZeroOffC(ctx, zeroOff)
        Prefs.setFanZeroOnC(ctx, zeroOn)
        if (Prefs.fanManaged(ctx)) FanDaemon.restart(ctx)
    }.start()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Fan curve", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Smoothed Smart curve")
                    Text(
                        "Drives fan from board soak (xo-therm) when Smart is active; Quiet/Sport/Off go to Odin",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = managed, onCheckedChange = {
                    managed = it
                    Prefs.setFanManaged(ctx, it)
                    Thread {
                        if (it) FanDaemon.start(ctx, forceTakeover = true) else FanDaemon.stop(ctx)
                        KeeperService.sync(ctx.applicationContext)
                    }.start()
                })
            }
            live?.let {
                val status = when {
                    it.yieldedMode != null -> "Yielded to Odin (${FanDaemon.modeName(it.yieldedMode)})"
                    !it.running -> "Daemon not running"
                    else -> buildString {
                        append("Soak %.1f °C · Junct %.1f °C → %d%% · %d rpm".format(it.soakC, it.junctC, it.pct, it.rpm))
                        if (it.zeroStopped) append(" · 0% battery")
                        if (it.guarded) append(" · GUARD")
                    }
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            points.forEachIndexed { i, p ->
                Text("Point ${i + 1}", style = MaterialTheme.typography.labelLarge)
                NumberSetting("Board temp (xo-therm)", p.temp, 30f..80f, 1f, 0, "°C") {
                    points[i] = p.copy(temp = it); dirty = true
                }
                NumberSetting("Speed", p.pct, 0f..100f, 1f, 0, "%") {
                    points[i] = p.copy(pct = it); dirty = true
                }
            }
            Text("Smoothing", style = MaterialTheme.typography.labelLarge)
            NumberSetting("Temperature averaging", tau, 1f..60f, 1f, 0, "s", slider = false) { tau = it; dirty = true }
            NumberSetting("Hysteresis", hyst, 0f..10f, 0.5f, 1, "°C", slider = false) { hyst = it; dirty = true }
            NumberSetting("Ramp up", up, 0.5f..20f, 0.5f, 1, "%/s", slider = false) { up = it; dirty = true }
            NumberSetting("Ramp down", down, 0.5f..20f, 0.5f, 1, "%/s", slider = false) { down = it; dirty = true }
            Text("Junction guard", style = MaterialTheme.typography.labelLarge)
            NumberSetting("Override temp", guardC, 80f..105f, 1f, 0, "°C", slider = false) { guardC = it; dirty = true }
            NumberSetting("Hold time", guardS, 0f..60f, 1f, 0, "s", slider = false) { guardS = it; dirty = true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("0% fan on battery when cool")
                    Text("Stops fan off charger below soak threshold; resumes immediately on plug-in or load", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = zeroBat, onCheckedChange = {
                    zeroBat = it
                    Prefs.setFanZeroBattery(ctx, it)
                    if (Prefs.fanManaged(ctx)) Thread { FanDaemon.restart(ctx) }.start()
                })
            }
            if (zeroBat) {
                NumberSetting("Stop below (soak)", zeroOff, 32f..55f, 1f, 0, "°C", slider = false) {
                    zeroOff = it
                    if (zeroOn <= it + 2f) zeroOn = it + 4f
                    dirty = true
                }
                NumberSetting("Resume above (soak)", zeroOn, 36f..60f, 1f, 0, "°C", slider = false) {
                    zeroOn = it.coerceAtLeast(zeroOff + 2f)
                    dirty = true
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = dirty, onClick = { apply(); dirty = false }) { Text("Apply") }
                OutlinedButton(onClick = {
                    points.clear(); points.addAll(parse(Prefs.FAN_CURVE_DEFAULT))
                    tau = Prefs.FAN_TAU_DEFAULT
                    hyst = Prefs.FAN_HYST_DEFAULT
                    up = Prefs.FAN_SLEW_UP_DEFAULT
                    down = Prefs.FAN_SLEW_DOWN_DEFAULT
                    guardC = Prefs.FAN_GUARD_C_DEFAULT
                    guardS = Prefs.FAN_GUARD_S_DEFAULT.toFloat()
                    zeroOff = Prefs.FAN_ZERO_OFF_C_DEFAULT
                    zeroOn = Prefs.FAN_ZERO_ON_C_DEFAULT
                    dirty = true
                }) { Text("Defaults") }
            }
        }
    }
}
