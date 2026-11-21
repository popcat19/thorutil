package dev.popcat19.thorutil

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.ImageButton

// Stock rotation suggestions only cover Auto vs Landscape on the top display. This shows
// a rotate button there when the device is held in the other orientation; tapping it
// locks the top display to the orientation the device is held in.
class RotationHint(private val ctx: Context, private val onPick: (RotationMode) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val top: Display? = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val wctx = top?.let {
        ctx.createDisplayContext(it).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
    private var button: ImageButton? = null
    private var held: RotationMode? = null
    private val show = Runnable { held?.let { showButton(it) } }
    private val hide = Runnable { hideButton() }

    private val listener = object : OrientationEventListener(ctx) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return
            // 20 degree dead band between quadrants avoids flicker near 45 degrees.
            val q = ((deg + 45) / 90) % 4
            val off = Math.floorMod(deg - q * 90 + 180, 360) - 180
            if (kotlin.math.abs(off) > 25) return
            val physical = if (q % 2 == 0) RotationMode.PORTRAIT else RotationMode.LANDSCAPE
            val r = top?.rotation ?: return
            val shown = if (r == Surface.ROTATION_0 || r == Surface.ROTATION_180) RotationMode.PORTRAIT else RotationMode.LANDSCAPE
            val want = if (physical != shown) physical else null
            if (want == held) return
            held = want
            main.removeCallbacks(show)
            if (want == null) hideButton() else main.postDelayed(show, 600)
        }
    }

    fun start() {
        if (Rotation.canOverlay(ctx) && listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
        main.removeCallbacks(show)
        held = null
        hideButton()
    }

    private fun showButton(mode: RotationMode) {
        val c = wctx ?: return
        if (!Rotation.canOverlay(ctx)) return
        val wm = c.getSystemService(WindowManager::class.java)
        val size = (48 * c.resources.displayMetrics.density).toInt()
        val margin = (24 * c.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = margin
            y = margin
        }
        val b = button ?: ImageButton(c).also {
            it.setImageResource(R.drawable.ic_rotation)
            it.setBackgroundResource(R.drawable.bg_hint)
            it.contentDescription = "Rotate top display"
            wm.addView(it, lp)
            button = it
        }
        b.setOnClickListener {
            hideButton()
            onPick(mode)
        }
        main.removeCallbacks(hide)
        main.postDelayed(hide, 5000)
    }

    private fun hideButton() {
        main.removeCallbacks(hide)
        val b = button ?: return
        runCatching { b.context.getSystemService(WindowManager::class.java).removeView(b) }
        button = null
    }
}
