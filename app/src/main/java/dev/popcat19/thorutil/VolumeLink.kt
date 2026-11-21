package dev.popcat19.thorutil

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlin.math.roundToInt

// Odin drives the bottom speaker from these system settings (0..15), separate from STREAM_MUSIC.
object VolumeLink {
    private const val TAG = "ThorUtil/Volume"
    const val KEY = "secondary_screen_volume_level"
    const val KEY_HEADPHONES = "secondary_screen_volume_level_for_headphones"
    const val MAX = 15
    const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
    const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private var last = ""

    private fun headphones(am: AudioManager) = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
        it.type in setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
        )
    }

    fun target(ctx: Context): Pair<String, Int> {
        val am = ctx.getSystemService(AudioManager::class.java)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val level = if (v == 0) 0 else ((v * MAX.toFloat() / max).roundToInt() + Prefs.volumeOffset(ctx)).coerceIn(0, MAX)
        return (if (headphones(am)) KEY_HEADPHONES else KEY) to level
    }

    // Worker thread.
    fun sync(ctx: Context, force: Boolean = false) {
        if (!Prefs.volumeLink(ctx)) return
        val (key, level) = target(ctx)
        val sig = "$key $level"
        if (!force && sig == last) return
        val ok = Priv.sh("settings put system $KEY $level; settings put system $KEY_HEADPHONES $level").code == 0
        if (ok) last = sig
        Log.i(TAG, "$KEY=$level $KEY_HEADPHONES=$level ${if (ok) "ok" else "failed"}")
    }
}
