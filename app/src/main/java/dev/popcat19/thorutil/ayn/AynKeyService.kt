package dev.popcat19.thorutil.ayn

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

// Holds AYN as a modifier. AYN never reaches PhoneWindowManager while this runs,
// so a lone tap is re-sent through the daemon's gpio-keys clone (vendor 0x2020),
// which this filter lets through.
class AynKeyService : AccessibilityService() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var held = false
    private var combo = false
    private val swallowed = HashSet<Int>()

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
        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && held) {
            val action = AynBinds.DEFAULT[code] ?: return false
            combo = true
            swallowed.add(code)
            worker.post { AynActions.run(applicationContext, action) }
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        const val AYN_SCAN = 194
        const val GPIO_VENDOR = 0x0001
    }
}
