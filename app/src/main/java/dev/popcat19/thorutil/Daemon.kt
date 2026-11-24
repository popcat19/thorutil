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
    private const val TOUCH = "/data/local/tmp/thorutil-touch"

    data class State(val running: Boolean, val active: Boolean)

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libthormouse.so").absolutePath

    private fun pidExpr() = "\$(cut -d' ' -f2 $STATE 2>/dev/null)"

    fun state(): State {
        // /proc/$p works from shell even when the daemon was started by root at boot.
        val r = Priv.sh("p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ] && cut -d' ' -f1 $STATE")
        if (r.code != 0) return State(false, false)
        return State(true, r.out.trim() == "1")
    }

    // Set by a manual stop so the keeper watchdog leaves the daemon down until the next start.
    @Volatile
    var stopped = false
        private set

    fun start(ctx: Context): Boolean {
        stopped = false
        if (state().running) return true
        val dpad = if (Prefs.mouseRotateDpad(ctx)) " -r" else ""
        val args = "-d ${Prefs.deadzone(ctx)} -p ${Prefs.pointerSpeed(ctx)} -s ${Prefs.scrollSpeed(ctx)}$dpad"
        // A root-run daemon (root Shizuku or su) leaves root-owned files a shell-run one cannot write.
        val cmd = "rm -f $LOG $TOUCH; setsid ${binary(ctx)} $args </dev/null >$LOG 2>&1 & sleep 0.3; " +
            "p=${pidExpr()}; [ -n \"\$p\" ] && [ -d \"/proc/\$p\" ]"
        return Priv.sh(cmd).code == 0
    }

    fun stop(): Boolean {
        stopped = true
        return kill()
    }

    // Signal via active backend, falling back to root if the daemon is uid 0 and Shizuku is shell.
    private fun signal(sig: String): Boolean {
        val cmd = "p=${pidExpr()}; [ -n \"\$p\" ] && kill $sig \$p 2>/dev/null"
        if (Priv.sh(cmd).code == 0) return true
        return Priv.rootReady() && Priv.shRoot(cmd).code == 0
    }

    private fun kill(): Boolean {
        signal("-TERM")
        return true
    }

    fun toggle(): Boolean = signal("-USR1")

    // Sets focus.current in the daemon; the pointer follows only while mouse mode is on.
    fun pointer(bottom: Boolean): Boolean {
        if (Priv.sh("echo ${if (bottom) "bottom" else "top"} > $CMD; chmod 666 $CMD").code != 0) return false
        return signal("-USR2")
    }

    // Display rotations (Surface.ROTATION_*) so stick motion follows the held orientation.
    // Written even when the daemon is down; it reads the file at start.
    fun rotation(top: Int, bottom: Int): Boolean {
        val w = Priv.sh("echo $top $bottom > $ROTATION; chmod 666 $ROTATION")
        if (w.code != 0) return false
        signal("-USR2")
        return true
    }

    // Returns true only when the suspend state actually changed.
    fun setSuspended(on: Boolean): Boolean {
        val change = if (on) "[ -e $SUSPEND ] || { touch $SUSPEND; echo y; }" else "[ -e $SUSPEND ] && { rm -f $SUSPEND; echo y; }"
        if (Priv.sh("$change; true").out.trim() != "y") return false
        signal("-USR2")
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
