package dev.popcat19.thorutil

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        PinKeeperService.sync(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.daemonOnBoot(context)) {
            val pending = goAsync()
            Thread {
                // Shizuku may need a moment after boot; the Magisk module covers root-only boots.
                repeat(10) {
                    if (Priv.backend() != Priv.Backend.NONE) {
                        if (!Daemon.state().running) Daemon.start(context)
                        pending.finish()
                        return@Thread
                    }
                    Thread.sleep(2000)
                }
                pending.finish()
            }.start()
        }
    }
}
