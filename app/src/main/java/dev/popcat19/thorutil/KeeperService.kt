package dev.popcat19.thorutil

import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log

// Long-lived helper. Pin: BetterDSA and the stock dual-screen assistant reset ime_fixed
// around screen off/on and at boot, so re-apply it. Lock: release the pad from mouse
// mode while the keyguard is up so the gamepad can enter a PIN. Rotation: hosts the
// orientation overlay, which only lives as long as this process, and the rotate hint.
class KeeperService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var observer: ContentObserver? = null
    private var hint: RotationHint? = null
    private val restore = Runnable { enforce("debounce") }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val action = i.action ?: ""
            handler.post { syncLock(action) }
            if (action == Intent.ACTION_SCREEN_OFF) hint?.stop() else syncHint()
            // The assistants write their reset shortly after these broadcasts.
            for (delay in longArrayOf(400, 1500, 4000)) handler.postDelayed({ enforce(action) }, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pin-keeper").apply { start() }
        handler = Handler(thread.looper)
        startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.removeCallbacks(restore)
                handler.postDelayed(restore, 250)
            }
        }.also {
            contentResolver.registerContentObserver(Settings.System.getUriFor(PinMode.IME_FIXED), false, it)
            contentResolver.registerContentObserver(Settings.System.getUriFor(PinMode.IME_SHOW_ON_SECOND), false, it)
        }
        registerReceiver(screenRx, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
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
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
        // Boot: Shizuku can come up after us, so retry a few times.
        for (delay in longArrayOf(5000, 15000, 30000)) handler.postDelayed({ enforce("boot") }, delay)
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
                ).joinToString(" · ")
            )
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Rotation.remove()
        hint?.stop()
        observer?.let { contentResolver.unregisterContentObserver(it) }
        unregisterReceiver(screenRx)
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThorUtil/Keeper"
        private const val CHANNEL = "pin_keeper"
        private const val NOTIF_ID = 1

        private fun needed(ctx: Context) = Prefs.pinManaged(ctx) || Prefs.lockSuspend(ctx) ||
            Prefs.rotation(ctx) != RotationMode.AUTO || Prefs.rotationHint(ctx)

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
