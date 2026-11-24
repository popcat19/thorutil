package dev.popcat19.thorutil

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType

// Long-lived helper. Pin: BetterDSA and the stock dual-screen assistant reset ime_fixed
// around screen off/on and at boot, so re-apply it (and rebind stale WMS IME targets).
// Lock: release the pad from mouse mode while the keyguard is up so the gamepad can enter a PIN.
// Rotation: hosts the orientation overlay and rotate hint. Volume: mirror media volume to bottom.
// Fan / Perf / AutoLaunch: keep native daemons and keep-alive background apps healthy.
class KeeperService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var observer: ContentObserver? = null
    private var secondResumeObserver: ContentObserver? = null
    private var fanObserver: ContentObserver? = null
    private var hint: RotationHint? = null
    private val restore = Runnable { enforce("debounce") }
    private val imeRebind = Runnable { rebindIme("trigger") }
    private val fanModeChange = Runnable { FanDaemon.onModeChanged(this) }
    private var lastRot = ""
    private var watchTick = 0
    @Volatile
    private var lastRebindAt = 0L

    // Stick and D-pad motion in mouse mode follow each screen's rotation.
    private val displays = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) = syncRotation()
    }

    private fun syncRotation() {
        val dm = getSystemService(DisplayManager::class.java)
        val top = dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        val bottom = dm.getDisplay(Rotation.BOTTOM_DISPLAY)?.rotation ?: return
        val key = "$top $bottom"
        if (key == lastRot) return
        val hadPrev = lastRot.isNotEmpty()
        lastRot = key
        handler.post { Daemon.rotation(top, bottom) }
        if (hadPrev && Prefs.imeRebindFix(this)) {
            handler.removeCallbacks(imeRebind)
            handler.postDelayed(imeRebind, 600)
            handler.postDelayed({ rebindIme("rotation-settle") }, 1500)
        }
    }

    private fun rebindIme(why: String) {
        if (!Prefs.imeRebindFix(this)) return
        val now = System.currentTimeMillis()
        if (now - lastRebindAt < 400) return
        if (PinMode.current(this) == PinMode.OFF) return
        lastRebindAt = now
        val ok = PinMode.rebindIme(this)
        Log.i(TAG, "ime rebind ($why) -> $ok")
    }

    private val volumeSync = Runnable { VolumeLink.sync(this) }

    private val daemonWatch = object : Runnable {
        override fun run() {
            val nativeDir = applicationInfo.nativeLibraryDir
            val upgraded = Prefs.lastNativeDir(this@KeeperService) != nativeDir && Priv.backend() != Priv.Backend.NONE
            if (upgraded) {
                Prefs.setLastNativeDir(this@KeeperService, nativeDir)
                if (Prefs.daemonOnBoot(this@KeeperService) && !Daemon.stopped) {
                    Log.i(TAG, "upgrade: restart mouse -> ${Daemon.restart(this@KeeperService)}")
                }
                if (Prefs.fanManaged(this@KeeperService) && !FanDaemon.stopped) {
                    Log.i(TAG, "upgrade: restart fan -> ${FanDaemon.restart(this@KeeperService)}")
                }
                if (Prefs.perfAdaptive(this@KeeperService) && !PerfDaemon.stopped) {
                    Log.i(TAG, "upgrade: restart perf -> ${PerfDaemon.restart(this@KeeperService)}")
                }
            } else {
                if (Prefs.daemonOnBoot(this@KeeperService) && !Daemon.stopped && !Daemon.state().running) {
                    val ok = Daemon.start(this@KeeperService)
                    Log.i(TAG, "daemon watchdog: start -> $ok")
                }
                if (Prefs.fanManaged(this@KeeperService) && !FanDaemon.stopped && !FanDaemon.state().running) {
                    val ok = FanDaemon.start(this@KeeperService)
                    Log.i(TAG, "fan watchdog: start -> $ok")
                }
                if (Prefs.perfAdaptive(this@KeeperService) && !PerfDaemon.stopped && !PerfDaemon.state().running) {
                    val ok = PerfDaemon.start(this@KeeperService)
                    Log.i(TAG, "perf watchdog: start -> $ok")
                }
            }
            if (++watchTick % 3 == 0 && AutoLaunch.hasKeepAlive(this@KeeperService)) {
                val interactive = getSystemService(PowerManager::class.java).isInteractive
                AutoLaunch.watchdogTick(this@KeeperService, interactive)
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private val volumeRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == VolumeLink.ACTION &&
                i.getIntExtra(VolumeLink.EXTRA_STREAM, -1) != android.media.AudioManager.STREAM_MUSIC) return
            handler.removeCallbacks(volumeSync)
            handler.postDelayed(volumeSync, 80)
        }
    }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val action = i.action ?: ""
            if (action == Intent.ACTION_SHUTDOWN) {
                handler.post {
                    FanDaemon.onShutdown(this@KeeperService)
                    PerfDaemon.onShutdown(this@KeeperService)
                }
                return
            }
            val off = action == Intent.ACTION_SCREEN_OFF
            handler.post { syncLock(action) }
            if (Prefs.fanManaged(this@KeeperService)) handler.post { FanDaemon.setScreenOff(off) }
            if (Prefs.perfAdaptive(this@KeeperService)) handler.post { PerfDaemon.setScreenOff(off) }
            if (off) hint?.stop() else syncHint()
            // The assistants write their reset shortly after these broadcasts.
            for (delay in longArrayOf(400, 1500, 4000)) handler.postDelayed({ enforce(action) }, delay)
            if (!off && Prefs.imeRebindFix(this@KeeperService)) {
                handler.postDelayed({ rebindIme(action) }, 600)
                handler.postDelayed({ rebindIme("$action-late") }, 2000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pin-keeper").apply { start() }
        handler = Handler(thread.looper)
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        getSystemService(DisplayManager::class.java).registerDisplayListener(displays, handler)
        syncRotation()

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(restore)
                handler.postDelayed(restore, 250)
            }
        }.also {
            contentResolver.registerContentObserver(Settings.System.getUriFor(PinMode.IME_FIXED), false, it)
            contentResolver.registerContentObserver(Settings.System.getUriFor(PinMode.IME_SHOW_ON_SECOND), false, it)
        }
        secondResumeObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!Prefs.imeRebindFix(this@KeeperService)) return
                handler.removeCallbacks(imeRebind)
                handler.postDelayed(imeRebind, 500)
            }
        }.also {
            contentResolver.registerContentObserver(Settings.System.getUriFor(PinMode.SECOND_RESUME), false, it)
        }
        fanObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(fanModeChange)
                handler.postDelayed(fanModeChange, 150)
            }
        }.also {
            contentResolver.registerContentObserver(Settings.System.getUriFor(FanDaemon.MODE_KEY), false, it)
        }
        registerReceiver(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SHUTDOWN)
        })
        registerReceiver(volumeRx, IntentFilter().apply {
            addAction(VolumeLink.ACTION)
            addAction(android.media.AudioManager.ACTION_HEADSET_PLUG)
            addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!needed(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { enforce("start"); syncLock("start") }
        val rot = Prefs.rotation(this)
        Rotation.apply(this, rot)
        handler.post { Rotation.syncForceLandscape(this, rot) }
        syncHint()
        handler.post { VolumeLink.sync(this, force = true) }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        // Boot: Shizuku can come up after us, so retry a few times.
        for (delay in longArrayOf(5000, 15000, 30000)) handler.postDelayed({ enforce("boot") }, delay)
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        if (Prefs.fanManaged(this)) handler.post { FanDaemon.setScreenOff(!interactive) }
        if (Prefs.perfAdaptive(this)) handler.post { PerfDaemon.setScreenOff(!interactive) }
        // Daemon watchdog: restart it if it dies (or once after an APK upgrade).
        handler.removeCallbacks(daemonWatch)
        handler.post(daemonWatch)
        return START_STICKY
    }

    private fun enforce(why: String) {
        if (!Prefs.pinManaged(this)) return
        val want = Prefs.pinMode(this)
        val have = PinMode.current(this)
        if (want == have) return
        val ok = PinMode.apply(want)
        Log.i(TAG, "$why: $have -> $want ${if (ok) "ok" else "failed (${Priv.backend()})"}")
    }

    // Main thread.
    private fun syncHint() {
        if (!Prefs.rotationHint(this)) {
            hint?.stop()
            hint = null
            return
        }
        val h = hint ?: RotationHint(this) { mode ->
            Prefs.setRotation(this, mode)
            sync(applicationContext)
            Feedback.show(this, FeedbackType.ROTATION, "Rotation: ${mode.label}", mode == RotationMode.AUTO)
        }.also { hint = it }
        h.start()
    }

    private fun syncLock(why: String) {
        val locked = Prefs.lockSuspend(this) && (why == Intent.ACTION_SCREEN_OFF ||
            (why != Intent.ACTION_USER_PRESENT && getSystemService(KeyguardManager::class.java).isKeyguardLocked))
        if (Daemon.setSuspended(locked)) Log.i(TAG, "$why: mouse ${if (locked) "suspended" else "resumed"}")
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Keeper", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(
                listOfNotNull(
                    if (Prefs.pinManaged(this)) "IME pin: ${Prefs.pinMode(this).label}" else null,
                    if (Prefs.lockSuspend(this)) "Mouse off on lockscreen" else null,
                    Prefs.rotation(this).takeIf { it != RotationMode.AUTO }?.let { "Top: ${it.label}" },
                    if (Prefs.perfAdaptive(this)) "Adaptive perf" else null,
                ).joinToString(" · ").ifEmpty { "ThorUtil Keeper" }
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Rotation.remove()
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displays)
        hint?.stop()
        observer?.let { contentResolver.unregisterContentObserver(it) }
        secondResumeObserver?.let { contentResolver.unregisterContentObserver(it) }
        fanObserver?.let { contentResolver.unregisterContentObserver(it) }
        unregisterReceiver(screenRx)
        unregisterReceiver(volumeRx)
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThorUtil/Keeper"
        private const val CHANNEL = "pin_keeper"
        private const val NOTIF_ID = 1

        private fun needed(ctx: Context) = Prefs.pinManaged(ctx) || Prefs.imeRebindFix(ctx) ||
            Prefs.lockSuspend(ctx) || Prefs.rotation(ctx) != RotationMode.AUTO ||
            Prefs.rotationHint(ctx) || Prefs.volumeLink(ctx) || Prefs.daemonOnBoot(ctx) ||
            Prefs.fanManaged(ctx) || Prefs.perfAdaptive(ctx) || AutoLaunch.hasKeepAlive(ctx)

        fun sync(ctx: Context) {
            val i = Intent(ctx, KeeperService::class.java)
            val app = ctx.applicationContext
            if (Prefs.rotation(app) == RotationMode.AUTO) {
                Thread { Rotation.syncForceLandscape(app, RotationMode.AUTO) }.start()
            }
            if (needed(ctx)) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }
}
