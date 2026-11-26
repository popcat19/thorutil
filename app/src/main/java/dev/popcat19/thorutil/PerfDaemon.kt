package dev.popcat19.thorutil

import android.content.Context
import android.provider.Settings
import android.util.Log

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

    private val proc = NativeDaemon(
        libName = "libthorperf.so",
        statePath = STATE,
        logPath = LOG,
        suspendPath = SUSPEND,
        pidField = 1,
        wakeSignal = "-USR1",
    )

    data class State(
        val running: Boolean,
        val mode: Int,
        val cpuPct: Int,
        val corePct: Int,
        val gpuPct: Int,
    )

    fun binary(ctx: Context) = proc.binary(ctx)

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
        val out = proc.readStateLine() ?: return State(false, 0, 0, 0, 0)
        val f = out.trim().split(" ")
        if (f.size < 5) return State(false, 0, 0, 0, 0)
        return State(
            running = true,
            mode = f[1].toIntOrNull() ?: 0,
            cpuPct = f[2].toIntOrNull() ?: 0,
            corePct = f[3].toIntOrNull() ?: 0,
            gpuPct = f[4].toIntOrNull() ?: 0,
        )
    }

    private fun syncSuspend(): Boolean = proc.setSuspendFile(screenOff)

    fun start(ctx: Context): Boolean = synchronized(proc.lock) {
        stopped = false
        if (!proc.isRunning()) Prefs.setSavedPerfMode(ctx, currentMode(ctx))
        proc.launch(ctx, suspended = screenOff)
    }

    fun stop(ctx: Context): Boolean = synchronized(proc.lock) {
        stopped = true
        val ok = proc.kill()
        Priv.sh("rm -f $SUSPEND")
        val saved = Prefs.savedPerfMode(ctx)
        Priv.sh("settings put system $MODE_KEY $saved; setprop persist.vendor.debug.mode $saved 2>/dev/null")
        ok
    }

    fun setScreenOff(off: Boolean): Boolean {
        screenOff = off
        return syncSuspend()
    }

    fun onShutdown(ctx: Context) {
        if (!Prefs.perfAdaptive(ctx)) return
        val saved = Prefs.savedPerfMode(ctx)
        Log.i(TAG, "shutdown: restoring performance_mode=$saved")
        proc.kill()
        Priv.sh("settings put system $MODE_KEY $saved; setprop persist.vendor.debug.mode $saved 2>/dev/null")
    }

    fun restart(ctx: Context): Boolean = synchronized(proc.lock) {
        proc.kill()
        proc.waitStopped()
        start(ctx)
    }
}
