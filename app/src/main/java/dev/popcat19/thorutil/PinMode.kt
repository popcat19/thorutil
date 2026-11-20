package dev.popcat19.thorutil

import android.content.Context
import android.provider.Settings

// Framework encoding from WindowManagerService.setPinScreenMode on the Thor:
// 0 = off (ime_fixed=0), 1 = top (ime_fixed=1, ime_show_on_second=0), 2 = bottom (ime_show_on_second=1).
enum class PinMode(val id: Int, val label: String) {
    OFF(0, "Off"), TOP(1, "Top"), BOTTOM(2, "Bottom");

    companion object {
        const val IME_FIXED = "ime_fixed"
        const val IME_SHOW_ON_SECOND = "ime_show_on_second"

        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OFF

        fun current(ctx: Context): PinMode {
            val cr = ctx.contentResolver
            if (Settings.System.getInt(cr, IME_FIXED, 0) != 1) return OFF
            return if (Settings.System.getInt(cr, IME_SHOW_ON_SECOND, 0) == 1) BOTTOM else TOP
        }

        fun apply(mode: PinMode): Boolean {
            val cmd = when (mode) {
                OFF -> "settings put system $IME_FIXED 0"
                TOP -> "settings put system $IME_SHOW_ON_SECOND 0 && settings put system $IME_FIXED 1"
                BOTTOM -> "settings put system $IME_SHOW_ON_SECOND 1 && settings put system $IME_FIXED 1"
            }
            return Priv.sh(cmd).code == 0
        }
    }
}
