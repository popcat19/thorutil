package dev.popcat19.thorutil

import android.content.Context
import java.io.File

// Shared lifecycle helper for native daemons (libthormouse, libthorfan, libthorperf).
// Serializes start/stop/restart per daemon so concurrent callers (BootReceiver, ThorApp,
// KeeperService, UI) never race or delete a live daemon's state file.
class NativeDaemon(
    private val libName: String,
    val statePath: String,
    private val logPath: String,
    private val suspendPath: String,
    private val pidField: Int,
    private val wakeSignal: String? = null,
) {
    val lock = Any()

    fun binary(ctx: Context): String =
        File(ctx.applicationInfo.nativeLibraryDir, libName).absolutePath

    fun pidExpr(): String = "\$(cut -d' ' -f$pidField $statePath 2>/dev/null)"

    fun readStateLine(extraCmd: String = ""): String? {
        val tail = if (extraCmd.isEmpty()) "cat $statePath" else "cat $statePath; $extraCmd"
        val r = Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] && { $tail; }")
        return if (r.code == 0) r.out else null
    }

    fun isRunning(): Boolean =
        Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]").code == 0

    // Signal via active backend, falling back to root only if the daemon is alive and
    // shell Shizuku was denied (e.g. a root-started daemon while Shizuku runs as shell).
    fun signal(sig: String): Boolean {
        val check = "p=${pidExpr()}; if [ -z \"\$p\" ] || [ ! -d \"/proc/\$p\" ]; then echo gone; " +
            "else kill $sig \$p 2>/dev/null && echo ok || echo deny; fi"
        val out = Priv.sh(check).out.trim()
        if (out == "ok") return true
        if (out != "deny" || Priv.shizukuIsRoot()) return false
        val cmd = "p=${pidExpr()}; [ -n \"\$p\" ] && kill $sig \$p 2>/dev/null"
        return Priv.rootReady() && Priv.shRoot(cmd).code == 0
    }

    fun launch(
        ctx: Context,
        args: String = "",
        preCmd: String = "",
        suspended: Boolean? = null,
    ): Boolean = synchronized(lock) {
        if (isRunning()) {
            if (suspended != null) setSuspendFile(suspended)
            return true
        }
        val suspCmd = when (suspended) {
            true -> "touch $suspendPath; chmod 666 $suspendPath; "
            false -> "rm -f $suspendPath; "
            null -> ""
        }
        val extraPre = if (preCmd.isEmpty()) "" else "$preCmd; "
        val argStr = if (args.isEmpty()) "" else " $args"
        // Never unlink statePath before launch; the daemon overwrites it atomically after flock.
        val cmd = "rm -f $logPath; $extraPre${suspCmd}setsid ${binary(ctx)}$argStr </dev/null >$logPath 2>&1 & " +
            "sleep 0.3; p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]"
        Priv.sh(cmd).code == 0
    }

    fun setSuspendFile(want: Boolean): Boolean {
        val ok = Priv.sh(if (want) "touch $suspendPath; chmod 666 $suspendPath" else "rm -f $suspendPath").code == 0
        if (ok && wakeSignal != null) signal(wakeSignal)
        return ok
    }

    // Returns true only when the suspend file actually changed state.
    fun setSuspendFileIfChanged(want: Boolean): Boolean {
        val change = if (want) {
            "[ -e $suspendPath ] || { touch $suspendPath; chmod 666 $suspendPath; echo y; }"
        } else {
            "[ -e $suspendPath ] && { rm -f $suspendPath; echo y; }"
        }
        if (Priv.sh("$change; true").out.trim() != "y") return false
        if (wakeSignal != null) signal(wakeSignal)
        return true
    }

    fun kill(): Boolean = synchronized(lock) {
        signal("-TERM")
        true
    }

    fun waitStopped(maxWaitMs: Long = 1500L) {
        val steps = (maxWaitMs / 100L).toInt().coerceAtLeast(1)
        repeat(steps) {
            if (!isRunning()) return
            Thread.sleep(100)
        }
    }
}
