package dev.popcat19.thorutil.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Priv

// Per-app actions for the home screen context menu. Launched from the home activity itself
// so the system dialogs open on the same display and carry a real caller package.
object LauncherActions {
    private fun safe(pkg: String) = pkg.filter { it.isLetterOrDigit() || it in "._" }

    fun appInfo(act: Activity, pkg: String) {
        val p = safe(pkg)
        if (p.isEmpty()) return
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$p"))
        if (runCatching { act.startActivity(i) }.isFailure) {
            val d = act.display?.displayId ?: 0
            Bg.post { Priv.sh("am start --display $d -a android.settings.APPLICATION_DETAILS_SETTINGS -d 'package:$p' -f 0x10000000") }
        }
    }

    // ACTION_DELETE needs REQUEST_DELETE_PACKAGES and a real caller; pm uninstall is the privileged fallback.
    @Suppress("DEPRECATION")
    fun uninstall(act: Activity, pkg: String) {
        val p = safe(pkg)
        if (p.isEmpty()) return
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$p"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, false)
        if (runCatching { act.startActivity(i) }.isFailure) {
            Bg.post { Priv.sh("pm uninstall --user 0 $p") }
        }
    }

    fun toggleFavorite(ctx: Context, pkg: String): Boolean {
        LauncherStore.toggleFavorite(ctx, pkg)
        return LauncherStore.isFavorite(ctx, pkg)
    }
}
