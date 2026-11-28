package dev.popcat19.thorutil

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku

// Starts daemons whenever Shizuku becomes usable: at boot once Shizuku's own
// start-on-boot finishes, after a Shizuku restart, or right after permission is granted.
// ShizukuProvider wakes this process when the server comes up.
class ThorApp : Application() {
    private val onBinder = Shizuku.OnBinderReceivedListener { kick("binder") }
    private val onBinderDead = Shizuku.OnBinderDeadListener { Priv.onShizukuDead() }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) kick("granted")
    }

    override fun onCreate() {
        super.onCreate()
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermission)
        if (Prefs.launcherHome(this)) dev.popcat19.thorutil.launcher.LauncherSwitch.sync(this)
    }

    private fun kick(why: String) {
        if (!Prefs.daemonOnBoot(this) && !Prefs.fanManaged(this) && !Prefs.perfAdaptive(this)) return
        Bg.post {
            if (!Priv.shizukuReady()) {
                Log.i(TAG, "$why: shizuku not ready")
                return@post
            }
            if (Prefs.daemonOnBoot(this) && !Daemon.state().running) {
                Log.i(TAG, "$why: start daemon -> ${Daemon.start(this)}")
            }
            if (Prefs.fanManaged(this) && !FanDaemon.stopped && !FanDaemon.state().running) {
                Log.i(TAG, "$why: start fan -> ${FanDaemon.start(this)}")
            }
            if (Prefs.perfAdaptive(this) && !PerfDaemon.stopped && !PerfDaemon.state().running) {
                Log.i(TAG, "$why: start perf -> ${PerfDaemon.start(this)}")
            }
        }
    }

    companion object {
        private const val TAG = "ThorUtil/App"
    }
}
