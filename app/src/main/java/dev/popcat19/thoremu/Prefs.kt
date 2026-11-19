package dev.popcat19.thoremu

import android.content.Context
import android.content.SharedPreferences

// Device-protected storage so the pin choice is readable before first unlock.
object Prefs {
    private const val PIN = "pin_mode"
    private const val MANAGED = "pin_managed"
    private const val DAEMON_BOOT = "daemon_on_boot"
    private const val DEADZONE = "deadzone"
    private const val POINTER = "pointer_speed"
    private const val SCROLL = "scroll_speed"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("thor_emu", Context.MODE_PRIVATE)

    fun pinManaged(ctx: Context) = sp(ctx).getBoolean(MANAGED, false)
    fun pinMode(ctx: Context) = PinMode.of(sp(ctx).getInt(PIN, PinMode.BOTTOM.id))
    fun setPin(ctx: Context, managed: Boolean, mode: PinMode) =
        sp(ctx).edit().putBoolean(MANAGED, managed).putInt(PIN, mode.id).apply()

    fun daemonOnBoot(ctx: Context) = sp(ctx).getBoolean(DAEMON_BOOT, false)
    fun setDaemonOnBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(DAEMON_BOOT, v).apply()

    fun deadzone(ctx: Context) = sp(ctx).getFloat(DEADZONE, 0.15f)
    fun pointerSpeed(ctx: Context) = sp(ctx).getFloat(POINTER, 1400f)
    fun scrollSpeed(ctx: Context) = sp(ctx).getFloat(SCROLL, 18f)
    fun setTuning(ctx: Context, dz: Float, ptr: Float, scr: Float) =
        sp(ctx).edit().putFloat(DEADZONE, dz).putFloat(POINTER, ptr).putFloat(SCROLL, scr).apply()
}
