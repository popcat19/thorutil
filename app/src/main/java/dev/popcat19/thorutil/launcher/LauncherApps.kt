package dev.popcat19.thorutil.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Priv

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

    fun launch(app: LauncherApp, display: Int) {
        val comp = app.component.flattenToShortString().filter { it.isLetterOrDigit() || it in "._/$" }
        Bg.post { Priv.sh("am start --display $display -f 0x10200000 -n '$comp'") }
    }
}
