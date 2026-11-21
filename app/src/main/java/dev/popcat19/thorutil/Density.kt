package dev.popcat19.thorutil

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// Per-panel `wm density`. Android persists overrides across reboot, so nothing re-applies them.
object Density {
    enum class Panel(val displayId: Int, val label: String) { TOP(0, "Top"), BOTTOM(4, "Bottom") }

    data class Info(val physical: Int, val override: Int?) {
        val effective get() = override ?: physical
    }

    data class Preset(val name: String, val top: Int, val bottom: Int)

    const val MIN = 120
    const val MAX = 640

    fun read(p: Panel): Info? {
        val out = Priv.sh("wm density -d ${p.displayId}").out
        val phys = Regex("Physical density: (\\d+)").find(out)?.groupValues?.get(1)?.toInt() ?: return null
        val ovr = Regex("Override density: (\\d+)").find(out)?.groupValues?.get(1)?.toInt()
        return Info(phys, ovr)
    }

    fun set(p: Panel, dpi: Int): Boolean {
        require(dpi in MIN..MAX)
        return Priv.sh("wm density $dpi -d ${p.displayId}").code == 0
    }

    fun reset(p: Panel): Boolean = Priv.sh("wm density reset -d ${p.displayId}").code == 0

    fun presets(ctx: Context): List<Preset> = runCatching {
        val a = JSONArray(Prefs.densityPresets(ctx))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Preset(o.getString("name"), o.getInt("top"), o.getInt("bottom"))
        }
    }.getOrDefault(emptyList())

    fun savePresets(ctx: Context, list: List<Preset>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("top", it.top).put("bottom", it.bottom)) }
        Prefs.setDensityPresets(ctx, a.toString())
    }
}
