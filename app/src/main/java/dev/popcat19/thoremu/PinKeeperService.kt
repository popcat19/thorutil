package dev.popcat19.thoremu

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
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log

// Keeps ime_fixed / ime_show_on_second at the user's choice. BetterDSA and the stock
// dual-screen assistant reset the pin around screen off/on and at boot; this re-applies it.
class PinKeeperService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var observer: ContentObserver? = null
    private val restore = Runnable { enforce("debounce") }

    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // The assistants write their reset shortly after these broadcasts.
            for (delay in longArrayOf(400, 1500, 4000)) handler.postDelayed({ enforce(i.action ?: "") }, delay)
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
        if (!Prefs.pinManaged(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.post { enforce("start") }
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

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "IME pin keeper", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle("Keeping IME pin: ${Prefs.pinMode(this).label}")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.let { contentResolver.unregisterContentObserver(it) }
        unregisterReceiver(screenRx)
        thread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ThorEmu/PinKeeper"
        private const val CHANNEL = "pin_keeper"
        private const val NOTIF_ID = 1

        fun sync(ctx: Context) {
            val i = Intent(ctx, PinKeeperService::class.java)
            if (Prefs.pinManaged(ctx)) ctx.startForegroundService(i) else ctx.stopService(i)
        }
    }
}
