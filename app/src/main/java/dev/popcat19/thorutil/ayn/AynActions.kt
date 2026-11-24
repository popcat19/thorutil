package dev.popcat19.thorutil.ayn

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.popcat19.thorutil.Daemon
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.brightness.BrightnessLink
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType

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

    @Volatile
    private var pointerBottom = false

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
            AynAction.FOCUS_SWAP -> {
                val cur = if (Prefs.aynFocusMovesPad(ctx)) padDisplay() else if (pointerBottom) BOTTOM else TOP
                focus(ctx, if (cur == BOTTOM) TOP else BOTTOM)
            }
            AynAction.SWAP_WINDOWS -> {
                swapWindows()
                Feedback.show(ctx, FeedbackType.WINDOWS, "Windows swapped", false, padDisplay())
            }
            AynAction.PREV_APP -> {
                val d = targetDisplay()
                if (prevApp(d)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", d == TOP, d)
            }
            AynAction.PREV_APP_TOP -> {
                if (prevApp(TOP)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", true, TOP)
            }
            AynAction.PREV_APP_BOTTOM -> {
                if (prevApp(BOTTOM)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", false, BOTTOM)
            }
            AynAction.RECENTS_TOGGLE -> {
                // Recents task keeps visible=true after closing; trust focus on the top display instead.
                val visible = recentsShown()
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

    // Null display = lone Home, sent to the touched-or-focused screen. Pad focus is left alone.
    // Resolves the default launcher component and launches it directly so Android never fires
    // CLOSE_SYSTEM_DIALOGS(homekey) (which dismisses BDSA). When aynHomeSkipBdsa is enabled and
    // BDSA/DSA holds focus on display 4, lone Home routes to top instead of clearing the overlay.
    fun home(ctx: Context, display: Int?) {
        val d = when {
            display != null -> display
            Prefs.aynHomeSkipBdsa(ctx) && bdsaVisible() -> TOP
            else -> targetDisplay()
        }
        val cat = if (d == BOTTOM) "android.intent.category.SECONDARY_HOME" else "android.intent.category.HOME"
        Log.i(TAG, "home -> $d ($cat)")
        val comp = resolveHomeComponent(cat)
        val r = if (comp != null) {
            Priv.sh("am start --display $d -f 0x10200000 -n '$comp'")
        } else {
            Priv.sh("am start --display $d -a android.intent.action.MAIN -c $cat -f 0x10200000")
        }
        if (r.code != 0 || r.out.contains("Error:")) {
            val r2 = Priv.sh("am start --display $d -a android.intent.action.MAIN -c $cat -f 0x10200000")
            if (r2.code != 0 || r2.out.contains("Error:")) Priv.sh("input -d $d keyevent HOME")
        }
        if (display != null) Feedback.show(ctx, FeedbackType.HOME, "Home: ${name(d)}", d == TOP, d)
    }

    private fun resolveHomeComponent(cat: String): String? {
        val out = Priv.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c $cat").out
        val line = out.lines().map { it.trim() }.lastOrNull { '/' in it && !it.startsWith("priority=") } ?: return null
        val safe = line.filter { it.isLetterOrDigit() || it in "._/$" }
        return safe.takeIf { '/' in it }
    }

    private fun bdsaVisible(): Boolean {
        val focus = Priv.sh("dumpsys window displays | grep -E 'Display: mDisplayId=|mCurrentFocus='").out
        var display = -1
        for (line in focus.lines()) {
            Regex("mDisplayId=(\\d+)").find(line)?.let { display = it.groupValues[1].toInt() }
            if (display == BOTTOM && line.contains("mCurrentFocus=")) {
                return line.contains("betterdualscreenassistant") ||
                    line.contains("dualscreen.assistant") ||
                    line.contains("odin.gameassistant")
            }
        }
        return false
    }

    // Moves the mouse pointer; moves gamepad screen_focus_lock only when aynFocusMovesPad is on.
    fun focus(ctx: Context, display: Int) {
        pointerBottom = display == BOTTOM
        val movePad = Prefs.aynFocusMovesPad(ctx)
        if (movePad) {
            Priv.sh("settings put system $FOCUS_LOCK ${if (display == BOTTOM) 2 else 1}")
            focusAt = SystemClock.uptimeMillis()
        }
        Daemon.pointer(display == BOTTOM)
        val label = if (movePad) "Focus: ${name(display)}" else "Pointer: ${name(display)}"
        Feedback.show(ctx, FeedbackType.FOCUS, label, display == TOP, display)
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

    private fun recentsShown(): Boolean {
        val focus = Priv.sh("dumpsys window displays | grep -E 'Display: mDisplayId=|mCurrentFocus='").out
        var display = -1
        for (line in focus.lines()) {
            Regex("mDisplayId=(\\d+)").find(line)?.let { display = it.groupValues[1].toInt() }
            if (display == TOP && line.contains("mCurrentFocus=")) {
                Log.i(TAG, "recents check: ${line.trim()}")
                return line.contains("RecentsActivity")
            }
        }
        return false
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
