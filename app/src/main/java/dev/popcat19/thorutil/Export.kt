package dev.popcat19.thorutil

import android.content.Context
import org.json.JSONObject

object Export {
    enum class Section(val key: String, val label: String) {
        PIN("pin", "IME Pin"),
        MOUSE("mouse", "Mouse Tuning"),
        ROTATION("rotation", "Rotation"),
        PIP("pip", "Bottom PiP"),
        DENSITY("density", "Density Presets"),
        VOLUME("volume", "Volume Link"),
        FEEDBACK("feedback", "Feedback"),
        FAN("fan", "Fan Curve"),
        PERF("perf", "Adaptive Perf"),
        AYN("ayn", "AYN Combos"),
        RECENTS("recents", "Recents"),
        AUTOLAUNCH("autolaunch", "Boot & Background Apps"),
    }

    private val sections = mapOf(
        "pin_mode" to Section.PIN,
        "pin_managed" to Section.PIN,
        "ime_rebind_fix" to Section.PIN,
        "deadzone" to Section.MOUSE,
        "pointer_speed" to Section.MOUSE,
        "scroll_speed" to Section.MOUSE,
        "daemon_on_boot" to Section.MOUSE,
        "lock_suspend" to Section.MOUSE,
        "mouse_rotate_dpad" to Section.MOUSE,
        "mouse_ignore_touch_focus" to Section.MOUSE,
        "mouse_idle_hide" to Section.MOUSE,
        "mouse_idle_hide_s" to Section.MOUSE,
        "odin_key_first" to Section.MOUSE,
        "odin_key_second" to Section.MOUSE,
        "rotation_mode" to Section.ROTATION,
        "rotation_hint" to Section.ROTATION,
        "rotation_bottom" to Section.ROTATION,
        "rotation_reverse_in_cycle" to Section.ROTATION,
        "pip_fix_mode" to Section.PIP,
        "density_presets" to Section.DENSITY,
        "volume_link" to Section.VOLUME,
        "volume_offset" to Section.VOLUME,
        "fan_managed" to Section.FAN,
        "fan_curve" to Section.FAN,
        "fan_tau" to Section.FAN,
        "fan_hyst" to Section.FAN,
        "fan_slew_up" to Section.FAN,
        "fan_slew_down" to Section.FAN,
        "fan_guard_c" to Section.FAN,
        "fan_guard_s" to Section.FAN,
        "fan_zero_battery" to Section.FAN,
        "fan_zero_off_c" to Section.FAN,
        "fan_zero_on_c" to Section.FAN,
        "perf_adaptive" to Section.PERF,
        "ayn_focus_combos" to Section.AYN,
        "ayn_focus_moves_pad" to Section.AYN,
        "ayn_home_double_recents" to Section.AYN,
        "ayn_back_gestures" to Section.AYN,
        "ayn_home_skip_bdsa" to Section.AYN,
        "ayn_screen_lock" to Section.AYN,
        "recents_overlay" to Section.RECENTS,
        "recents_locked_pkgs" to Section.RECENTS,
        "autolaunch_apps" to Section.AUTOLAUNCH,
    )

    private val floats = setOf(
        "deadzone", "pointer_speed", "scroll_speed",
        "fan_tau", "fan_hyst", "fan_slew_up", "fan_slew_down", "fan_guard_c",
        "fan_zero_off_c", "fan_zero_on_c",
    )
    private const val FORMAT = 1

    fun export(ctx: Context, s: Set<Section>): String {
        val p = Prefs.prefs(ctx)
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
        val p = Prefs.prefs(ctx)
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
