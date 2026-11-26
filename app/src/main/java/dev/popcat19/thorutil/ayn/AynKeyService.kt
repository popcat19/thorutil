package dev.popcat19.thorutil.ayn

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.feedback.Feedback
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// Holds AYN as a modifier. AYN never reaches PhoneWindowManager while this runs,
// so a lone tap is re-sent through the daemon's gpio-keys clone (vendor 0x2020),
// which this filter lets through. Pad Home supports Home+L1/R1 (and optional Home 2x
// for Recents). Pad Back supports legacy Back+L1/R1 or optional Back 2x (prev app)
// and Back hold (swap windows).
class AynKeyService : AccessibilityService() {
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var held = false
    private var combo = false
    private var homeHeld = false
    private var homeCombo = false
    private var homeTapPending = false
    private var backHeld = false
    private var backCombo = false
    private var backTapPending = false
    private val swallowed = HashSet<Int>()
    private val pendingBrightness = AtomicInteger(0)
    private val brightnessBusy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private var repeatCode = 0
    private var repeatTask: Runnable? = null

    private val brightnessDrain: Runnable = object : Runnable {
        override fun run() {
            try {
                while (true) {
                    val delta = pendingBrightness.getAndSet(0)
                    if (delta == 0) break
                    AynActions.stepBrightness(applicationContext, delta)
                }
            } finally {
                brightnessBusy.set(false)
                if (pendingBrightness.get() != 0 && brightnessBusy.compareAndSet(false, true)) {
                    worker.post(this)
                }
            }
        }
    }

    private fun queueBrightness(action: AynAction, fromRepeat: Boolean = false) {
        // Skip piling up repeat ticks while a step is in flight so release stops immediately.
        if (fromRepeat && (brightnessBusy.get() || pendingBrightness.get() != 0)) return
        pendingBrightness.addAndGet(if (action == AynAction.BRIGHTNESS_UP) 1 else -1)
        if (brightnessBusy.compareAndSet(false, true)) {
            worker.post(brightnessDrain)
        }
    }

    private val homeSingleRun = Runnable {
        homeTapPending = false
        worker.post { AynActions.home(applicationContext, null) }
    }

    private val backSingleRun = Runnable {
        backTapPending = false
        worker.post { AynActions.back(applicationContext) }
    }

    private val backHoldRun = Runnable {
        if (backHeld && !backCombo) {
            backCombo = true
            worker.post { AynActions.run(applicationContext, AynAction.SWAP_WINDOWS) }
        }
    }

    // Accessibility filters see raw down/up only; repeats come later from the dispatcher.
    private fun startRepeat(code: Int, action: AynAction) {
        stopRepeat()
        repeatCode = code
        val task = object : Runnable {
            override fun run() {
                if (!held) return stopRepeat()
                queueBrightness(action, fromRepeat = true)
                main.postDelayed(this, REPEAT_MS)
            }
        }
        repeatTask = task
        main.postDelayed(task, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatTask?.let { main.removeCallbacks(it) }
        repeatTask = null
        repeatCode = 0
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ayn-actions").apply { start() }
        worker = Handler(thread.looper)
    }

    override fun onServiceConnected() {
        Feedback.a11y = this
    }

    override fun onDestroy() {
        main.removeCallbacks(homeSingleRun)
        main.removeCallbacks(backSingleRun)
        main.removeCallbacks(backHoldRun)
        stopRepeat()
        pendingBrightness.set(0)
        if (Feedback.a11y === this) Feedback.a11y = null
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (isRealAyn(e)) {
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) { held = true; combo = false }
                KeyEvent.ACTION_UP -> {
                    held = false
                    stopRepeat()
                    if (!combo) worker.post { AynActions.tap(applicationContext) }
                }
            }
            return true
        }
        if (isPadHome(e)) {
            val dbl = Prefs.aynHomeDoubleRecents(applicationContext)
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                    homeHeld = true
                    if (dbl && homeTapPending) {
                        main.removeCallbacks(homeSingleRun)
                        homeTapPending = false
                        homeCombo = true
                        worker.post { AynActions.run(applicationContext, AynAction.RECENTS_TOGGLE) }
                    } else {
                        homeCombo = false
                    }
                }
                KeyEvent.ACTION_UP -> {
                    homeHeld = false
                    if (!homeCombo) {
                        if (dbl) {
                            homeTapPending = true
                            main.removeCallbacks(homeSingleRun)
                            main.postDelayed(homeSingleRun, DOUBLE_TAP_MS)
                        } else {
                            worker.post { AynActions.home(applicationContext, null) }
                        }
                    }
                }
            }
            return true
        }
        if (isPadBack(e)) {
            val gest = Prefs.aynBackGestures(applicationContext)
            when (e.action) {
                KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) {
                    backHeld = true
                    if (gest && backTapPending) {
                        main.removeCallbacks(backSingleRun)
                        main.removeCallbacks(backHoldRun)
                        backTapPending = false
                        backCombo = true
                        worker.post { AynActions.run(applicationContext, AynAction.PREV_APP) }
                    } else {
                        backCombo = false
                        if (gest) {
                            main.removeCallbacks(backHoldRun)
                            main.postDelayed(backHoldRun, HOLD_MS)
                        }
                    }
                }
                KeyEvent.ACTION_UP -> {
                    backHeld = false
                    main.removeCallbacks(backHoldRun)
                    if (!backCombo) {
                        if (gest) {
                            backTapPending = true
                            main.removeCallbacks(backSingleRun)
                            main.postDelayed(backSingleRun, DOUBLE_TAP_MS)
                        } else {
                            worker.post { AynActions.back(applicationContext) }
                        }
                    }
                }
            }
            return true
        }

        val code = e.keyCode
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && homeHeld) {
            val d = AynBinds.HOME[code] ?: return false
            main.removeCallbacks(homeSingleRun)
            homeTapPending = false
            homeCombo = true
            swallowed.add(code)
            worker.post { AynActions.home(applicationContext, d) }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && backHeld) {
            val action = AynBinds.backBinds(applicationContext)[code] ?: return false
            main.removeCallbacks(backHoldRun)
            main.removeCallbacks(backSingleRun)
            backTapPending = false
            backCombo = true
            swallowed.add(code)
            worker.post { AynActions.run(applicationContext, action) }
            return true
        }

        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && held) {
            val action = AynBinds.aynBinds(applicationContext)[code] ?: return false
            combo = true
            swallowed.add(code)
            if (action in REPEATING) {
                queueBrightness(action)
                startRepeat(code, action)
            } else {
                worker.post { AynActions.run(applicationContext, action) }
            }
            return true
        }
        if (code in swallowed) {
            if (e.action == KeyEvent.ACTION_UP) {
                swallowed.remove(code)
                if (code == repeatCode) stopRepeat()
            }
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
        const val REPEAT_DELAY_MS = 280L
        const val REPEAT_MS = 65L
        const val DOUBLE_TAP_MS = 260L
        const val HOLD_MS = 450L
        val REPEATING = setOf(AynAction.BRIGHTNESS_UP, AynAction.BRIGHTNESS_DOWN)
    }
}
