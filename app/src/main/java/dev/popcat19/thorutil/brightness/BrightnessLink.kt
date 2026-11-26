package dev.popcat19.thorutil.brightness

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import dev.popcat19.thorutil.Priv
import dev.popcat19.thorutil.ayn.AynActions
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

// Step both screens to one shared brightness via AYN+Vol, turning off adaptive.
// Uses IDisplayManager binder IPC directly when Shizuku is active, and falls back
// to a single batched persistent-shell call on root, caching the linear value across
// rapid/held steps to avoid redundant reads.
object BrightnessLink {
    private const val TAG = "ThorUtil/Brightness"
    private const val STEP = 0.03929f // (0.06^(1/2.2))^16 ≈ 1, ~6% perceptual
    private const val GAMMA = 2.2f
    private const val CACHE_MS = 1_500L
    private const val TX_SET_BRIGHTNESS = 35
    private const val TX_GET_BRIGHTNESS = 36
    private const val DISPLAY_DESCRIPTOR = "android.hardware.display.IDisplayManager"

    private val PARCEL = Regex("""Parcel\(00000000 ([0-9a-f]{8})""")

    private val lock = Any()
    private var cachedLin = Float.NaN
    private var cachedAt = 0L

    private fun shizukuDisplay(): Pair<IBinder, String>? {
        if (!Priv.shizukuReady()) return null
        return try {
            val raw = SystemServiceHelper.getSystemService("display") ?: return null
            val desc = raw.interfaceDescriptor ?: DISPLAY_DESCRIPTOR
            ShizukuBinderWrapper(raw) to desc
        } catch (e: Throwable) {
            Log.w(TAG, "shizuku display binder unavailable", e)
            null
        }
    }

    private fun readBinder(d: Int): Float? {
        val (binder, desc) = shizukuDisplay() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(desc)
            data.writeInt(d)
            if (!binder.transact(TX_GET_BRIGHTNESS, data, reply, 0)) return null
            reply.readException()
            reply.readFloat()
        } catch (e: Throwable) {
            Log.w(TAG, "binder getBrightness($d) failed", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun writeBinder(v: Float): Boolean {
        val (binder, desc) = shizukuDisplay() ?: return false
        return try {
            for (d in intArrayOf(AynActions.TOP, AynActions.BOTTOM)) {
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                try {
                    data.writeInterfaceToken(desc)
                    data.writeInt(d)
                    data.writeFloat(v)
                    if (!binder.transact(TX_SET_BRIGHTNESS, data, reply, 0)) return false
                    reply.readException()
                } finally {
                    data.recycle()
                    reply.recycle()
                }
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "binder setBrightness failed", e)
            false
        }
    }

    private fun read(d: Int): Float {
        readBinder(d)?.let { return it }
        val hex = PARCEL.find(Priv.sh("service call display $TX_GET_BRIGHTNESS i32 $d").out)?.groupValues?.get(1)
            ?: return Float.NaN
        return java.lang.Float.intBitsToFloat(hex.toLong(16).toInt())
    }

    private fun writeBoth(v: Float) {
        if (writeBinder(v)) return
        val fv = String.format(Locale.US, "%.6f", v)
        // Wait only on our two child pids so persistent-shell background daemons cannot stall wait.
        Priv.sh(
            "service call display $TX_SET_BRIGHTNESS i32 ${AynActions.TOP} f $fv >/dev/null & p1=\$!; " +
                "service call display $TX_SET_BRIGHTNESS i32 ${AynActions.BOTTOM} f $fv >/dev/null & p2=\$!; " +
                "wait \$p1 \$p2"
        )
    }

    private fun autoOff(ctx: Context) {
        val mode = Settings.System.getInt(
            ctx.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
            Priv.sh("settings put system screen_brightness_mode 0")
        }
    }

    fun step(ctx: Context, up: Boolean): Int = step(ctx, if (up) 1 else -1)

    fun step(ctx: Context, delta: Int): Int = synchronized(lock) {
        autoOff(ctx)
        val now = SystemClock.elapsedRealtime()
        val fresh = !cachedLin.isNaN() && now - cachedAt < CACHE_MS
        val base = if (fresh) {
            cachedLin
        } else {
            val top = read(AynActions.TOP).let { if (it.isNaN() || it < 0f) 0.5f else it.coerceIn(0f, 1f) }
            top.pow(1 / GAMMA)
        }
        val lin = (base + delta * STEP).coerceIn(0f, 1f)
        if (!fresh || lin != base) {
            writeBoth(lin.pow(GAMMA))
        }
        cachedLin = lin
        cachedAt = SystemClock.elapsedRealtime()
        val pct = (lin * 100).roundToInt()
        Log.i(TAG, "step $pct%")
        pct
    }
}
