package dev.popcat19.thorutil.ayn

import android.view.KeyEvent

enum class AynAction(val label: String) {
    FOCUS_TOP("Focus top"),
    FOCUS_BOTTOM("Focus bottom"),
    FOCUS_SWAP("Swap focus"),
    SWAP_WINDOWS("Swap windows"),
    PREV_APP("Previous app"),
    MOUSE("Mouse mode"),
}

object AynBinds {
    val DEFAULT = mapOf(
        KeyEvent.KEYCODE_BUTTON_L1 to AynAction.FOCUS_TOP,
        KeyEvent.KEYCODE_BUTTON_R1 to AynAction.FOCUS_BOTTOM,
        KeyEvent.KEYCODE_BUTTON_SELECT to AynAction.FOCUS_SWAP,
        KeyEvent.KEYCODE_BUTTON_START to AynAction.SWAP_WINDOWS,
        KeyEvent.KEYCODE_BUTTON_B to AynAction.PREV_APP,
        KeyEvent.KEYCODE_BUTTON_X to AynAction.MOUSE,
    )

    fun label(code: Int) = KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_BUTTON_")
}
