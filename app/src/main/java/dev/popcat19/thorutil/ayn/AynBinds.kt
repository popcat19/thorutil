package dev.popcat19.thorutil.ayn

import android.content.Context
import android.view.KeyEvent
import dev.popcat19.thorutil.Prefs

enum class AynAction(val label: String) {
    FOCUS_TOP("Focus top"),
    FOCUS_BOTTOM("Focus bottom"),
    FOCUS_SWAP("Swap focus"),
    SWAP_WINDOWS("Swap windows"),
    PREV_APP("Previous app (target)"),
    PREV_APP_TOP("Previous app (top)"),
    PREV_APP_BOTTOM("Previous app (bottom)"),
    RECENTS_TOGGLE("Recents toggle"),
    SCREEN_LOCK("Lock screen"),
    MOUSE("Mouse mode"),
    BRIGHTNESS_UP("Brightness up (both)"),
    BRIGHTNESS_DOWN("Brightness down (both)"),
}

object AynBinds {
    val DEFAULT = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynAction.FOCUS_TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynAction.FOCUS_BOTTOM,
        KeyEvent.KEYCODE_BUTTON_SELECT to AynAction.FOCUS_SWAP,
        KeyEvent.KEYCODE_BUTTON_START to AynAction.SWAP_WINDOWS,
        KeyEvent.KEYCODE_BUTTON_B to AynAction.RECENTS_TOGGLE,
        KeyEvent.KEYCODE_BUTTON_X to AynAction.MOUSE,
        KeyEvent.KEYCODE_VOLUME_UP to AynAction.BRIGHTNESS_UP,
        KeyEvent.KEYCODE_VOLUME_DOWN to AynAction.BRIGHTNESS_DOWN,
    )

    // Back + key combos (legacy); freed when Back 2x/hold gestures are enabled.
    val BACK = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynAction.PREV_APP_TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynAction.PREV_APP_BOTTOM,
    )

    // Pad Home + key -> display that goes home.
    val HOME = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynActions.TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynActions.BOTTOM,
    )

    fun aynBinds(ctx: Context): Map<Int, AynAction> {
        val m = LinkedHashMap<Int, AynAction>()
        if (Prefs.aynFocusCombos(ctx)) {
            m[KeyEvent.KEYCODE_BUTTON_L1] = AynAction.FOCUS_TOP
            m[KeyEvent.KEYCODE_BUTTON_R1] = AynAction.FOCUS_BOTTOM
            m[KeyEvent.KEYCODE_BUTTON_SELECT] = AynAction.FOCUS_SWAP
        }
        if (!Prefs.aynBackGestures(ctx)) {
            m[KeyEvent.KEYCODE_BUTTON_START] = AynAction.SWAP_WINDOWS
        }
        if (!Prefs.aynHomeDoubleRecents(ctx)) {
            m[KeyEvent.KEYCODE_BUTTON_B] = AynAction.RECENTS_TOGGLE
        }
        if (Prefs.aynScreenLock(ctx)) {
            m[KeyEvent.KEYCODE_BUTTON_Y] = AynAction.SCREEN_LOCK
        }
        m[KeyEvent.KEYCODE_BUTTON_X] = AynAction.MOUSE
        m[KeyEvent.KEYCODE_VOLUME_UP] = AynAction.BRIGHTNESS_UP
        m[KeyEvent.KEYCODE_VOLUME_DOWN] = AynAction.BRIGHTNESS_DOWN
        return m
    }

    fun backBinds(ctx: Context): Map<Int, AynAction> =
        if (Prefs.aynBackGestures(ctx)) emptyMap() else BACK

    fun label(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_")
}
