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
    private const val ROTATION_HINT = "rotation_hint"
    private const val ROTATION_BOTTOM = "rotation_bottom"
    private const val DENSITY_PRESETS = "density_presets"
    private const val SAVED_FORCE_LANDSCAPE = "saved_force_landscape"
    private const val VOLUME_LINK = "volume_link"
    private const val VOLUME_OFFSET = "volume_offset"
    private const val FAN_MANAGED = "fan_managed"
    private const val FAN_CURVE = "fan_curve"
    private const val FAN_TAU = "fan_tau"
    private const val FAN_HYST = "fan_hyst"
    private const val FAN_SLEW_UP = "fan_slew_up"
    private const val FAN_SLEW_DOWN = "fan_slew_down"
    private const val FAN_GUARD_C = "fan_guard_c"
    private const val FAN_GUARD_S = "fan_guard_s"
    private const val SAVED_FAN_MODE = "saved_fan_mode"
    private const val SAVED_FAN_SPEED = "saved_fan_speed"
    private const val LAST_NATIVE_DIR = "last_native_dir"

    // Board soak (xo-therm) curve: flat quiet 20% through idle/light loads (<=48 °C),
    // progressive ramp through medium (54-60 °C) and heavy gaming (66-72 °C).
    const val FAN_CURVE_DEFAULT = "48:20,54:32,60:50,66:75,72:100"
    const val FAN_TAU_DEFAULT = 15f
    const val FAN_HYST_DEFAULT = 2.5f
    const val FAN_SLEW_UP_DEFAULT = 1.5f
    const val FAN_SLEW_DOWN_DEFAULT = 0.5f
    const val FAN_GUARD_C_DEFAULT = 98f
    const val FAN_GUARD_S_DEFAULT = 20

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

    fun rotationHint(ctx: Context) = sp(ctx).getBoolean(ROTATION_HINT, false)
    fun setRotationHint(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(ROTATION_HINT, on).apply()

    fun rotationBottom(ctx: Context) = sp(ctx).getBoolean(ROTATION_BOTTOM, true)
    fun setRotationBottom(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(ROTATION_BOTTOM, on).apply()

    fun savedForceLandscape(ctx: Context) = sp(ctx).getInt(SAVED_FORCE_LANDSCAPE, 0)
    fun setSavedForceLandscape(ctx: Context, v: Int) = sp(ctx).edit().putInt(SAVED_FORCE_LANDSCAPE, v).apply()

    fun volumeLink(ctx: Context) = sp(ctx).getBoolean(VOLUME_LINK, false)
    fun setVolumeLink(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(VOLUME_LINK, on).apply()

    fun volumeOffset(ctx: Context) = sp(ctx).getInt(VOLUME_OFFSET, 0)
    fun setVolumeOffset(ctx: Context, v: Int) = sp(ctx).edit().putInt(VOLUME_OFFSET, v).apply()

    fun densityPresets(ctx: Context) = sp(ctx).getString(DENSITY_PRESETS, "[]") ?: "[]"
    fun setDensityPresets(ctx: Context, json: String) = sp(ctx).edit().putString(DENSITY_PRESETS, json).apply()

    fun deadzone(ctx: Context) = sp(ctx).getFloat(DEADZONE, 0.02f)
    fun pointerSpeed(ctx: Context) = sp(ctx).getFloat(POINTER, 1000f)
    fun scrollSpeed(ctx: Context) = sp(ctx).getFloat(SCROLL, 12f)
    fun setTuning(ctx: Context, dz: Float, ptr: Float, scr: Float) =
        sp(ctx).edit().putFloat(DEADZONE, dz).putFloat(POINTER, ptr).putFloat(SCROLL, scr).apply()

    fun fanManaged(ctx: Context) = sp(ctx).getBoolean(FAN_MANAGED, false)
    fun setFanManaged(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(FAN_MANAGED, v).apply()

    fun fanCurve(ctx: Context): String = sp(ctx).getString(FAN_CURVE, FAN_CURVE_DEFAULT) ?: FAN_CURVE_DEFAULT
    fun setFanCurve(ctx: Context, v: String) = sp(ctx).edit().putString(FAN_CURVE, v).apply()

    fun fanTau(ctx: Context) = sp(ctx).getFloat(FAN_TAU, FAN_TAU_DEFAULT)
    fun setFanTau(ctx: Context, v: Float) = sp(ctx).edit().putFloat(FAN_TAU, v).apply()

    fun fanHyst(ctx: Context) = sp(ctx).getFloat(FAN_HYST, FAN_HYST_DEFAULT)
    fun setFanHyst(ctx: Context, v: Float) = sp(ctx).edit().putFloat(FAN_HYST, v).apply()

    fun fanSlewUp(ctx: Context) = sp(ctx).getFloat(FAN_SLEW_UP, FAN_SLEW_UP_DEFAULT)
    fun setFanSlewUp(ctx: Context, v: Float) = sp(ctx).edit().putFloat(FAN_SLEW_UP, v).apply()

    fun fanSlewDown(ctx: Context) = sp(ctx).getFloat(FAN_SLEW_DOWN, FAN_SLEW_DOWN_DEFAULT)
    fun setFanSlewDown(ctx: Context, v: Float) = sp(ctx).edit().putFloat(FAN_SLEW_DOWN, v).apply()

    fun fanGuardC(ctx: Context) = sp(ctx).getFloat(FAN_GUARD_C, FAN_GUARD_C_DEFAULT)
    fun setFanGuardC(ctx: Context, v: Float) = sp(ctx).edit().putFloat(FAN_GUARD_C, v).apply()

    fun fanGuardS(ctx: Context) = sp(ctx).getInt(FAN_GUARD_S, FAN_GUARD_S_DEFAULT)
    fun setFanGuardS(ctx: Context, v: Int) = sp(ctx).edit().putInt(FAN_GUARD_S, v).apply()

    // Odin fan_mode before ThorUtil switched to custom; 4 = Smart.
    fun savedFanMode(ctx: Context) = sp(ctx).getInt(SAVED_FAN_MODE, 4)
    fun setSavedFanMode(ctx: Context, v: Int) = sp(ctx).edit().putInt(SAVED_FAN_MODE, v).apply()

    // Odin fan_speed before ThorUtil set -1 to suppress Odin custom duty writes.
    fun savedFanSpeed(ctx: Context) = sp(ctx).getInt(SAVED_FAN_SPEED, 35)
    fun setSavedFanSpeed(ctx: Context, v: Int) = sp(ctx).edit().putInt(SAVED_FAN_SPEED, v).apply()

    // Tracks nativeLibraryDir so daemons restart onto the new binary after an APK upgrade.
    fun lastNativeDir(ctx: Context): String = sp(ctx).getString(LAST_NATIVE_DIR, "") ?: ""
    fun setLastNativeDir(ctx: Context, v: String) = sp(ctx).edit().putString(LAST_NATIVE_DIR, v).apply()
}
