package dev.popcat19.thorutil.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType

data class LauncherApp(
    val pkg: String,
    val label: String,
    val component: ComponentName,
    val icon: Drawable,
)

object LauncherApps {
    fun load(ctx: Context): List<LauncherApp> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val out = ArrayList<LauncherApp>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val ai = ri.activityInfo ?: continue
            val comp = ComponentName(ai.packageName, ai.name)
            if (!seen.add(comp.flattenToShortString())) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(ai.packageName)
            out.add(LauncherApp(ai.packageName, label, comp, ri.loadIcon(pm)))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    // Subsequence match: substring, prefix, consecutive and word-start hits score higher. Null = no match.
    fun score(label: String, query: String): Int? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return 0
        val l = label.lowercase()
        if (l.startsWith(q)) return 2000 - l.length
        var s = if (l.contains(q)) 50 else 0
        var from = 0
        var prev = -2
        for (c in q) {
            val i = l.indexOf(c, from)
            if (i < 0) return null
            s += if (i == prev + 1) 8 else 1
            if (i == 0 || l[i - 1] == ' ') s += 6
            prev = i
            from = i + 1
        }
        return s
    }

    fun filter(apps: List<LauncherApp>, query: String): List<LauncherApp> {
        if (query.isBlank()) return apps
        return apps.mapNotNull { a -> score(a.label, query)?.let { a to it } }
            .sortedWith(compareByDescending<Pair<LauncherApp, Int>> { it.second }.thenBy { it.first.label.lowercase() })
            .map { it.first }
    }

    fun launch(ctx: Context, app: LauncherApp, display: Int) {
        // Shell start keeps the target display reliably. Without a privilege backend (Shizuku
        // stopped after a reboot) fall back to a local start instead of dropping the tap.
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Bg.post {
            val comp = app.component.flattenToShortString().filter { it.isLetterOrDigit() || it in "._/$" }
            val r = Priv.sh("am start --display $display -f 0x10200000 -n '$comp'")
            if (r.code == 0 && !r.out.contains("Error:")) return@post
            main.post {
                if (!local(ctx, app.component, display)) {
                    Feedback.show(ctx, FeedbackType.HOME, "Cannot open ${app.label}", true, display)
                }
            }
        }
    }

    private fun local(ctx: Context, comp: ComponentName, display: Int): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(comp)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()
        ctx.startActivity(intent, opts)
        true
    }.getOrElse {
        android.util.Log.w("ThorUtil/Launcher", "local start $comp on $display failed", it)
        false
    }
}
