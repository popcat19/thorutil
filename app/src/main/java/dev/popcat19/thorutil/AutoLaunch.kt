package dev.popcat19.thorutil

import android.content.Context
import android.content.Intent
import android.util.Log
import dev.popcat19.thorutil.ayn.AynActions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class ManagedApp(
    val pkg: String,
    val label: String,
    val onBoot: Boolean = true,
    val keepAlive: Boolean = true,
)

// Manages background utility apps (equalizers, socks5 proxies, VPNs, etc.):
// applies battery/standby whitelists, launches selected apps at boot then returns home,
// and optionally watches keep-alive apps to restart them if killed by the OS.
object AutoLaunch {
    private const val TAG = "ThorUtil/AutoLaunch"
    private const val RELAUNCH_COOLDOWN_MS = 5 * 60_000L
    private val lastRelaunch = ConcurrentHashMap<String, Long>()

    private fun safePkg(pkg: String): String? {
        val clean = pkg.filter { it.isLetterOrDigit() || it in "._" }
        return clean.takeIf { it.isNotEmpty() && it == pkg }
    }

    fun list(ctx: Context): List<ManagedApp> {
        val arr = runCatching { JSONArray(Prefs.autoLaunchApps(ctx)) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ManagedApp>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("pkg").takeIf { it.isNotBlank() } ?: continue
            out.add(
                ManagedApp(
                    pkg = pkg,
                    label = o.optString("label", pkg),
                    onBoot = o.optBoolean("onBoot", true),
                    keepAlive = o.optBoolean("keepAlive", true),
                )
            )
        }
        return out
    }

    fun save(ctx: Context, apps: List<ManagedApp>) {
        val arr = JSONArray()
        for (a in apps) {
            arr.put(
                JSONObject()
                    .put("pkg", a.pkg)
                    .put("label", a.label)
                    .put("onBoot", a.onBoot)
                    .put("keepAlive", a.keepAlive)
            )
        }
        Prefs.setAutoLaunchApps(ctx, arr.toString())
    }

    fun hasKeepAlive(ctx: Context): Boolean = list(ctx).any { it.keepAlive }

    fun installedLaunchable(ctx: Context): List<Pair<String, String>> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val seen = HashSet<String>()
        val res = ArrayList<Pair<String, String>>()
        for (ri in pm.queryIntentActivities(intent, 0)) {
            val pkg = ri.activityInfo?.packageName ?: continue
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            res.add(pkg to label)
        }
        return res.sortedBy { it.second.lowercase() }
    }

    fun whitelist(pkg: String): Boolean {
        val p = safePkg(pkg) ?: return false
        val cmd = "dumpsys deviceidle whitelist +$p; " +
            "appops set $p RUN_ANY_IN_BACKGROUND allow 2>/dev/null; " +
            "appops set $p RUN_IN_BACKGROUND allow 2>/dev/null; " +
            "am set-standby-bucket $p active 2>/dev/null"
        return Priv.sh(cmd).code == 0
    }

    fun isRunning(pkg: String): Boolean {
        val p = safePkg(pkg) ?: return false
        return Priv.sh("pidof $p").out.trim().isNotEmpty()
    }

    fun launchActivity(ctx: Context, pkg: String): Boolean {
        val p = safePkg(pkg) ?: return false
        val comp = ctx.packageManager.getLaunchIntentForPackage(p)?.component?.flattenToShortString()
        val safeComp = comp?.filter { it.isLetterOrDigit() || it in "._/$" }
        val r = if (!safeComp.isNullOrEmpty() && '/' in safeComp) {
            Priv.sh("am start --display 0 -f 0x10000000 -n '$safeComp'")
        } else {
            Priv.sh("monkey -p $p -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        }
        return r.code == 0
    }

    fun runBootLaunch(ctx: Context) {
        val apps = list(ctx)
        if (apps.isEmpty()) return
        var launchedAny = false
        for (app in apps) {
            whitelist(app.pkg)
            if (!app.onBoot) continue
            if (isRunning(app.pkg)) continue
            Log.i(TAG, "boot launch: ${app.pkg}")
            if (launchActivity(ctx, app.pkg)) {
                launchedAny = true
                lastRelaunch[app.pkg] = System.currentTimeMillis()
                Thread.sleep(1500)
            }
        }
        if (launchedAny) {
            AynActions.home(ctx, AynActions.TOP)
        }
    }

    fun watchdogTick(ctx: Context, interactive: Boolean) {
        val apps = list(ctx).filter { it.keepAlive }
        if (apps.isEmpty()) return
        val safeList = apps.mapNotNull { a -> safePkg(a.pkg)?.let { a to it } }
        if (safeList.isEmpty()) return
        // Single batched shell check for all keep-alive packages.
        val checkCmd = safeList.joinToString("; ") { (_, p) -> "pidof $p >/dev/null 2>&1 && echo $p" } + "; true"
        val running = Priv.sh(checkCmd).out.lines().map { it.trim() }.toSet()
        val now = System.currentTimeMillis()
        for ((app, p) in safeList) {
            if (p in running) continue
            val prev = lastRelaunch[app.pkg] ?: 0L
            if (now - prev < RELAUNCH_COOLDOWN_MS) continue
            lastRelaunch[app.pkg] = now
            whitelist(p)
            // Try silent BOOT_COMPLETED wake first so background services recover without stealing focus.
            Priv.sh("am broadcast -a android.intent.action.BOOT_COMPLETED -p $p >/dev/null 2>&1")
            Thread.sleep(1200)
            if (isRunning(p)) {
                Log.i(TAG, "watchdog: woke $p via broadcast")
                continue
            }
            // Fall back to launching the activity only while screen is off so games are not interrupted.
            if (!interactive) {
                Log.i(TAG, "watchdog: relaunching $p (screen off)")
                if (launchActivity(ctx, p)) {
                    Thread.sleep(1500)
                    AynActions.home(ctx, AynActions.TOP)
                }
            } else {
                Log.i(TAG, "watchdog: $p stopped while interactive; deferred activity relaunch")
            }
        }
    }
}
