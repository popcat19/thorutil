package dev.popcat19.thorutil.ayn

import android.view.KeyEvent

enum class AynAction(val label: String) {
    FOCUS_TOP("Focus top"),
    FOCUS_BOTTOM("Focus bottom"),
    FOCUS_SWAP("Swap focus"),
    SWAP_WINDOWS("Swap windows"),
    PREV_APP_TOP("Previous app (top)"),
    PREV_APP_BOTTOM("Previous app (bottom)"),
    RECENTS_TOGGLE("Recents toggle"),
    MOUSE("Mouse mode"),
}

object AynBinds {
    val DEFAULT = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynAction.FOCUS_TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynAction.FOCUS_BOTTOM,
        KeyEvent.KEYCODE_BUTTON_SELECT to AynAction.FOCUS_SWAP,
        KeyEvent.KEYCODE_BUTTON_START to AynAction.SWAP_WINDOWS,
        KeyEvent.KEYCODE_BUTTON_B to AynAction.RECENTS_TOGGLE,
        KeyEvent.KEYCODE_BUTTON_X to AynAction.MOUSE,
    )

    // Back + key combos; back itself re-sent on lone release, matching AYN/Home behavior.
    val BACK = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynAction.PREV_APP_TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynAction.PREV_APP_BOTTOM,
    )

    // Pad Home + key → display that goes home.
    val HOME = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynActions.TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynActions.BOTTOM,
    )

    fun label(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_BUTTON_")
}
