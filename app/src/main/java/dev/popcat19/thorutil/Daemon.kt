package dev.popcat19.thorutil

import android.content.Context
import java.io.File

// Controls the native thor-mouse daemon. The binary ships as libthormouse.so so the
// package manager extracts it executable into nativeLibraryDir.
object Daemon {
    const val STATE = "/data/local/tmp/thorutil-mouse.state"
    private const val LOG = "/data/local/tmp/thorutil-mouse.log"

    data class State(val running: Boolean, val active: Boolean)

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libthormouse.so").absolutePath

    private fun pidExpr() = "\$(cut -d' ' -f2 $STATE 2>/dev/null)"

    fun state(): State {
        val r = Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null && cut -d' ' -f1 $STATE")
        if (r.code != 0) return State(false, false)
        return State(true, r.out.trim() == "1")
    }

    fun start(ctx: Context): Boolean {
        val args = "-d ${Prefs.deadzone(ctx)} -p ${Prefs.pointerSpeed(ctx)} -s ${Prefs.scrollSpeed(ctx)}"
        val cmd = "setsid ${binary(ctx)} $args </dev/null >$LOG 2>&1 & sleep 0.3; " +
            "kill -0 ${pidExpr()} 2>/dev/null"
        return Priv.sh(cmd).code == 0
    }

    fun stop(): Boolean = Priv.sh("kill ${pidExpr()} 2>/dev/null; true").code == 0

    fun toggle(): Boolean = Priv.sh("kill -USR1 ${pidExpr()}").code == 0

    fun restart(ctx: Context): Boolean {
        stop()
        Thread.sleep(300)
        return start(ctx)
    }
}
