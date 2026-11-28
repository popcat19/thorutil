package dev.popcat19.thorutil.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Priv

data class MediaInfo(
    val title: String,
    val artist: String,
    val app: String,
    val playing: Boolean,
)

// Reads the top active media session and drives its transport controls.
object LauncherMedia {
    fun listenerEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    // Android 13 blocks the listener toggle for sideloaded apps, so grant through the privileged shell first.
    fun openListenerSettings(ctx: Context) {
        val app = ctx.applicationContext
        Bg.post {
            val comp = ComponentName(app, MediaListenerService::class.java).flattenToString()
                .filter { it.isLetterOrDigit() || it in "._/$" }
            val r = Priv.sh("cmd notification allow_listener '$comp'")
            if (r.code != 0 || !listenerEnabled(app)) {
                runCatching {
                    app.startActivity(
                        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
        }
    }

    private fun controller(ctx: Context): MediaController? {
        if (!listenerEnabled(ctx)) return null
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = runCatching {
            msm.getActiveSessions(ComponentName(ctx, MediaListenerService::class.java))
        }.getOrNull() ?: return null
        return sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
    }

    fun info(ctx: Context): MediaInfo? {
        val c = controller(ctx) ?: return null
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return null
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val pm = ctx.packageManager
        val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }
            .getOrDefault(c.packageName)
        return MediaInfo(title, artist, app, c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    fun playPause(ctx: Context) {
        val c = controller(ctx) ?: return
        val t = c.transportControls
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
    }

    fun next(ctx: Context) {
        controller(ctx)?.transportControls?.skipToNext()
    }

    fun previous(ctx: Context) {
        controller(ctx)?.transportControls?.skipToPrevious()
    }
}
