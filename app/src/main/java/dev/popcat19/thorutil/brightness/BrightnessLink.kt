package dev.popcat19.thorutil.brightness

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.ayn.AynActions
import kotlin.math.pow
import kotlin.math.roundToInt

// Step both screens to one shared brightness via AYN+Vol, turning off adaptive.
// DisplayManager.setBrightness (shell has CONTROL_DISPLAY_BRIGHTNESS) writes panel0/1.
object BrightnessLink {
    private const val TAG = "ThorUtil/Brightness"
    private const val STEP = 0.03929f // (0.06^(1/2.2))^16 ≈ 1, ~6% perceptual
    private const val GAMMA = 2.2f

    private val PARCEL = Regex("""Parcel\(00000000 ([0-9a-f]{8})""")

    private fun read(d: Int): Float {
        val hex = PARCEL.find(Priv.sh("service call display 36 i32 $d").out)?.groupValues?.get(1)
            ?: return Float.NaN
        return java.lang.Float.intBitsToFloat(hex.toLong(16).toInt())
    }

    private fun write(d: Int, v: Float) = Priv.sh("service call display 35 i32 $d f $v")

    private fun autoOff() {
        val mode = Priv.sh("settings get system screen_brightness_mode").out.trim()
        if (mode == "1") Priv.sh("settings put system screen_brightness_mode 0")
    }

    fun step(ctx: Context, up: Boolean): Int {
        autoOff()
        val top = read(AynActions.TOP).let { if (it.isNaN()) 0.5f else it.coerceIn(0f, 1f) }
        val lin = (top.pow(1 / GAMMA) + (if (up) STEP else -STEP)).coerceIn(0f, 1f)
        val v = lin.pow(GAMMA)
        write(AynActions.TOP, v)
        write(AynActions.BOTTOM, v)
        val pct = (lin * 100).roundToInt()
        Log.i(TAG, "step $pct%")
        return pct
    }
}
