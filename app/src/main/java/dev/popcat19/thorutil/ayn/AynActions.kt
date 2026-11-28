package dev.popcat19.thorutil.ayn

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.popcat19.thorutil.Daemon
import dev.popcat19.thorutil.PipFix
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.brightness.BrightnessLink
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import dev.popcat19.thorutil.recents.RecentsOverlay

// Shell-side actions. screen_focus_lock steers gamepad keys only (Odin Settings maps it
// to persist.sys.input.dispaly for inputflinger). Touch never moves pad focus, but the
// last touched screen is the target for previous app when it is newer than the pad focus.
object AynActions {
    private const val TAG = "ThorUtil/Ayn"
    const val TOP = 0
    const val BOTTOM = 4
    private const val FOCUS_LOCK = "screen_focus_lock"
    private const val TOUCH = "/data/local/tmp/thorutil-touch"
    private val ACT_STANDARD = Regex("""\bmActivityType=standard\b""")

    @Volatile
    private var focusAt = 0L

    @Volatile
    private var pointerBottom = false

    @Volatile
    private var cachedPadDisplay = TOP

    @Volatile
    private var cachedPadDisplayAt = 0L

    private val screenLockMutex = Any()

    @Volatile
    private var lockedDisplay: Int? = null

    private var savedFocusLock: String? = null

    private var appliedFocusLock: String? = null

    // Temporary screen_focus_lock override while RecentsOverlay is open so Odin's
    // InputDispatcher delivers joystick/hat MotionEvents to the overlay's display.
    private var focusOverrideActive = false
    private var preOverrideFocusLock: String? = null
    private var appliedFocusOverride: String? = null

    private fun name(d: Int) = if (d == BOTTOM) "bottom" else "top"

    private fun titleName(d: Int) = if (d == BOTTOM) "Bottom" else "Top"

    private fun other(d: Int) = if (d == BOTTOM) TOP else BOTTOM

    fun lockedDisplay(): Int? = lockedDisplay

    private fun odinFocusLock(ctx: Context): Int? =
        when (runCatching { Settings.System.getString(ctx.contentResolver, FOCUS_LOCK)?.trim() }.getOrNull()) {
            "1" -> TOP
            "2" -> BOTTOM
            else -> null
        }

    private fun odinFocusLockRaw(ctx: Context): String =
        runCatching { Settings.System.getString(ctx.contentResolver, FOCUS_LOCK)?.trim() }.getOrNull()
            ?.takeIf { it == "0" || it == "1" || it == "2" } ?: "0"

    fun acquireFocusLockOverride(ctx: Context, display: Int) = synchronized(screenLockMutex) {
        Daemon.setSuspended(Daemon.SuspendReason.RECENTS, true)
        val want = if (display == BOTTOM) "2" else "1"
        val cur = odinFocusLockRaw(ctx)
        if (!focusOverrideActive) {
            focusOverrideActive = true
            preOverrideFocusLock = cur
        }
        if (cur != want) {
            appliedFocusOverride = want
            Priv.sh("settings put system $FOCUS_LOCK $want")
        }
        Log.i(TAG, "recents focus override -> ${name(display)} (cur=$cur, saved=$preOverrideFocusLock)")
    }

    fun releaseFocusLockOverride(ctx: Context) = synchronized(screenLockMutex) {
        releaseFocusLockOverrideLocked(ctx)
    }

    private fun releaseFocusLockOverrideLocked(ctx: Context) {
        Daemon.setSuspended(Daemon.SuspendReason.RECENTS, false)
        if (!focusOverrideActive) return
        val restore = preOverrideFocusLock
        val applied = appliedFocusOverride
        focusOverrideActive = false
        preOverrideFocusLock = null
        appliedFocusOverride = null
        if (applied != null && restore != null && restore != applied) {
            val cur = odinFocusLockRaw(ctx)
            if (cur == applied) {
                Priv.sh("settings put system $FOCUS_LOCK $restore")
            }
        }
        Log.i(TAG, "recents focus override released (restore=$restore)")
    }

    private fun dismissRecentsForAction(ctx: Context) {
        if (RecentsOverlay.isOpen()) {
            RecentsOverlay.dismiss(releaseInput = false)
            releaseFocusLockOverride(ctx)
            Daemon.setSuspended(Daemon.SuspendReason.RECENTS, false)
        }
    }

    fun toggleScreenLock(ctx: Context) = synchronized(screenLockMutex) {
        if (RecentsOverlay.isOpen()) {
            RecentsOverlay.dismiss(releaseInput = false)
            releaseFocusLockOverrideLocked(ctx)
            Daemon.setSuspended(Daemon.SuspendReason.RECENTS, false)
        }
        if (lockedDisplay != null) {
            unlockLocked(ctx, notify = true)
            return@synchronized
        }
        // Heed Odin's screen_focus_lock when set; fall back to target display in Auto mode.
        val d = odinFocusLock(ctx) ?: targetDisplay(ctx)
        val cur = odinFocusLockRaw(ctx)
        val want = if (d == BOTTOM) "2" else "1"
        savedFocusLock = cur
        lockedDisplay = d
        if (cur != want) {
            appliedFocusLock = want
            Priv.sh("settings put system $FOCUS_LOCK $want")
            focusAt = SystemClock.uptimeMillis()
        } else {
            appliedFocusLock = null
        }
        Log.i(TAG, "screen lock -> ${name(d)} (saved $FOCUS_LOCK=$cur)")
        Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "Locked: ${name(d)}", true, d)
    }

    fun unlock(ctx: Context, notify: Boolean = false) = synchronized(screenLockMutex) {
        if (RecentsOverlay.isOpen()) {
            RecentsOverlay.dismiss(releaseInput = false)
            releaseFocusLockOverrideLocked(ctx)
            Daemon.setSuspended(Daemon.SuspendReason.RECENTS, false)
        }
        unlockLocked(ctx, notify)
    }

    private fun unlockLocked(ctx: Context, notify: Boolean) {
        val prev = lockedDisplay ?: return
        val saved = savedFocusLock
        val applied = appliedFocusLock
        lockedDisplay = null
        savedFocusLock = null
        appliedFocusLock = null
        // Restore Odin's prior screen_focus_lock only if we changed it and Odin hasn't modified it since.
        if (applied != null && saved != null && saved != applied) {
            val cur = odinFocusLockRaw(ctx)
            if (cur == applied) {
                Priv.sh("settings put system $FOCUS_LOCK $saved")
            }
        }
        Log.i(TAG, "screen lock -> off (was ${name(prev)})")
        if (notify) Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "Screen unlocked", false, prev)
    }

    fun tap(ctx: Context) {
        Daemon.tap(ctx)
    }

    // Scoped recents: opens the overlay on an explicit display (Home + shoulder, pressed twice).
    fun recentsOn(ctx: Context, display: Int) {
        if (!Prefs.recentsOverlay(ctx)) {
            home(ctx, display)
            return
        }
        if (display == lockedDisplay) {
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${titleName(display)} locked", false, display)
            return
        }
        RecentsOverlay.toggle(ctx, display)
    }

    // Null display = lone Back, routed to the touched-or-focused screen.
    fun back(ctx: Context, display: Int? = null) {
        dismissRecentsForAction(ctx)
        if (display != null && display == lockedDisplay) {
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${titleName(display)} locked", false, display)
            return
        }
        val d = display ?: lockedDisplay?.let(::other) ?: targetDisplay(ctx)
        if (dev.popcat19.thorutil.launcher.LauncherActivity.consumeBack(d)) {
            Log.i(TAG, "back consumed by launcher ime on $d")
            return
        }
        Log.i(TAG, "back -> $d")
        Priv.sh("input -d $d keyevent BACK")
        PipFix.nudge()
    }

    fun stepBrightness(ctx: Context, delta: Int) {
        val pct = BrightnessLink.step(ctx, delta)
        Feedback.show(ctx, FeedbackType.BRIGHTNESS, "Brightness $pct%", delta > 0, padDisplay(ctx, allowCache = true))
    }

    fun run(ctx: Context, a: AynAction) {
        Log.i(TAG, "run $a")
        when (a) {
            AynAction.FOCUS_TOP -> focus(ctx, TOP)
            AynAction.FOCUS_BOTTOM -> focus(ctx, BOTTOM)
            AynAction.FOCUS_SWAP -> {
                dismissRecentsForAction(ctx)
                val movePad = Prefs.aynFocusMovesPad(ctx) && lockedDisplay == null
                val cur = if (movePad) padDisplay(ctx) else if (pointerBottom) BOTTOM else TOP
                focus(ctx, if (cur == BOTTOM) TOP else BOTTOM)
            }
            AynAction.SWAP_WINDOWS -> {
                dismissRecentsForAction(ctx)
                swapWindows()
                Feedback.show(ctx, FeedbackType.WINDOWS, "Windows swapped", false, padDisplay(ctx))
            }
            AynAction.PREV_APP -> {
                dismissRecentsForAction(ctx)
                val d = lockedDisplay?.let(::other) ?: targetDisplay(ctx)
                if (prevApp(d)) Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", d == TOP, d)
            }
            AynAction.PREV_APP_TOP -> {
                dismissRecentsForAction(ctx)
                if (lockedDisplay == TOP) {
                    Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "Top locked", false, TOP)
                } else if (prevApp(TOP)) {
                    Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", true, TOP)
                }
            }
            AynAction.PREV_APP_BOTTOM -> {
                dismissRecentsForAction(ctx)
                if (lockedDisplay == BOTTOM) {
                    Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "Bottom locked", false, BOTTOM)
                } else if (prevApp(BOTTOM)) {
                    Feedback.show(ctx, FeedbackType.PREV_APP, "Previous app", false, BOTTOM)
                }
            }
            AynAction.RECENTS_TOGGLE -> {
                if (Prefs.recentsOverlay(ctx)) {
                    val wasOpen = RecentsOverlay.isOpen()
                    RecentsOverlay.toggle(ctx)
                    if (wasOpen && Feedback.hapticOn(ctx, FeedbackType.RECENTS)) {
                        Feedback.buzz(ctx, Feedback.hapticFor(ctx, false))
                    }
                } else {
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
            }
            AynAction.SCREEN_LOCK -> toggleScreenLock(ctx)
            AynAction.MOUSE -> {
                dismissRecentsForAction(ctx)
                Daemon.toggle()
            }
            AynAction.BRIGHTNESS_UP, AynAction.BRIGHTNESS_DOWN -> {
                stepBrightness(ctx, if (a == AynAction.BRIGHTNESS_UP) 1 else -1)
            }
        }
    }

    // Null display = lone Home, sent to the touched-or-focused screen. Pad focus is left alone.
    // Resolves the default launcher component and launches it directly so Android never fires
    // CLOSE_SYSTEM_DIALOGS(homekey) (which dismisses BDSA). When aynHomeSkipBdsa is enabled and
    // BDSA/DSA holds focus on display 4, lone Home routes to top instead of clearing the overlay.
    fun home(ctx: Context, display: Int?, respectLock: Boolean = true) {
        dismissRecentsForAction(ctx)
        val locked = if (respectLock) lockedDisplay else null
        if (display != null && display == locked) {
            Log.i(TAG, "home blocked on locked display $display")
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${titleName(display)} locked", false, display)
            return
        }
        val d = when {
            display != null -> display
            locked != null -> other(locked)
            Prefs.aynHomeSkipBdsa(ctx) && bdsaVisible() -> TOP
            else -> targetDisplay(ctx)
        }
        val cat = if (d == BOTTOM) "android.intent.category.SECONDARY_HOME" else "android.intent.category.HOME"
        Log.i(TAG, "home -> $d ($cat)")
        val comp = resolveHomeComponent(ctx, cat)
        val r = if (comp != null) {
            Priv.sh("am start --display $d -f 0x10200000 -n '$comp'")
        } else {
            Priv.sh("am start --display $d -a android.intent.action.MAIN -c $cat -f 0x10200000")
        }
        if (r.code != 0 || r.out.contains("Error:")) {
            val r2 = Priv.sh("am start --display $d -a android.intent.action.MAIN -c $cat -f 0x10200000")
            if (r2.code != 0 || r2.out.contains("Error:")) {
                if (!localHome(ctx, comp, cat, d)) Priv.sh("input -d $d keyevent HOME")
            }
        }
        PipFix.nudge()
        if (display != null) Feedback.show(ctx, FeedbackType.HOME, "Home: ${name(d)}", d == TOP, d)
    }

    // Shizuku and root both down (e.g. after a reboot): every shell path fails, so start locally.
    private fun localHome(ctx: Context, comp: String?, cat: String, d: Int): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(cat)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        comp?.let { android.content.ComponentName.unflattenFromString(it) }?.let { intent.setComponent(it) }
        ctx.startActivity(intent, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(d).toBundle())
        true
    }.getOrElse {
        Log.w(TAG, "local home on $d failed", it)
        false
    }

    private fun resolveHomeComponent(ctx: Context, cat: String): String? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(cat)
        val ai = runCatching {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        }.getOrNull()
        if (ai != null && ai.packageName != "android") {
            val comp = "${ai.packageName}/${ai.name}".filter { it.isLetterOrDigit() || it in "._/$" }
            if ('/' in comp) return comp
        }
        if (cat == "android.intent.category.SECONDARY_HOME" && Prefs.launcherHome(ctx)) {
            return "${ctx.packageName}/.launcher.LauncherSecondaryActivity"
        }
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

    // Moves the mouse pointer; moves gamepad screen_focus_lock only when aynFocusMovesPad is on
    // and no screen lock is active.
    fun focus(ctx: Context, display: Int) {
        dismissRecentsForAction(ctx)
        pointerBottom = display == BOTTOM
        val movePad = Prefs.aynFocusMovesPad(ctx) && lockedDisplay == null
        if (movePad) {
            Priv.sh("settings put system $FOCUS_LOCK ${if (display == BOTTOM) 2 else 1}")
            focusAt = SystemClock.uptimeMillis()
        }
        Daemon.pointer(display == BOTTOM)
        val label = if (movePad) "Focus: ${name(display)}" else "Pointer: ${name(display)}"
        Feedback.show(ctx, FeedbackType.FOCUS, label, display == TOP, display)
    }

    // Last touched screen if touched after the last focus action, else pad focus.
    fun targetDisplay(ctx: Context): Int {
        val p = Priv.sh("cat $TOUCH 2>/dev/null").out.trim().split(' ')
        val at = p.getOrNull(1)?.toLongOrNull() ?: 0L
        val d = p.getOrNull(0)?.toIntOrNull()
        if (d != null && at > focusAt) return if (d == BOTTOM) BOTTOM else TOP
        return padDisplay(ctx)
    }

    fun padDisplay(ctx: Context, allowCache: Boolean = false): Int {
        odinFocusLock(ctx)?.let { return it }
        val now = SystemClock.elapsedRealtime()
        if (allowCache && now - cachedPadDisplayAt < 1_500L) return cachedPadDisplay
        val top = Priv.sh("dumpsys window displays | grep -m1 mTopFocusedDisplayId").out
        val d = if (top.trim().endsWith("=$BOTTOM")) BOTTOM else TOP
        cachedPadDisplay = d
        cachedPadDisplayAt = now
        return d
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
            if (line.contains("mActivityType=")) {
                standard = ACT_STANDARD.containsMatchIn(line) && !line.contains("mWindowingMode=pinned")
            }
            val t = task.find(line) ?: continue
            if (standard && root >= 0 && res.none { it.rootId == root }) res.add(Task(root, display, t.groupValues[1]))
        }
        return res
    }

    fun swapWindows() {
        val all = tasks()
        val top = all.firstOrNull { it.display == TOP }
        val bottom = all.firstOrNull { it.display == BOTTOM }
        val cmds = buildList {
            if (top != null) add("am display move-stack ${top.rootId} $BOTTOM")
            if (bottom != null) add("am display move-stack ${bottom.rootId} $TOP")
        }
        if (cmds.isNotEmpty()) Priv.sh(cmds.joinToString("; "))
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
