package dev.popcat19.thorutil

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

// Starts the daemon whenever Shizuku becomes usable: at boot once Shizuku's own
// start-on-boot finishes, after a Shizuku restart, or right after permission is granted.
// ShizukuProvider wakes this process when the server comes up.
class ThorApp : Application() {
    private val onBinder = Shizuku.OnBinderReceivedListener { kick("binder") }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) kick("granted")
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addRequestPermissionResultListener(onPermission)
    }

    private fun kick(why: String) {
        if (!Prefs.daemonOnBoot(this)) return
        Thread {
            if (!Priv.shizukuReady()) return@Thread Unit.also { Log.i(TAG, "$why: shizuku not ready") }
            if (Daemon.state().running) return@Thread Unit.also { Log.i(TAG, "$why: daemon running") }
            Log.i(TAG, "$why: start daemon -> ${Daemon.start(this)}")
        }.start()
    }

    companion object {
        private const val TAG = "ThorUtil/App"
    }
}
