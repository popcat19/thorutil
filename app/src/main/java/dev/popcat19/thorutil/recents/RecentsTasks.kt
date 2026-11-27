package dev.popcat19.thorutil.recents

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import dev.popcat19.thorutil.PipFix
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.ayn.AynActions
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import java.util.concurrent.ConcurrentHashMap

data class RecentsTask(
    val rootId: Int,
    val taskId: Int,
    val display: Int,
    val pkg: String,
    val component: String,
    val visible: Boolean,
    val label: String,
    val icon: Drawable?,
)

object RecentsTasks {
    private const val TAG = "ThorUtil/Recents"

    private val HEAD = Regex("""^RootTask id=(\d+) .*displayId=(\d+)""")
    private val CHILD = Regex("""^\s+taskId=(\d+): (\S+/\S+) """)
    private val ACT_STANDARD = Regex("""\bmActivityType=standard\b""")

    private data class AppMeta(val label: String, val icon: Drawable?)

    private val metaCache = ConcurrentHashMap<String, AppMeta>()

    data class ClearResult(
        val cleared: Int,
        val skippedLocked: Int,
        val skippedScreenLocked: Int,
    )

    private fun screenTitle(display: Int) = if (display == AynActions.BOTTOM) "Bottom" else "Top"

    fun appLabel(ctx: Context, pkg: String): String = meta(ctx, pkg).label

    fun appIcon(ctx: Context, pkg: String): Drawable? = meta(ctx, pkg).icon

    private fun meta(ctx: Context, pkg: String): AppMeta = metaCache.getOrPut(pkg) {
        val pm = ctx.packageManager
        runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppMeta(
                label = pm.getApplicationLabel(ai).toString().ifBlank { pkg },
                icon = runCatching { pm.getApplicationIcon(ai) }.getOrNull(),
            )
        }.getOrElse {
            AppMeta(label = pkg, icon = null)
        }
    }

    fun query(ctx: Context): List<RecentsTask> {
        val out = Priv.sh("am stack list").out
        val res = ArrayList<RecentsTask>()
        var root = -1
        var display = -1
        var standard = false
        for (line in out.lines()) {
            HEAD.find(line)?.let { m ->
                root = m.groupValues[1].toInt()
                display = m.groupValues[2].toInt()
                standard = false
                return@let
            }
            if (line.contains("mActivityType=")) {
                standard = ACT_STANDARD.containsMatchIn(line) && !line.contains("mWindowingMode=pinned")
                continue
            }
            if (!standard || root < 0) continue
            if (display != AynActions.TOP && display != AynActions.BOTTOM) continue
            val m = CHILD.find(line) ?: continue
            if (res.any { it.rootId == root }) continue
            val taskId = m.groupValues[1].toInt()
            val comp = m.groupValues[2].filter { it.isLetterOrDigit() || it in "._/$" }
            if ('/' !in comp) continue
            val pkg = comp.substringBefore('/')
            val info = meta(ctx, pkg)
            res.add(
                RecentsTask(
                    rootId = root,
                    taskId = taskId,
                    display = display,
                    pkg = pkg,
                    component = comp,
                    visible = line.contains("visible=true"),
                    label = info.label,
                    icon = info.icon,
                )
            )
        }
        return res
    }

    fun open(ctx: Context, task: RecentsTask): Boolean {
        val locked = AynActions.lockedDisplay()
        if (locked != null && task.display == locked) {
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${screenTitle(locked)} locked", false, locked)
            return false
        }
        Log.i(TAG, "open task=${task.taskId} root=${task.rootId} display=${task.display} (${task.pkg})")
        val r = Priv.sh("cmd activity task focus ${task.taskId}")
        if (r.code != 0 || r.out.contains("Failed") || r.out.contains("Error:")) {
            Priv.sh("am start --display ${task.display} -f 0x10200000 -n '${task.component}'")
        }
        PipFix.nudge()
        return true
    }

    fun close(ctx: Context, task: RecentsTask): Boolean {
        val locked = AynActions.lockedDisplay()
        if (locked != null && task.display == locked) {
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${screenTitle(locked)} locked", false, locked)
            return false
        }
        if (task.pkg in Prefs.recentsLockedPackages(ctx)) {
            Feedback.show(ctx, FeedbackType.RECENTS, "Locked: ${task.label}", false, task.display)
            return false
        }
        Log.i(TAG, "close root=${task.rootId} (${task.pkg})")
        val r = Priv.sh("cmd activity stack remove ${task.rootId}")
        PipFix.nudge()
        return r.code == 0
    }

    fun move(ctx: Context, task: RecentsTask): Int? {
        val target = if (task.display == AynActions.BOTTOM) AynActions.TOP else AynActions.BOTTOM
        val locked = AynActions.lockedDisplay()
        if (locked != null && (target == locked || task.display == locked)) {
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "${screenTitle(locked)} locked", false, locked)
            return null
        }
        Log.i(TAG, "move root=${task.rootId} (${task.pkg}) ${task.display} -> $target")
        val r = Priv.sh("cmd activity display move-stack ${task.rootId} $target")
        if (r.code != 0 || r.out.contains("Error:")) return null
        PipFix.nudge()
        val label = if (target == AynActions.BOTTOM) "Moved to bottom" else "Moved to top"
        Feedback.show(ctx, FeedbackType.WINDOWS, label, target == AynActions.TOP, target)
        return target
    }

    fun openAppInfo(ctx: Context, task: RecentsTask, display: Int) {
        val locked = AynActions.lockedDisplay()
        val target = if (locked != null && display == locked) {
            if (locked == AynActions.BOTTOM) AynActions.TOP else AynActions.BOTTOM
        } else {
            display
        }
        val safePkg = task.pkg.filter { it.isLetterOrDigit() || it in "._" }
        if (safePkg.isEmpty()) return
        Log.i(TAG, "app info pkg=$safePkg display=$target")
        Priv.sh("am start --display $target -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:$safePkg' -f 0x10000000")
    }

    fun clearAll(ctx: Context, tasks: List<RecentsTask>): ClearResult {
        val lockedPkgs = Prefs.recentsLockedPackages(ctx)
        val lockedDisplay = AynActions.lockedDisplay()
        val targets = ArrayList<RecentsTask>()
        var skippedLocked = 0
        var skippedScreen = 0
        for (t in tasks) {
            when {
                lockedDisplay != null && t.display == lockedDisplay -> skippedScreen++
                t.pkg in lockedPkgs -> skippedLocked++
                else -> targets.add(t)
            }
        }
        if (targets.isNotEmpty()) {
            val cmd = targets.joinToString("; ") { "cmd activity stack remove ${it.rootId}" }
            Log.i(TAG, "clearAll count=${targets.size} (skippedLocked=$skippedLocked, skippedScreen=$skippedScreen)")
            Priv.sh(cmd)
            PipFix.nudge()
        }
        return ClearResult(targets.size, skippedLocked, skippedScreen)
    }
}
