package dev.popcat19.thorutil.launcher

import android.content.Context
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Priv

// Per-app actions for the home screen context menu. Shell calls target the display the home runs on.
object LauncherActions {
    private fun safe(pkg: String) = pkg.filter { it.isLetterOrDigit() || it in "._" }

    fun appInfo(pkg: String, display: Int) {
        val p = safe(pkg)
        if (p.isEmpty()) return
        Bg.post { Priv.sh("am start --display $display -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:$p' -f 0x10000000") }
    }

    fun uninstall(pkg: String, display: Int) {
        val p = safe(pkg)
        if (p.isEmpty()) return
        Bg.post { Priv.sh("am start --display $display -a android.intent.action.DELETE -d 'package:$p' -f 0x10000000") }
    }

    fun toggleFavorite(ctx: Context, pkg: String): Boolean {
        LauncherStore.toggleFavorite(ctx, pkg)
        return LauncherStore.isFavorite(ctx, pkg)
    }
}
