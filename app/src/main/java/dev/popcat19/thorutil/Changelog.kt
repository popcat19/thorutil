package dev.popcat19.thorutil

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONObject

// Declarative preference changelog. Every new behavior pref ships as mkDefault (preserving
// legacy behavior for downstream users). Each PrefChange has a stable id and integer revision:
// once reviewed, seen[id] = revision is stored so the user's choice is remembered across updates
// unless that pref's revision is bumped for a new change/feature.
data class PrefChange(
    val id: String,
    val revision: Int,
    val version: String,
    val title: String,
    val summary: String,
    val legacyLabel: String,
    val newLabel: String,
    val isNew: (Context) -> Boolean,
    val applyNew: (Context) -> Unit,
    val applyLegacy: (Context) -> Unit,
)

object Changelog {
    const val EXTRA_OPEN_CHANGELOG = "open_changelog"
    private const val CHANNEL = "pref_changelog"
    private const val NOTIF_ID = 2

    val changes: List<PrefChange> = listOf(
        PrefChange(
            id = "ime_rebind_fix",
            revision = 1,
            version = "0.4.7",
            title = "Auto-rebind IME after rotation & bottom use",
            summary = "Refreshes Odin's cached IME window target on rotation, unlock, and bottom-screen resume so the keyboard keeps appearing.",
            legacyLabel = "Legacy: off",
            newLabel = "New: auto-rebind",
            isNew = { Prefs.imeRebindFix(it) },
            applyNew = { Prefs.setImeRebindFix(it, true) },
            applyLegacy = { Prefs.setImeRebindFix(it, false) },
        ),
        PrefChange(
            id = "rotation_reverse_in_cycle",
            revision = 1,
            version = "0.4.7",
            title = "Reverse portrait in rotation tile & hint",
            summary = "Adds upside-down portrait (right-handed grip) to the Quick Settings tile cycle and rotation hint. Always available in the app card.",
            legacyLabel = "Legacy: Auto / Portrait / Landscape",
            newLabel = "New: include Reverse Portrait",
            isNew = { Prefs.rotationReverseInCycle(it) },
            applyNew = { Prefs.setRotationReverseInCycle(it, true) },
            applyLegacy = { Prefs.setRotationReverseInCycle(it, false) },
        ),
        PrefChange(
            id = "mouse_rotate_dpad",
            revision = 1,
            version = "0.4.7",
            title = "Rotate D-pad in portrait (mouse mode)",
            summary = "Rotates D-pad axes and keys to match display orientation while mouse mode is active; games outside mouse mode stay untouched.",
            legacyLabel = "Legacy: unrotated D-pad",
            newLabel = "New: rotate D-pad in mouse mode",
            isNew = { Prefs.mouseRotateDpad(it) },
            applyNew = { Prefs.setMouseRotateDpad(it, true) },
            applyLegacy = { Prefs.setMouseRotateDpad(it, false) },
        ),
        PrefChange(
            id = "ayn_focus_combos",
            revision = 1,
            version = "0.4.7",
            title = "Disable AYN focus-switch combos",
            summary = "Stops AYN+L1/R1/Select from switching focus so combos cannot accidentally unfocus a game.",
            legacyLabel = "Legacy: AYN+L1/R1/Select switch focus",
            newLabel = "New: focus combos disabled",
            isNew = { !Prefs.aynFocusCombos(it) },
            applyNew = { Prefs.setAynFocusCombos(it, false) },
            applyLegacy = { Prefs.setAynFocusCombos(it, true) },
        ),
        PrefChange(
            id = "ayn_focus_moves_pad",
            revision = 1,
            version = "0.4.7",
            title = "Mouse-only focus binding",
            summary = "When focus combos are used, move only the mouse pointer and leave gamepad screen_focus_lock untouched.",
            legacyLabel = "Legacy: moves gamepad + mouse",
            newLabel = "New: mouse pointer only",
            isNew = { !Prefs.aynFocusMovesPad(it) },
            applyNew = { Prefs.setAynFocusMovesPad(it, false) },
            applyLegacy = { Prefs.setAynFocusMovesPad(it, true) },
        ),
        PrefChange(
            id = "ayn_home_skip_bdsa",
            revision = 1,
            version = "0.4.7",
            title = "Home skips AYN/BDSA overlay",
            summary = "Lone Home targets the top screen directly when BDSA/DSA is open on the bottom screen instead of clearing BDSA first.",
            legacyLabel = "Legacy: targets last touched display",
            newLabel = "New: route lone Home to top over BDSA",
            isNew = { Prefs.aynHomeSkipBdsa(it) },
            applyNew = { Prefs.setAynHomeSkipBdsa(it, true) },
            applyLegacy = { Prefs.setAynHomeSkipBdsa(it, false) },
        ),
        PrefChange(
            id = "ayn_home_double_recents",
            revision = 1,
            version = "0.4.7",
            title = "Home 2x toggles Recents",
            summary = "Moves Recents toggle from AYN+B to double-pressing Home, freeing AYN+B.",
            legacyLabel = "Legacy: AYN+B",
            newLabel = "New: Home 2x (frees AYN+B)",
            isNew = { Prefs.aynHomeDoubleRecents(it) },
            applyNew = { Prefs.setAynHomeDoubleRecents(it, true) },
            applyLegacy = { Prefs.setAynHomeDoubleRecents(it, false) },
        ),
        PrefChange(
            id = "ayn_back_gestures",
            revision = 1,
            version = "0.4.7",
            title = "Back 2x = Prev app · Back hold = Swap",
            summary = "Moves Previous app to double-pressing Back (target screen) and Swap windows to holding Back, freeing Back+L1/R1 and AYN+Start.",
            legacyLabel = "Legacy: Back+L1/R1 & AYN+Start",
            newLabel = "New: Back 2x & Back hold",
            isNew = { Prefs.aynBackGestures(it) },
            applyNew = { Prefs.setAynBackGestures(it, true) },
            applyLegacy = { Prefs.setAynBackGestures(it, false) },
        ),
        PrefChange(
            id = "fan_zero_battery",
            revision = 1,
            version = "0.4.7",
            title = "0% fan on battery when cool",
            summary = "Allows the fan to stop off charger below 44 °C soak (resuming at 48 °C or immediately when plugged in).",
            legacyLabel = "Legacy: minimum curve floor (20%)",
            newLabel = "New: 0% fan on battery when cool",
            isNew = { Prefs.fanZeroBattery(it) },
            applyNew = { Prefs.setFanZeroBattery(it, true) },
            applyLegacy = { Prefs.setFanZeroBattery(it, false) },
        ),
        PrefChange(
            id = "perf_adaptive",
            revision = 1,
            version = "0.4.7",
            title = "Adaptive Odin performance profile",
            summary = "Scales Odin performance_mode (Normal / Performance / High) from live CPU and GPU load without throttling peak clocks.",
            legacyLabel = "Legacy: Odin manual mode",
            newLabel = "New: adaptive Odin profile",
            isNew = { Prefs.perfAdaptive(it) },
            applyNew = { Prefs.setPerfAdaptive(it, true) },
            applyLegacy = { Prefs.setPerfAdaptive(it, false) },
        ),
    )

    fun seenMap(ctx: Context): MutableMap<String, Int> {
        val obj = runCatching { JSONObject(Prefs.changelogSeen(ctx)) }.getOrNull() ?: return mutableMapOf()
        val out = mutableMapOf<String, Int>()
        for (k in obj.keys()) out[k] = obj.optInt(k, 0)
        return out
    }

    fun pending(ctx: Context): List<PrefChange> {
        val seen = seenMap(ctx)
        return changes.filter { (seen[it.id] ?: 0) < it.revision }
    }

    fun markSeen(ctx: Context, items: List<PrefChange>) {
        val seen = seenMap(ctx)
        for (c in items) seen[c.id] = c.revision
        val obj = JSONObject()
        for ((k, v) in seen) obj.put(k, v)
        Prefs.setChangelogSeen(ctx, obj.toString())
        if (pending(ctx).isEmpty()) cancelNotification(ctx)
    }

    fun notifyIfPending(ctx: Context) {
        val list = pending(ctx)
        if (list.isEmpty()) return
        val app = ctx.applicationContext
        Bg.post {
            if (app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                Priv.sh("pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS")
            }
            val nm = app.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Preference changelog", NotificationManager.IMPORTANCE_DEFAULT)
            )
            val open = PendingIntent.getActivity(
                app,
                NOTIF_ID,
                Intent(app, MainActivity::class.java)
                    .putExtra(EXTRA_OPEN_CHANGELOG, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notif = Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_keyboard)
                .setContentTitle("ThorUtil: ${list.size} new preference options")
                .setContentText("Tap to review and cherry-pick new defaults without changing existing habits.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIF_ID, notif) }
        }
    }

    fun cancelNotification(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID) }
    }

    fun syncAfterApply(ctx: Context) {
        val app = ctx.applicationContext
        Bg.post {
            if (Prefs.daemonOnBoot(app) && Daemon.state().running) Daemon.restart(app)
            if (Prefs.fanManaged(app) && !FanDaemon.stopped) FanDaemon.restart(app)
            if (Prefs.perfAdaptive(app)) {
                if (!PerfDaemon.state().running) PerfDaemon.start(app)
            } else if (PerfDaemon.state().running) {
                PerfDaemon.stop(app)
            }
            KeeperService.sync(app)
        }
    }
}
