package dev.popcat19.thorutil

import android.content.Context

// Odin settings (com.odin.settings trigger G.u) registers no hotkey when both keys are empty.
object OdinShortcut {
    private const val K1 = "first_gamepad_to_mouse_key"
    private const val K2 = "second_gamepad_to_mouse_key"

    data class Keys(val first: String, val second: String) {
        val cleared get() = first.isEmpty() && second.isEmpty()
        val label get() = if (cleared) "none" else listOf(first, second).filter { it.isNotEmpty() }.joinToString(" + ")
    }

    private fun get(key: String): String =
        Priv.sh("settings get system $key").out.let { if (it == "null") "" else safe(it) }

    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '_' }

    fun read(): Keys = Keys(get(K1), get(K2))

    fun clear(ctx: Context): Boolean {
        val cur = read()
        if (!cur.cleared) Prefs.setOdinKeys(ctx, cur.first, cur.second)
        return Priv.sh("settings put system $K1 '' && settings put system $K2 '' && settings put global global_gamepad_to_mouse_mode 0").code == 0
    }

    fun restore(ctx: Context): Boolean {
        val (a, b) = Prefs.odinKeys(ctx) ?: return false
        return Priv.sh("settings put system $K1 '${safe(a)}' && settings put system $K2 '${safe(b)}'").code == 0
    }
}
