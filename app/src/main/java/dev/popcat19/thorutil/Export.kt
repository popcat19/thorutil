package dev.popcat19.thorutil

import android.content.Context
import org.json.JSONObject

object Export {
    enum class Section(val key: String, val label: String) {
        PIN("pin", "IME Pin"),
        MOUSE("mouse", "Mouse Tuning"),
        ROTATION("rotation", "Rotation"),
        DENSITY("density", "Density Presets"),
        VOLUME("volume", "Volume Link"),
        FEEDBACK("feedback", "Feedback"),
    }

    private val sections = mapOf(
        "pin_mode" to Section.PIN,
        "pin_managed" to Section.PIN,
        "deadzone" to Section.MOUSE,
        "pointer_speed" to Section.MOUSE,
        "scroll_speed" to Section.MOUSE,
        "daemon_on_boot" to Section.MOUSE,
        "lock_suspend" to Section.MOUSE,
        "odin_key_first" to Section.MOUSE,
        "odin_key_second" to Section.MOUSE,
        "rotation_mode" to Section.ROTATION,
        "rotation_hint" to Section.ROTATION,
        "rotation_bottom" to Section.ROTATION,
        "density_presets" to Section.DENSITY,
        "volume_link" to Section.VOLUME,
        "volume_offset" to Section.VOLUME,
    )

    private val floats = setOf("deadzone", "pointer_speed", "scroll_speed")
    private const val FORMAT = 1

    fun export(ctx: Context, s: Set<Section>): String {
        val p = ctx.createDeviceProtectedStorageContext().getSharedPreferences("thorutil", Context.MODE_PRIVATE)
        val j = JSONObject()
        for ((k, v) in p.all) {
            val sec = if (k.startsWith("fb_")) Section.FEEDBACK else sections[k]
            if (sec != null && sec in s) j.put(k, v)
        }
        return JSONObject().put("app", "thorutil").put("format", FORMAT).put("prefs", j).toString(2)
    }

    // Returns the number of keys applied. Throws on malformed or foreign files.
    fun import(ctx: Context, json: String, s: Set<Section>): Int {
        val root = JSONObject(json)
        require(root.optString("app") == "thorutil") { "Not a ThorUtil config" }
        val j = root.getJSONObject("prefs")
        val p = ctx.createDeviceProtectedStorageContext().getSharedPreferences("thorutil", Context.MODE_PRIVATE)
        val e = p.edit()
        var n = 0
        for (k in j.keys()) {
            val sec = if (k.startsWith("fb_")) Section.FEEDBACK else sections[k]
            if (sec == null || sec !in s) continue
            val v = j.get(k)
            when {
                k in floats && v is Number -> e.putFloat(k, v.toFloat())
                v is Boolean -> e.putBoolean(k, v)
                v is Int -> e.putInt(k, v)
                v is String -> e.putString(k, v)
                else -> continue
            }
            n++
        }
        e.commit()
        return n
    }
}
