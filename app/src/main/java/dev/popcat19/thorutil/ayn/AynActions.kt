package dev.popcat19.thorutil.ayn

import android.content.Context
import android.util.Log
import dev.popcat19.thorutil.Daemon
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import dev.popcat19.thorutil.brightness.BrightnessLink
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

    fun back(ctx: Context) {
        val d = targetDisplay()
        Log.i(TAG, "back -> $d")
        Priv.sh("input -d $d keyevent BACK")
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
            AynAction.PREV_APP_TOP -> {
                if (prevApp(TOP)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", true, TOP)
            }
            AynAction.PREV_APP_BOTTOM -> {
                if (prevApp(BOTTOM)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", false, BOTTOM)
            }
            AynAction.RECENTS_TOGGLE -> {
                // APP_SWITCH while recents is up lands on home instead of closing; back closes it.
                val visible = Priv.sh("dumpsys activity activities | grep -m1 'type=recents' | grep -c 'visible=true'").out.trim() == "1"
                if (visible) {
                    // Back from recents lands on home; reopen the app that was on top instead.
                    if (!resume(TOP)) Priv.sh("input keyevent BACK")
                    Feedback.show(ctx, FeedbackType.RECENTS, "Recents hidden", true, TOP)
                } else {
                    Priv.sh("input keyevent APP_SWITCH")
                    Feedback.show(ctx, FeedbackType.RECENTS, "Recents", true, TOP)
                }
            }
            AynAction.MOUSE -> Daemon.toggle()
            AynAction.BRIGHTNESS_UP, AynAction.BRIGHTNESS_DOWN -> {
                val pct = BrightnessLink.step(ctx, a == AynAction.BRIGHTNESS_UP)
                Feedback.show(ctx, FeedbackType.BRIGHTNESS, "Brightness $pct%", a == AynAction.BRIGHTNESS_UP, padDisplay())
            }
        }
    }

    // Null display = lone Home, sent where pad keys go. Pad focus is left alone.
    fun home(ctx: Context, display: Int?) {
        val d = display ?: padDisplay()
        Priv.sh("input -d $d keyevent HOME")
        if (display != null) Feedback.show(ctx, FeedbackType.HOME, "Home: ${name(d)}", d == TOP, d)
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

    private fun resume(display: Int): Boolean {
        val t = tasks().firstOrNull { it.display == display } ?: return false
        val comp = t.component.filter { it.isLetterOrDigit() || it in "._/$" }
        return Priv.sh("am start --display $display -f 0x10200000 -n '$comp'").code == 0
    }

    fun prevApp(display: Int): Boolean {
        val prev = tasks().filter { it.display == display }.getOrNull(1) ?: return false
        val comp = prev.component.filter { it.isLetterOrDigit() || it in "._/$" }
        return Priv.sh("am start --display $display -f 0x10200000 -n '$comp'").code == 0
    }
}
