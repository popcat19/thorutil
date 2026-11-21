package dev.popcat19.thorutil

import android.content.Context
import java.io.File

// Controls the native thor-mouse daemon. The binary ships as libthormouse.so so the
// package manager extracts it executable into nativeLibraryDir.
object Daemon {
    const val STATE = "/data/local/tmp/thorutil-mouse.state"
    private const val LOG = "/data/local/tmp/thorutil-mouse.log"
    private const val SUSPEND = "/data/local/tmp/thorutil-mouse.suspend"
    private const val CMD = "/data/local/tmp/thorutil-mouse.cmd"
    private const val ROTATION = "/data/local/tmp/thorutil-rotation"

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

    // Sets focus.current in the daemon; the pointer follows only while mouse mode is on.
    fun pointer(bottom: Boolean): Boolean =
        Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] || exit 1; echo ${if (bottom) "bottom" else "top"} > $CMD; chmod 666 $CMD; kill -USR2 \$p").code == 0

    // Display rotations (Surface.ROTATION_*) so stick motion follows the held orientation.
    // Written even when the daemon is down; it reads the file at start.
    fun rotation(top: Int, bottom: Int): Boolean =
        Priv.sh("echo $top $bottom > $ROTATION; chmod 666 $ROTATION; p=${pidExpr()}; [ -n \"\$p\" ] && kill -USR2 \$p; true").code == 0

    // Returns true only when the suspend state actually changed.
    fun setSuspended(on: Boolean): Boolean {
        val change = if (on) "[ -e $SUSPEND ] || { touch $SUSPEND; echo y; }" else "[ -e $SUSPEND ] && { rm -f $SUSPEND; echo y; }"
        if (Priv.sh("$change; true").out.trim() != "y") return false
        Priv.sh("kill -USR2 ${pidExpr()} 2>/dev/null; true")
        return true
    }

    fun restart(ctx: Context): Boolean {
        stop()
        Thread.sleep(300)
        return start(ctx)
    }
}
