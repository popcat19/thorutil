package dev.popcat19.thorutil

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
    private const val LOCK_SUSPEND = "lock_suspend"
    private const val ODIN_K1 = "odin_key_first"
    private const val ODIN_K2 = "odin_key_second"
    private const val ROTATION = "rotation_mode"
    private const val SAVED_FORCE_LANDSCAPE = "saved_force_landscape"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.createDeviceProtectedStorageContext().getSharedPreferences("thorutil", Context.MODE_PRIVATE)

    fun pinManaged(ctx: Context) = sp(ctx).getBoolean(MANAGED, false)
    fun pinMode(ctx: Context) = PinMode.of(sp(ctx).getInt(PIN, PinMode.BOTTOM.id))
    fun setPin(ctx: Context, managed: Boolean, mode: PinMode) =
        sp(ctx).edit().putBoolean(MANAGED, managed).putInt(PIN, mode.id).apply()

    fun daemonOnBoot(ctx: Context) = sp(ctx).getBoolean(DAEMON_BOOT, false)
    fun setDaemonOnBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(DAEMON_BOOT, v).apply()

    fun lockSuspend(ctx: Context) = sp(ctx).getBoolean(LOCK_SUSPEND, true)
    fun setLockSuspend(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(LOCK_SUSPEND, v).apply()

    fun odinKeys(ctx: Context): Pair<String, String>? {
        val p = sp(ctx)
        if (!p.contains(ODIN_K1)) return null
        return (p.getString(ODIN_K1, "") ?: "") to (p.getString(ODIN_K2, "") ?: "")
    }
    fun setOdinKeys(ctx: Context, a: String, b: String) =
        sp(ctx).edit().putString(ODIN_K1, a).putString(ODIN_K2, b).apply()

    fun rotation(ctx: Context) = RotationMode.of(sp(ctx).getInt(ROTATION, RotationMode.AUTO.id))
    fun setRotation(ctx: Context, m: RotationMode) = sp(ctx).edit().putInt(ROTATION, m.id).apply()

    fun savedForceLandscape(ctx: Context) = sp(ctx).getInt(SAVED_FORCE_LANDSCAPE, 0)
    fun setSavedForceLandscape(ctx: Context, v: Int) = sp(ctx).edit().putInt(SAVED_FORCE_LANDSCAPE, v).apply()

    fun deadzone(ctx: Context) = sp(ctx).getFloat(DEADZONE, 0.15f)
    fun pointerSpeed(ctx: Context) = sp(ctx).getFloat(POINTER, 1000f)
    fun scrollSpeed(ctx: Context) = sp(ctx).getFloat(SCROLL, 12f)
    fun setTuning(ctx: Context, dz: Float, ptr: Float, scr: Float) =
        sp(ctx).edit().putFloat(DEADZONE, dz).putFloat(POINTER, ptr).putFloat(SCROLL, scr).apply()
}
