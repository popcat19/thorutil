package dev.popcat19.thorutil.ayn

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// Holds AYN as a modifier. AYN never reaches PhoneWindowManager while this runs,
// so a lone tap is re-sent through the daemon's gpio-keys clone (vendor 0x2020),
// which this filter lets through. Pad Home is a second modifier: Home+L1/R1 sends
// Home to one screen without moving pad focus; a lone Home is re-sent on release.
class AynKeyService : AccessibilityService() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var held = false
    private var combo = false
    private var homeHeld = false
    private var homeCombo = false
    private var backHeld = false
    private var backCombo = false
    private val swallowed = HashSet<Int>()
    @Volatile private var stepping = false

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ayn-actions").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (isRealAyn(e)) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { held = true; combo = false }
                KeyEvent.ACTION_UP -> {
                    held = false
                    if (!combo) worker.post { AynActions.tap(applicationContext) }
                }
            }
            return true
        }
        if (isPadHome(e)) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { homeHeld = true; homeCombo = false }
                KeyEvent.ACTION_UP -> {
                    homeHeld = false
                    if (!homeCombo) worker.post { AynActions.home(applicationContext, null) }
                }
            }
            return true
        }
        if (isPadBack(e)) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { backHeld = true; backCombo = false }
                KeyEvent.ACTION_UP -> {
                    backHeld = false
                    if (!backCombo) worker.post { AynActions.back(applicationContext) }
                }
            }
            return true
        }

        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && homeHeld) {
            val d = AynBinds.HOME[code] ?: return false
            homeCombo = true
            swallowed.add(code)
            worker.post { AynActions.home(applicationContext, d) }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && backHeld) {
            val action = AynBinds.BACK[code] ?: return false
            backCombo = true
            swallowed.add(code)
            worker.post { AynActions.run(applicationContext, action) }
            return true
        }

        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && held) {
            val action = AynBinds.DEFAULT[code] ?: return false
            combo = true
            swallowed.add(code)
            worker.post { AynActions.run(applicationContext, action) }
            return true
        }
        // Holding AYN+Vol keeps stepping brightness.
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount > 0 && held && code in swallowed) {
            val action = AynBinds.DEFAULT[code]
            if (action == AynAction.BRIGHTNESS_UP || action == AynAction.BRIGHTNESS_DOWN) {
                if (!stepping) {
                    stepping = true
                    worker.post { AynActions.run(applicationContext, action); stepping = false }
                }
            }
            return true
        }
        if (code in swallowed) {
            if (e.action == KeyEvent.ACTION_UP) swallowed.remove(code)
            return true
        }
        return false
    }

    private fun isRealAyn(e: KeyEvent): Boolean {
        if (e.scanCode != AYN_SCAN) return false
        val dev = InputDevice.getDevice(e.deviceId) ?: return false
        return dev.vendorId == GPIO_VENDOR
    }

    // gpio-keys (real or the re-send clone) also maps to HOME; only the pad counts.
    private fun isPadHome(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_HOME || e.scanCode == AYN_SCAN) return false
        val dev = InputDevice.getDevice(e.deviceId) ?: return false
        return dev.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }

    // B also maps to BACK; only recognize the pad's real Back key (scan 158), not B (305).
    private fun isPadBack(e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_BACK || e.scanCode != 158) return false
        val dev = InputDevice.getDevice(e.deviceId) ?: return false
        return dev.supportsSource(InputDevice.SOURCE_GAMEPAD)
    }



    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val AYN_SCAN = 194
        const val GPIO_VENDOR = 0x0001
    }
}
