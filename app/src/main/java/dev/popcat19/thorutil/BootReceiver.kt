package dev.popcat19.thorutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Daemon start is handled by ThorApp once Shizuku is up; this covers root-only setups.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        KeeperService.sync(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.daemonOnBoot(context)) {
            val pending = goAsync()
            Thread {
                if (!Priv.shizukuReady() && Priv.rootReady() && !Daemon.state().running) Daemon.start(context)
                pending.finish()
            }.start()
        }
    }
}
