package dev.popcat19.thorutil.launcher

import android.content.Context
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.PinMode
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv

// Bottom home cannot hide a keyboard owned by the top screen (hideSoftInput only reaches our own
// window), so a tap or launch there sends Back to the screen holding the IME instead.
object LauncherIme {
    private fun imeShown(): Boolean =
        Priv.sh("dumpsys input_method | grep -m1 'mInputShown='").out.contains("mInputShown=true")

    fun dismissOther(ctx: Context) {
        if (!Prefs.launcherImeDismiss(ctx)) return
        val app = ctx.applicationContext
        Bg.post {
            if (!imeShown()) return@post
            val pinned = PinMode.current(app)
            val target = when (pinned) {
                PinMode.BOTTOM -> return@post
                PinMode.TOP -> 0
                PinMode.OFF -> 0
            }
            Priv.sh("input -d $target keyevent BACK")
        }
    }
}
