package dev.popcat19.thorutil.feedback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Daemon events, sent with `am broadcast -p`. Guarded by DUMP, which shell and root hold.
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getStringExtra("event")) {
            "mouse_on" -> Feedback.show(ctx, FeedbackType.MOUSE, "Mouse on", true)
            "mouse_on_bottom" -> Feedback.show(ctx, FeedbackType.MOUSE, "Mouse on: bottom", true, 4)
            "mouse_off" -> Feedback.show(ctx, FeedbackType.MOUSE, "Mouse off", false)
            "pointer_top" -> Feedback.show(ctx, FeedbackType.POINTER, "Pointer: top", true, 0)
            "pointer_bottom" -> Feedback.show(ctx, FeedbackType.POINTER, "Pointer: bottom", false, 4)
        }
    }
}
