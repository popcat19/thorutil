package dev.popcat19.thorutil.launcher

import android.content.Context
import dev.popcat19.thorutil.Prefs
import org.json.JSONArray
import org.json.JSONObject

// Favorites (ordered package list) and user tags (package -> tag set) for the home screen.
object LauncherStore {
    fun favorites(ctx: Context): List<String> =
        Prefs.launcherFavorites(ctx).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun saveFavorites(ctx: Context, pkgs: List<String>) =
        Prefs.setLauncherFavorites(ctx, pkgs.distinct().joinToString("\n"))

    fun isFavorite(ctx: Context, pkg: String) = pkg in favorites(ctx)

    fun toggleFavorite(ctx: Context, pkg: String) {
        val cur = favorites(ctx).toMutableList()
        if (!cur.remove(pkg)) cur.add(pkg)
        saveFavorites(ctx, cur)
    }

    fun moveFavorite(ctx: Context, pkg: String, delta: Int) {
        val cur = favorites(ctx).toMutableList()
        val i = cur.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, cur.lastIndex)
        if (i == j) return
        cur.removeAt(i)
        cur.add(j, pkg)
        saveFavorites(ctx, cur)
    }

    fun tags(ctx: Context): Map<String, Set<String>> {
        val raw = Prefs.launcherTags(ctx)
        if (raw.isBlank()) return emptyMap()
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Set<String>>()
        for (pkg in o.keys()) {
            val arr = o.optJSONArray(pkg) ?: continue
            val set = (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
            if (set.isNotEmpty()) out[pkg] = set
        }
        return out
    }

    fun setTags(ctx: Context, pkg: String, tags: Set<String>) {
        val all = tags(ctx).toMutableMap()
        val clean = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSortedSet()
        if (clean.isEmpty()) all.remove(pkg) else all[pkg] = clean
        val o = JSONObject()
        for ((k, v) in all) o.put(k, JSONArray(v.toList()))
        Prefs.setLauncherTags(ctx, o.toString())
    }

    fun allTags(tags: Map<String, Set<String>>): List<String> = tags.values.flatten().toSortedSet().toList()
}
