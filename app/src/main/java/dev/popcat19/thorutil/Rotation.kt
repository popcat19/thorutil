package dev.popcat19.thorutil

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import android.view.View
import android.view.WindowManager

// The Thor's DisplayRotation.setUserRotation discards the requested rotation and stores
// persist.panel.orientation instead, so `wm user-rotation lock` cannot force portrait.
// DisplayArea.Tokens still honours screenOrientation on visible non-app windows, so an
// invisible overlay on the top display decides its orientation. Odin Settings' Force
// Landscape tile (system force_landscape) adds a higher MAGNIFICATION_OVERLAY requesting
// landscape, which wins, so it is switched off while a fixed mode is held and restored
// afterwards.
enum class RotationMode(val id: Int, val label: String, val shortLabel: String, val orientation: Int) {
    AUTO(0, "Auto", "Auto", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT(1, "Portrait", "Port", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    REVERSE_PORTRAIT(3, "Reverse Portrait", "Rev", ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT),
    LANDSCAPE(2, "Landscape", "Land", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

    fun next(includeReverse: Boolean = false): RotationMode {
        val list = if (includeReverse) listOf(AUTO, PORTRAIT, REVERSE_PORTRAIT, LANDSCAPE)
        else listOf(AUTO, PORTRAIT, LANDSCAPE)
        val idx = list.indexOf(this)
        return list[(idx + 1) % list.size]
    }

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

object Rotation {
    const val BOTTOM_DISPLAY = 4
    private val views = mutableMapOf<Int, View>()

    private const val FORCE_LANDSCAPE = "force_landscape"

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    // SYSTEM_ALERT_WINDOW is an appop, so shell (Shizuku) or root can grant it without UI.
    fun grantOverlay(ctx: Context): Boolean =
        Priv.sh("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow").code == 0

    // Blocking shell call, so off the main thread.
    fun syncForceLandscape(ctx: Context, mode: RotationMode) {
        val cur = Settings.System.getInt(ctx.contentResolver, FORCE_LANDSCAPE, 0)
        if (mode != RotationMode.AUTO) {
            if (cur != 0) {
                Prefs.setSavedForceLandscape(ctx, cur)
                Priv.sh("settings put system $FORCE_LANDSCAPE 0")
            }
        } else {
            val saved = Prefs.savedForceLandscape(ctx)
            if (saved > 0 && cur == 0) Priv.sh("settings put system $FORCE_LANDSCAPE $saved")
            Prefs.setSavedForceLandscape(ctx, 0)
        }
    }

    // Main thread only. The bottom display follows when Prefs.rotationBottom is on.
    fun apply(ctx: Context, mode: RotationMode) {
        if (mode == RotationMode.AUTO || !canOverlay(ctx)) {
            remove()
            return
        }
        hold(ctx, Display.DEFAULT_DISPLAY, mode)
        if (Prefs.rotationBottom(ctx)) hold(ctx, BOTTOM_DISPLAY, mode) else release(BOTTOM_DISPLAY)
    }

    private fun hold(ctx: Context, id: Int, mode: RotationMode) {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(id) ?: return
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { screenOrientation = mode.orientation }
        val v = views[id]
        if (v != null) {
            v.context.getSystemService(WindowManager::class.java).updateViewLayout(v, lp)
            return
        }
        val wctx = ctx.createDisplayContext(d)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        views[id] = View(wctx).also {
            it.setBackgroundColor(Color.TRANSPARENT)
            wctx.getSystemService(WindowManager::class.java).addView(it, lp)
        }
    }

    private fun release(id: Int) {
        val v = views.remove(id) ?: return
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    fun remove() {
        views.keys.toList().forEach(::release)
    }
}
