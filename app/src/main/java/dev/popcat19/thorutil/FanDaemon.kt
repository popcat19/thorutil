package dev.popcat19.thorutil

import android.content.Context
import android.provider.Settings
import android.util.Log

// Controls the native thor-fan daemon. The binary ships as libthorfan.so so the package
// manager extracts it executable into nativeLibraryDir.
//
// Profile model ("Smart = ThorUtil curve"):
// - When fan_managed is enabled and Odin's fan_mode is Smart (4), ThorUtil switches Odin to
//   Custom (6) so the vendor loop leaves gpio5_pwm2/duty alone, and runs the daemon.
// - If the user picks Quiet (1), Sport (5), Off (0), or any non-Smart/non-Custom mode via the
//   QS tile or Odin Settings, ThorUtil suspends the daemon so Odin drives that profile.
// - Picking Smart (4) again hands control back to ThorUtil.
// - On shutdown, ThorUtil restores Smart (4) if it owns Custom (6), so the next boot starts on
//   stock Smart instead of sitting at Custom's default 100% duty until the daemon starts.
object FanDaemon {
    const val STATE = "/data/local/tmp/thorutil-fan.state"
    private const val SUSPEND = "/data/local/tmp/thorutil-fan.suspend"
    private const val LOG = "/data/local/tmp/thorutil-fan.log"
    private const val SPEED_PATH = "/sys/class/gpio5_pwm2/speed"
    private const val TAG = "ThorUtil/Fan"

    const val MODE_KEY = "fan_mode"
    const val SPEED_KEY = "fan_speed"
    const val MODE_OFF = 0
    const val MODE_QUIET = 1
    const val MODE_SMART = 4
    const val MODE_SPORT = 5
    const val MODE_CUSTOM = 6
    // Odin's FanTrigger writes `duty = fan_speed * 500` when fan_speed >= 0 in Custom mode.
    // Setting fan_speed = -1 skips that write while keeping Smart/Quiet/Sport untouched.
    private const val SPEED_NOOP = -1

    private val proc = NativeDaemon(
        libName = "libthorfan.so",
        statePath = STATE,
        logPath = LOG,
        suspendPath = SUSPEND,
        pidField = 1,
        wakeSignal = "-USR1",
    )

    data class State(
        val running: Boolean,
        val pct: Int,
        val soakC: Double,
        val rpm: Long,
        val junctC: Double = 0.0,
        val guarded: Boolean = false,
        val zeroStopped: Boolean = false,
        val yieldedMode: Int? = null,
    )

    fun binary(ctx: Context) = proc.binary(ctx)

    // Tracks which reasons currently pause the daemon so screen-off and profile-yield don't
    // clobber each other.
    @Volatile
    private var screenOff = false

    @Volatile
    private var yieldedMode: Int? = null

    @Volatile
    private var ownModeWriteAt = 0L

    @Volatile
    var stopped = false
        private set

    fun modeName(mode: Int) = when (mode) {
        MODE_OFF -> "Off"
        MODE_QUIET -> "Quiet"
        MODE_SMART -> "Smart"
        MODE_SPORT -> "Sport"
        MODE_CUSTOM -> "Custom"
        else -> "mode $mode"
    }

    fun currentMode(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, MODE_KEY, MODE_SMART)

    private fun currentSpeed(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, SPEED_KEY, 35)

    private fun suppressOdinDuty(ctx: Context) {
        val cur = currentSpeed(ctx)
        if (cur == SPEED_NOOP) return
        if (cur >= 0) Prefs.setSavedFanSpeed(ctx, cur)
        Priv.sh("settings put system $SPEED_KEY $SPEED_NOOP")
    }

    private fun restoreOdinSpeed(ctx: Context) {
        if (currentSpeed(ctx) == SPEED_NOOP) {
            Priv.sh("settings put system $SPEED_KEY ${Prefs.savedFanSpeed(ctx)}")
        }
    }

    // RPM is only queried when includeRpm=true (UI open), because reading gpio5_pwm2/speed
    // busy-spins ~100ms in the kernel driver.
    fun state(includeRpm: Boolean = false): State {
        val y = yieldedMode
        val extra = if (includeRpm) "cat $SPEED_PATH 2>/dev/null || echo 0" else ""
        val out = proc.readStateLine(extra) ?: return State(false, 0, 0.0, 0, yieldedMode = y)
        val lines = out.lines()
        val f = lines.firstOrNull()?.trim()?.split(" ") ?: return State(false, 0, 0.0, 0, yieldedMode = y)
        if (f.size < 4) return State(false, 0, 0.0, 0, yieldedMode = y)
        val rpm = if (includeRpm) lines.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
        else f[3].toLongOrNull() ?: 0L
        return State(
            running = true,
            pct = f[1].toIntOrNull() ?: 0,
            soakC = f[2].toDoubleOrNull() ?: 0.0,
            rpm = rpm,
            junctC = f.getOrNull(4)?.toDoubleOrNull() ?: 0.0,
            guarded = f.getOrNull(5) == "1",
            zeroStopped = f.getOrNull(6) == "1",
            yieldedMode = y,
        )
    }

    private fun writeMode(mode: Int) {
        ownModeWriteAt = System.currentTimeMillis()
        Priv.sh("settings put system $MODE_KEY $mode")
    }

    private fun syncSuspend(): Boolean = proc.setSuspendFile(screenOff || yieldedMode != null)

    // Start the daemon, respecting the current Odin fan_mode: if the user is on Smart or Custom
    // we take over; if they're on Quiet/Sport/Off we start suspended and yield until Smart.
    fun start(ctx: Context, forceTakeover: Boolean = false): Boolean = synchronized(proc.lock) {
        stopped = false
        val mode = currentMode(ctx)
        if (forceTakeover || mode == MODE_SMART || mode == MODE_CUSTOM) {
            yieldedMode = null
            // Suppress Odin custom duty before switching to Custom so FanTrigger doesn't spike duty.
            suppressOdinDuty(ctx)
            if (mode != MODE_CUSTOM) {
                Prefs.setSavedFanMode(ctx, mode)
                writeMode(MODE_CUSTOM)
            }
        } else {
            yieldedMode = mode
            Log.i(TAG, "start yielded to Odin ${modeName(mode)}")
        }
        val curve = Prefs.fanCurve(ctx)
        val zeroArgs = if (Prefs.fanZeroBattery(ctx)) {
            " -z ${Prefs.fanZeroOffC(ctx)} -Z ${Prefs.fanZeroOnC(ctx)}"
        } else ""
        val args = "-c $curve -t ${Prefs.fanTau(ctx)} -h ${Prefs.fanHyst(ctx)} " +
            "-u ${Prefs.fanSlewUp(ctx)} -D ${Prefs.fanSlewDown(ctx)} " +
            "-g ${Prefs.fanGuardC(ctx)} -G ${Prefs.fanGuardS(ctx)}$zeroArgs"
        proc.launch(ctx, args = args, suspended = screenOff || yieldedMode != null)
    }

    // Hands the fan back to Odin in Smart (or the mode saved before takeover).
    fun stop(ctx: Context): Boolean = synchronized(proc.lock) {
        stopped = true
        val ok = proc.kill()
        Priv.sh("rm -f $SUSPEND")
        restoreOdinSpeed(ctx)
        if (currentMode(ctx) == MODE_CUSTOM) writeMode(Prefs.savedFanMode(ctx))
        yieldedMode = null
        ok
    }

    // React to external changes to system fan_mode (QS tile, Odin Settings, boot).
    fun onModeChanged(ctx: Context) {
        if (!Prefs.fanManaged(ctx) || stopped) return
        // Ignore the observer bounce from our own settings write.
        if (System.currentTimeMillis() - ownModeWriteAt < 1500) return
        val mode = currentMode(ctx)
        when (mode) {
            MODE_SMART -> {
                Log.i(TAG, "profile -> Smart: taking over")
                yieldedMode = null
                Prefs.setSavedFanMode(ctx, MODE_SMART)
                suppressOdinDuty(ctx)
                writeMode(MODE_CUSTOM)
                if (!proc.isRunning()) start(ctx, forceTakeover = true) else syncSuspend()
            }
            MODE_CUSTOM -> {
                // Already in our mode; keep current state.
            }
            else -> {
                if (yieldedMode == null && Prefs.perfAdaptive(ctx) && PerfDaemon.recentlyChanged()) {
                    Log.i(TAG, "ignoring FanTile bounce (${modeName(mode)}) after adaptive perf switch; restoring Custom")
                    suppressOdinDuty(ctx)
                    writeMode(MODE_CUSTOM)
                    return
                }
                Log.i(TAG, "profile -> ${modeName(mode)}: yielding to Odin")
                yieldedMode = mode
                syncSuspend()
            }
        }
    }

    // Odin drives the fan while the screen is off (off, or its own mode while charging).
    fun setScreenOff(off: Boolean): Boolean {
        screenOff = off
        return syncSuspend()
    }

    // Restores Smart before power-off so the next boot doesn't leave Odin in Custom (100% duty).
    fun onShutdown(ctx: Context) {
        if (!Prefs.fanManaged(ctx)) return
        if (currentMode(ctx) == MODE_CUSTOM) {
            Log.i(TAG, "shutdown: restoring fan_mode=${Prefs.savedFanMode(ctx)}")
            proc.kill()
            restoreOdinSpeed(ctx)
            writeMode(Prefs.savedFanMode(ctx))
        }
    }

    fun restart(ctx: Context): Boolean = synchronized(proc.lock) {
        proc.kill()
        proc.waitStopped()
        start(ctx, forceTakeover = false)
    }
}
