package dev.popcat19.thorutil.feedback

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import dev.popcat19.thorutil.R

enum class FeedbackType(val key: String, val label: String) {
    MOUSE("mouse", "Mouse mode"),
    POINTER("pointer", "Pointer screen"),
    FOCUS("focus", "Pad focus"),
    WINDOWS("windows", "Window swap"),
    PREV_APP("prev_app", "Previous app"),
    HOME("home", "Home on screen"),
    RECENTS("recents", "Recents toggle"),
    ROTATION("rotation", "Rotation"),
    BRIGHTNESS("brightness", "Brightness"),
}

// The Thor motor plays only two distinct effects: click/heavy (bip) and tick/double (brt).
// Bip means true: on, top (main) screen, Auto rotation. Brt means false or a plain action.
enum class Haptic(val id: Int) {
    BIP(VibrationEffect.EFFECT_CLICK),
    BRT(VibrationEffect.EFFECT_TICK),
}

object Feedback {
    private val main = Handler(Looper.getMainLooper())
    private var last: Pair<WindowManager, View>? = null
    private val hide = Runnable { dismiss() }

    private fun sp(ctx: Context) = ctx.createDeviceProtectedStorageContext().getSharedPreferences("thorutil", Context.MODE_PRIVATE)

    fun toastOn(ctx: Context, t: FeedbackType) = sp(ctx).getBoolean("fb_toast_${t.key}", true)
    fun hapticOn(ctx: Context, t: FeedbackType) = sp(ctx).getBoolean("fb_haptic_${t.key}", true)
    fun inverted(ctx: Context) = sp(ctx).getBoolean("fb_haptic_invert", false)
    fun setToast(ctx: Context, t: FeedbackType, on: Boolean) = sp(ctx).edit().putBoolean("fb_toast_${t.key}", on).apply()
    fun setHaptic(ctx: Context, t: FeedbackType, on: Boolean) = sp(ctx).edit().putBoolean("fb_haptic_${t.key}", on).apply()
    fun setInverted(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean("fb_haptic_invert", on).apply()

    fun hapticFor(ctx: Context, value: Boolean) = if (value != inverted(ctx)) Haptic.BIP else Haptic.BRT

    // System toasts are dropped while notifications are denied, so draw an overlay
    // on the screen the action concerns instead.
    fun show(ctx: Context, t: FeedbackType, text: String, value: Boolean, display: Int = 0) {
        val app = ctx.applicationContext
        if (hapticOn(app, t)) buzz(app, hapticFor(app, value))
        if (!toastOn(app, t) || !Settings.canDrawOverlays(app)) return
        main.post { toast(app, text, display) }
    }

    private fun toast(app: Context, text: String, display: Int) {
        dismiss()
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(display) ?: return
        val dc = app.createDisplayContext(d).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val dp = dc.resources.displayMetrics.density
        val v = TextView(dc).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setBackgroundResource(R.drawable.bg_toast)
            setPadding((20 * dp).toInt(), (10 * dp).toInt(), (20 * dp).toInt(), (10 * dp).toInt())
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (64 * dp).toInt()
            alpha = 0.8f
            windowAnimations = android.R.style.Animation_Toast
            title = "ThorUtil toast"
        }
        val wm = dc.getSystemService(WindowManager::class.java)
        runCatching { wm.addView(v, lp) }.onSuccess {
            last = wm to v
            main.postDelayed(hide, 1500)
        }
    }

    private fun dismiss() {
        main.removeCallbacks(hide)
        last?.let { (wm, v) -> runCatching { wm.removeView(v) } }
        last = null
    }

    fun buzz(ctx: Context, h: Haptic) {
        val v = ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        // Hardware feedback usage is not dropped for background callers.
        v.vibrate(
            VibrationEffect.createPredefined(h.id),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK),
        )
    }
}
