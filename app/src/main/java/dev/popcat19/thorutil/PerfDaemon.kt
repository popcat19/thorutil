package dev.popcat19.thorutil

import android.content.Context
import android.provider.Settings
import android.util.Log
import java.io.File

// Controls the native thor-perf daemon (libthorperf.so): auto-cpufreq style adaptive Odin
// performance mode scaling. Raises performance_mode rapidly under CPU/single-core/GPU load
// and steps back down after sustained idle, saving battery without throttling peak clocks.
object PerfDaemon {
    const val STATE = "/data/local/tmp/thorutil-perf.state"
    private const val SUSPEND = "/data/local/tmp/thorutil-perf.suspend"
    private const val CHANGE = "/data/local/tmp/thorutil-perf.change"
    private const val LOG = "/data/local/tmp/thorutil-perf.log"
    private const val TAG = "ThorUtil/Perf"
    const val MODE_KEY = "performance_mode"

    data class State(
        val running: Boolean,
        val mode: Int,
        val cpuPct: Int,
        val corePct: Int,
        val gpuPct: Int,
    )

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libthorperf.so").absolutePath

    private fun pidExpr() = "\$(cut -d' ' -f1 $STATE 2>/dev/null)"

    @Volatile
    private var screenOff = false

    @Volatile
    var stopped = false
        private set

    fun modeName(mode: Int) = when (mode) {
        0 -> "Normal"
        1 -> "Performance"
        2 -> "High Performance"
        else -> "mode $mode"
    }

    fun currentMode(ctx: Context): Int =
        Settings.System.getInt(ctx.contentResolver, MODE_KEY, 0)

    // SystemUI's FanTile overwrites fan_mode whenever performance_mode changes.
    // Returns true if thor-perf wrote performance_mode within the last 3 seconds.
    fun recentlyChanged(): Boolean {
        val ts = Priv.sh("cat $CHANGE 2>/dev/null").out.trim().toLongOrNull() ?: return false
        val now = System.currentTimeMillis() / 1000L
        return kotlin.math.abs(now - ts) <= 3L
    }

    fun state(): State {
        val r = Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] && cat $STATE")
        if (r.code != 0) return State(false, 0, 0, 0, 0)
        val f = r.out.trim().split(" ")
        if (f.size < 5) return State(false, 0, 0, 0, 0)
        return State(
            running = true,
            mode = f[1].toIntOrNull() ?: 0,
            cpuPct = f[2].toIntOrNull() ?: 0,
            corePct = f[3].toIntOrNull() ?: 0,
            gpuPct = f[4].toIntOrNull() ?: 0,
        )
    }

    private fun syncSuspend(): Boolean =
        Priv.sh(if (screenOff) "touch $SUSPEND; chmod 666 $SUSPEND" else "rm -f $SUSPEND").code == 0

    fun start(ctx: Context): Boolean {
        stopped = false
        if (state().running) return syncSuspend()
        Prefs.setSavedPerfMode(ctx, currentMode(ctx))
        val suspendCmd = if (screenOff) "touch $SUSPEND; chmod 666 $SUSPEND;" else "rm -f $SUSPEND;"
        val cmd = "rm -f $STATE $LOG; $suspendCmd setsid ${binary(ctx)} </dev/null >$LOG 2>&1 & sleep 0.3; " +
            "p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]"
        return Priv.sh(cmd).code == 0
    }

    fun stop(ctx: Context): Boolean {
        stopped = true
        val ok = kill()
        Priv.sh("rm -f $SUSPEND")
        val saved = Prefs.savedPerfMode(ctx)
        Priv.sh("settings put system $MODE_KEY $saved; setprop persist.vendor.debug.mode $saved 2>/dev/null")
        return ok
    }

    fun setScreenOff(off: Boolean): Boolean {
        screenOff = off
        return syncSuspend()
    }

    fun onShutdown(ctx: Context) {
        if (!Prefs.perfAdaptive(ctx)) return
        val saved = Prefs.savedPerfMode(ctx)
        Log.i(TAG, "shutdown: restoring performance_mode=$saved")
        kill()
        Priv.sh("settings put system $MODE_KEY $saved; setprop persist.vendor.debug.mode $saved 2>/dev/null")
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
        return start(ctx)
    }
}
