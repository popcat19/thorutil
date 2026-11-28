package dev.popcat19.thorutil.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.Priv

// Enable/disable is fully revertable: enabling records the current default Home, disabling
// restores it (set-home-activity), disables both ThorUtil home activities and returns Home.
object LauncherSwitch {
    private const val TAG = "ThorUtil/Launcher"

    private fun components(ctx: Context) = listOf(
        ComponentName(ctx, LauncherActivity::class.java),
        ComponentName(ctx, LauncherSecondaryActivity::class.java),
    )

    fun previousHome(ctx: Context): String? = Prefs.launcherPrevHome(ctx).takeIf { it.isNotBlank() }

    fun set(ctx: Context, on: Boolean) {
        val app = ctx.applicationContext
        if (on && previousHome(app) == null) currentHome(app)?.let { Prefs.setLauncherPrevHome(app, it) }
        Prefs.setLauncherHome(app, on)
        if (on) {
            applyComponents(app, true)
        } else {
            Bg.post { restore(app) }
        }
    }

    // Keeps component state matching the pref after import / changelog apply.
    fun sync(ctx: Context) {
        val app = ctx.applicationContext
        val want = Prefs.launcherHome(app)
        val have = components(app).all { enabledState(app, it) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED }
        if (want == have) return
        set(app, want)
    }

    private fun restore(app: Context) {
        val prev = previousHome(app)?.filter { it.isLetterOrDigit() || it in "._/$" }
        if (!prev.isNullOrEmpty()) {
            val r = Priv.sh("cmd package set-home-activity --user 0 '$prev'")
            Log.i(TAG, "restore home $prev -> ${r.code} ${r.out.trim()}")
        }
        applyComponents(app, false)
        Prefs.setLauncherPrevHome(app, "")
        Priv.sh("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.HOME -f 0x10200000")
    }

    private fun applyComponents(app: Context, on: Boolean) {
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (c in components(app)) {
            runCatching { app.packageManager.setComponentEnabledSetting(c, state, PackageManager.DONT_KILL_APP) }
        }
    }

    private fun enabledState(app: Context, c: ComponentName) = app.packageManager.getComponentEnabledSetting(c)

    private fun currentHome(app: Context): String? {
        val ai = runCatching {
            app.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo
        }.getOrNull() ?: return null
        if (ai.packageName == "android" || ai.packageName == app.packageName) return null
        return "${ai.packageName}/${ai.name}"
    }
}
