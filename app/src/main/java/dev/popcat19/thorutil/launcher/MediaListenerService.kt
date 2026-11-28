package dev.popcat19.thorutil.launcher

import android.service.notification.NotificationListenerService

// Intentionally empty: enabling this listener is what authorizes MediaSessionManager.getActiveSessions for the home media row.
class MediaListenerService : NotificationListenerService()
