package dev.popcat19.thorutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

// Daemon start is handled by ThorApp once Shizuku is up. This receiver covers two gaps:
// 1. Boot with root but no Shizuku: start the daemon via su.
// 2. Shizuku started after ThorUtil and sent no binder: restart the app so Shizuku sees it start.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeeperService.sync(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.daemonOnBoot(context)) {
            val pending = goAsync()
            Thread {
                val shizuku = shizukuInstalled(context) && waitForShizuku()
                if (!shizuku && !Daemon.state().running) {
                    if (Priv.rootReady()) Daemon.start(context)
                    else if (shizukuInstalled(context)) {
                        Log.i(TAG, "no shizuku binder after boot, restarting process")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
                pending.finish()
            }.start()
        }
    }

    private fun shizukuInstalled(ctx: Context) = try {
        ctx.packageManager.getPackageInfo(SHIZUKU, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun waitForShizuku(): Boolean {
        repeat(10) {
            if (Priv.shizukuReady()) return true
            Thread.sleep(3000)
        }
        return false
    }

    companion object {
        private const val TAG = "ThorUtil/Boot"
        private const val SHIZUKU = "moe.shizuku.privileged.api"
    }
}
