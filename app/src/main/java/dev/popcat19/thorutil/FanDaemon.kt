package dev.popcat19.thorutil

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File

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
    private const val TAG = "ThorUtil/Fan"

    const val MODE_KEY = "fan_mode"
    const val SPEED_KEY = "fan_speed"
    // OdinSettings s1.b$d.k(6) returns fan_speed unchanged when < 0, and s1.b$c.r() skips the
    // duty write when <= -1, preventing 67% duty jumps on wake/app-switch while keeping state=1.
    private const val SPEED_NOOP = -1
    const val MODE_OFF = 0
    const val MODE_QUIET = 1
    const val MODE_SMART = 4
    const val MODE_SPORT = 5
    const val MODE_CUSTOM = 6

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

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libthorfan.so").absolutePath

    private fun pidExpr() = "\$(cut -d' ' -f1 $STATE 2>/dev/null)"

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

    fun state(): State {
        val y = yieldedMode
        // /proc/$p works from shell even when the daemon was started by root at boot.
        val r = Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] && cat $STATE")
        if (r.code != 0) return State(false, 0, 0.0, 0, yieldedMode = y)
        val f = r.out.trim().split(" ")
        if (f.size < 4) return State(false, 0, 0.0, 0, yieldedMode = y)
        return State(
            running = true,
            pct = f[1].toIntOrNull() ?: 0,
            soakC = f[2].toDoubleOrNull() ?: 0.0,
            rpm = f[3].toLongOrNull() ?: 0,
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

    private fun syncSuspend(): Boolean {
        val want = screenOff || yieldedMode != null
        return Priv.sh(if (want) "touch $SUSPEND; chmod 666 $SUSPEND" else "rm -f $SUSPEND").code == 0
    }

    // Start the daemon, respecting the current Odin fan_mode: if the user is on Smart or Custom
    // we take over; if they're on Quiet/Sport/Off we start suspended and yield until Smart.
    fun start(ctx: Context, forceTakeover: Boolean = false): Boolean {
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
        if (state().running) return syncSuspend()
        val curve = Prefs.fanCurve(ctx)
        val zeroArgs = if (Prefs.fanZeroBattery(ctx)) {
            " -z ${Prefs.fanZeroOffC(ctx)} -Z ${Prefs.fanZeroOnC(ctx)}"
        } else ""
        val args = "-c $curve -t ${Prefs.fanTau(ctx)} -h ${Prefs.fanHyst(ctx)} " +
            "-u ${Prefs.fanSlewUp(ctx)} -D ${Prefs.fanSlewDown(ctx)} " +
            "-g ${Prefs.fanGuardC(ctx)} -G ${Prefs.fanGuardS(ctx)}$zeroArgs"
        val suspendCmd = if (screenOff || yieldedMode != null) "touch $SUSPEND; chmod 666 $SUSPEND;" else "rm -f $SUSPEND;"
        val cmd = "rm -f $STATE $LOG; $suspendCmd setsid ${binary(ctx)} $args </dev/null >$LOG 2>&1 & sleep 0.3; " +
            "p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]"
        return Priv.sh(cmd).code == 0
    }

    // Hands the fan back to Odin in Smart (or the mode saved before takeover).
    fun stop(ctx: Context): Boolean {
        stopped = true
        val ok = kill()
        Priv.sh("rm -f $SUSPEND")
        restoreOdinSpeed(ctx)
        if (currentMode(ctx) == MODE_CUSTOM) writeMode(Prefs.savedFanMode(ctx))
        yieldedMode = null
        return ok
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
                if (!state().running) start(ctx, forceTakeover = true) else syncSuspend()
            }
            MODE_CUSTOM -> {
                // Already in our mode; keep current state.
            }
            else -> {
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
            kill()
            restoreOdinSpeed(ctx)
            writeMode(Prefs.savedFanMode(ctx))
        }
    }

    private fun kill(): Boolean {
        val cmd = "p=${pidExpr()}; [ -n \"\$p\" ] && kill \$p 2>/dev/null"
        if (Priv.sh(cmd).code != 0 && Priv.rootReady()) Priv.shRoot(cmd)
        return true
    }

    fun restart(ctx: Context): Boolean {
        kill()
        repeat(15) {
            if (!state().running) return@repeat
            Thread.sleep(100)
        }
        return start(ctx, forceTakeover = false)
    }
}
