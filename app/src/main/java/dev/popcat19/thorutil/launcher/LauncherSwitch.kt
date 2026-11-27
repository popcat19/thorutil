package dev.popcat19.thorutil.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import dev.popcat19.thorutil.Prefs

object LauncherSwitch {
    private fun component(ctx: Context) = ComponentName(ctx, LauncherActivity::class.java)

    fun isComponentEnabled(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(component(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    // Enabling only makes ThorUtil selectable in the system Home picker; the user still chooses the default.
    fun set(ctx: Context, on: Boolean) {
        Prefs.setLauncherHome(ctx, on)
        sync(ctx)
    }

    fun sync(ctx: Context) {
        val state = if (Prefs.launcherHome(ctx)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            ctx.packageManager.setComponentEnabledSetting(component(ctx), state, PackageManager.DONT_KILL_APP)
        }
    }
}
