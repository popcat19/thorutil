package dev.popcat19.thorutil.ayn

import android.content.Context
import android.util.Log
import dev.popcat19.thorutil.Daemon
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import android.os.SystemClock

// Shell-side actions. screen_focus_lock steers gamepad keys only (Odin Settings maps it
// to persist.sys.input.dispaly for inputflinger). Touch never moves pad focus, but the
// last touched screen is the target for previous app when it is newer than the pad focus.
object AynActions {
    private const val TAG = "ThorUtil/Ayn"
    const val TOP = 0
    const val BOTTOM = 4
    private const val FOCUS_LOCK = "screen_focus_lock"
    private const val TOUCH = "/data/local/tmp/thorutil-touch"

    @Volatile
    private var focusAt = 0L

    private fun name(d: Int) = if (d == BOTTOM) "bottom" else "top"

    fun tap(ctx: Context) {
        Priv.sh("${Daemon.binary(ctx)} -k")
    }

    fun run(ctx: Context, a: AynAction) {
        Log.i(TAG, "run $a")
        when (a) {
            AynAction.FOCUS_TOP -> focus(ctx, TOP)
            AynAction.FOCUS_BOTTOM -> focus(ctx, BOTTOM)
            AynAction.FOCUS_SWAP -> focus(ctx, if (padDisplay() == BOTTOM) TOP else BOTTOM)
            AynAction.SWAP_WINDOWS -> {
                swapWindows()
                Feedback.show(ctx, FeedbackType.WINDOWS, "Windows swapped", false, padDisplay())
            }
            AynAction.PREV_APP -> {
                val d = targetDisplay()
                if (prevApp(d)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", false, d)
            }
            AynAction.MOUSE -> Daemon.toggle()
        }
    }

    // Gamepad focus moves the emu pointer too, so stick and buttons stay on one screen.
    fun focus(ctx: Context, display: Int) {
        Priv.sh("settings put system $FOCUS_LOCK ${if (display == BOTTOM) 2 else 1}")
        focusAt = SystemClock.uptimeMillis()
        Daemon.pointer(display == BOTTOM)
        Feedback.show(ctx, FeedbackType.FOCUS, "Focus: ${name(display)}", display == TOP, display)
    }

    // Last touched screen if touched after the last focus action, else pad focus.
    fun targetDisplay(): Int {
        val p = Priv.sh("cat $TOUCH 2>/dev/null").out.trim().split(' ')
        val at = p.getOrNull(1)?.toLongOrNull() ?: 0L
        val d = p.getOrNull(0)?.toIntOrNull()
        if (d != null && at > focusAt) return if (d == BOTTOM) BOTTOM else TOP
        return padDisplay()
    }

    fun padDisplay(): Int {
        val lock = Priv.sh("settings get system $FOCUS_LOCK").out.trim()
        if (lock == "1") return TOP
        if (lock == "2") return BOTTOM
        val top = Priv.sh("dumpsys window displays | grep -m1 mTopFocusedDisplayId").out
        return if (top.trim().endsWith("=$BOTTOM")) BOTTOM else TOP
    }

    data class Task(val rootId: Int, val display: Int, val component: String)

    // Standard (non-home) root tasks in z-order, top first, per `am stack list`.
    fun tasks(): List<Task> {
        val out = Priv.sh("am stack list").out
        val res = ArrayList<Task>()
        var root = -1
        var display = -1
        var standard = false
        val head = Regex("^RootTask id=(\\d+) .*displayId=(\\d+)")
        val task = Regex("^\\s+taskId=\\d+: (\\S+/\\S+) ")
        for (line in out.lines()) {
            head.find(line)?.let {
                root = it.groupValues[1].toInt()
                display = it.groupValues[2].toInt()
                standard = false
                return@let
            }
            if (line.contains("mActivityType=")) standard = line.contains("mActivityType=standard ")
            val t = task.find(line) ?: continue
            if (standard && root >= 0 && res.none { it.rootId == root }) res.add(Task(root, display, t.groupValues[1]))
        }
        return res
    }

    fun swapWindows() {
        val all = tasks()
        val top = all.firstOrNull { it.display == TOP }
        val bottom = all.firstOrNull { it.display == BOTTOM }
        if (top != null) Priv.sh("am display move-stack ${top.rootId} $BOTTOM")
        if (bottom != null) Priv.sh("am display move-stack ${bottom.rootId} $TOP")
    }

    fun prevApp(display: Int): Boolean {
        val prev = tasks().filter { it.display == display }.getOrNull(1) ?: return false
        val comp = prev.component.filter { it.isLetterOrDigit() || it in "._/$" }
        return Priv.sh("am start --display $display -f 0x10200000 -n '$comp'").code == 0
    }
}
