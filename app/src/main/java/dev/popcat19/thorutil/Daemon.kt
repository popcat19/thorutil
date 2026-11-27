package dev.popcat19.thorutil

import android.content.Context

// Controls the native thor-mouse daemon. The binary ships as libthormouse.so so the
// package manager extracts it executable into nativeLibraryDir.
object Daemon {
    const val STATE = "/data/local/tmp/thorutil-mouse.state"
    private const val LOG = "/data/local/tmp/thorutil-mouse.log"
    private const val SUSPEND = "/data/local/tmp/thorutil-mouse.suspend"
    private const val CMD = "/data/local/tmp/thorutil-mouse.cmd"
    private const val TAP = "/data/local/tmp/thorutil-mouse.tap"
    private const val ROTATION = "/data/local/tmp/thorutil-rotation"
    private const val TOUCH = "/data/local/tmp/thorutil-touch"

    private val proc = NativeDaemon(
        libName = "libthormouse.so",
        statePath = STATE,
        logPath = LOG,
        suspendPath = SUSPEND,
        pidField = 2,
        wakeSignal = "-USR2",
    )

    data class State(val running: Boolean, val active: Boolean)

    fun binary(ctx: Context) = proc.binary(ctx)

    fun state(): State {
        val line = proc.readStateLine() ?: return State(false, false)
        return State(true, line.trim().startsWith("1 "))
    }

    // Set by a manual stop so the keeper watchdog leaves the daemon down until the next start.
    @Volatile
    var stopped = false
        private set

    fun start(ctx: Context): Boolean = synchronized(proc.lock) {
        stopped = false
        val dpad = if (Prefs.mouseRotateDpad(ctx)) " -r" else ""
        val touch = if (Prefs.mouseIgnoreTouchFocus(ctx)) " -t" else ""
        val idle = if (Prefs.mouseIdleHide(ctx)) " -i ${Prefs.mouseIdleHideSec(ctx)}" else ""
        val args = "-d ${Prefs.deadzone(ctx)} -p ${Prefs.pointerSpeed(ctx)} -s ${Prefs.scrollSpeed(ctx)}$dpad$touch$idle"
        // Remove stale root-owned touch file before launch so shell-run daemon can overwrite it.
        proc.launch(ctx, args = args, preCmd = "rm -f $TOUCH")
    }

    fun stop(): Boolean = synchronized(proc.lock) {
        stopped = true
        proc.kill()
    }

    fun toggle(): Boolean = proc.signal("-USR1")

    // Fast path: ask the running daemon to pulse its persistent AYN uinput node (<10 ms);
    // falls back to spawning `-k` when the daemon is stopped.
    fun tap(ctx: Context): Boolean {
        if (Priv.sh("touch $TAP && chmod 666 $TAP").code == 0 && proc.signal("-USR2")) return true
        return Priv.sh("${binary(ctx)} -k").code == 0
    }

    // Sets focus.current in the daemon; the pointer follows only while mouse mode is on.
    fun pointer(bottom: Boolean): Boolean {
        if (Priv.sh("echo ${if (bottom) "bottom" else "top"} > $CMD; chmod 666 $CMD").code != 0) return false
        return proc.signal("-USR2")
    }

    // Display rotations (Surface.ROTATION_*) so stick motion follows the held orientation.
    // Written even when the daemon is down; it reads the file at start.
    fun rotation(top: Int, bottom: Int): Boolean {
        val w = Priv.sh("echo $top $bottom > $ROTATION; chmod 666 $ROTATION")
        if (w.code != 0) return false
        proc.signal("-USR2")
        return true
    }

    // Returns true only when the suspend state actually changed.
    fun setSuspended(on: Boolean): Boolean = proc.setSuspendFileIfChanged(on)

    fun restart(ctx: Context): Boolean = synchronized(proc.lock) {
        proc.kill()
        proc.waitStopped()
        start(ctx)
    }
}
