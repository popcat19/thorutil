package dev.popcat19.thorutil.ayn

import android.accessibilityservice.AccessibilityService
import android.hardware.input.InputManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.SparseIntArray
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.recents.RecentsOverlay
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
    private var lastScopeCode = 0
    private var lastScopeAt = 0L
    private var lastBackScopeCode = 0
    private var lastBackScopeAt = 0L
    private var homeCombo = false
    private var homeTapPending = false
    private var backHeld = false
    private var backCombo = false
    private var backTapPending = false
    private var backSwallowedByRecents = false
    private val swallowed = HashSet<Int>()
    private val pendingBrightness = AtomicInteger(0)
    private val brightnessBusy = AtomicBoolean(false)
    private val main = Handler(Looper.getMainLooper())
    private val deviceFlags = SparseIntArray()
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = deviceFlags.delete(id)
        override fun onInputDeviceRemoved(id: Int) = deviceFlags.delete(id)
        override fun onInputDeviceChanged(id: Int) = deviceFlags.delete(id)
    }
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
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(inputListener, main)
    }

    override fun onServiceConnected() {
        Feedback.a11y = this
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(inputListener)
        deviceFlags.clear()
        main.removeCallbacks(homeSingleRun)
        main.removeCallbacks(backSingleRun)
        main.removeCallbacks(backHoldRun)
        stopRepeat()
        pendingBrightness.set(0)
        RecentsOverlay.dismiss()
        if (AynActions.lockedDisplay() != null) {
            val app = applicationContext
            Bg.post { AynActions.unlock(app, notify = false) }
        }
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
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && RecentsOverlay.isOpen()) {
                backSwallowedByRecents = true
                backHeld = false
                backCombo = true
                main.removeCallbacks(backSingleRun)
                main.removeCallbacks(backHoldRun)
                backTapPending = false
                RecentsOverlay.onBack(applicationContext)
                return true
            }
            if (backSwallowedByRecents) {
                if (e.action == KeyEvent.ACTION_UP) backSwallowedByRecents = false
                return true
            }
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
        if (!held && !homeHeld && !backHeld && RecentsOverlay.isOpen()) {
            if (RecentsOverlay.onKeyEvent(applicationContext, e)) {
                if (e.action == KeyEvent.ACTION_DOWN) swallowed.add(code)
                else if (e.action == KeyEvent.ACTION_UP) swallowed.remove(code)
                return true
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && homeHeld) {
            val scoped = Prefs.aynScopedShoulders(applicationContext)
            if (scoped && code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
                main.removeCallbacks(homeSingleRun)
                homeTapPending = false
                homeCombo = true
                swallowed.add(code)
                worker.post { AynActions.run(applicationContext, AynAction.FOCUS_SWAP) }
                return true
            }
            val d = AynBinds.HOME[code] ?: return false
            main.removeCallbacks(homeSingleRun)
            homeTapPending = false
            homeCombo = true
            swallowed.add(code)
            val now = android.os.SystemClock.uptimeMillis()
            val again = scoped && code == lastScopeCode && now - lastScopeAt <= SCOPE_DOUBLE_MS
            lastScopeCode = code
            lastScopeAt = now
            worker.post {
                if (again) AynActions.recentsOn(applicationContext, d) else AynActions.home(applicationContext, d)
            }
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && backHeld) {
            val scoped = Prefs.aynScopedShoulders(applicationContext)
            val scopeDisplay = if (scoped) AynBinds.HOME[code] else null
            if (scopeDisplay != null || (scoped && code == KeyEvent.KEYCODE_BUTTON_THUMBL)) {
                main.removeCallbacks(backHoldRun)
                main.removeCallbacks(backSingleRun)
                backTapPending = false
                backCombo = true
                swallowed.add(code)
                val now = android.os.SystemClock.uptimeMillis()
                val again = scopeDisplay != null && code == lastBackScopeCode && now - lastBackScopeAt <= SCOPE_DOUBLE_MS
                lastBackScopeCode = code
                lastBackScopeAt = now
                worker.post {
                    if (scopeDisplay == null) {
                        AynActions.run(applicationContext, AynAction.FOCUS_SWAP)
                    } else if (again) {
                        AynActions.run(applicationContext, if (scopeDisplay == AynActions.TOP) AynAction.PREV_APP_TOP else AynAction.PREV_APP_BOTTOM)
                    } else {
                        AynActions.back(applicationContext, scopeDisplay)
                    }
                }
                return true
            }
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
                RecentsOverlay.onKeyUp(code)
            }
            return true
        }
        return false
    }

    private fun flagsFor(deviceId: Int): Int {
        val idx = deviceFlags.indexOfKey(deviceId)
        if (idx >= 0) return deviceFlags.valueAt(idx)
        val dev = InputDevice.getDevice(deviceId) ?: return 0
        var flags = 0
        if (dev.vendorId == GPIO_VENDOR) flags = flags or DEV_GPIO
        if (dev.supportsSource(InputDevice.SOURCE_GAMEPAD)) flags = flags or DEV_GAMEPAD
        deviceFlags.put(deviceId, flags)
        return flags
    }

    private fun isRealAyn(e: KeyEvent): Boolean =
        e.scanCode == AYN_SCAN && (flagsFor(e.deviceId) and DEV_GPIO) != 0

    // gpio-keys (real or the re-send clone) also maps to HOME; only the pad counts.
    private fun isPadHome(e: KeyEvent): Boolean =
        e.keyCode == KeyEvent.KEYCODE_HOME && e.scanCode != AYN_SCAN && (flagsFor(e.deviceId) and DEV_GAMEPAD) != 0

    // B also maps to BACK; only recognize the pad's real Back key (scan 158), not B (305).
    private fun isPadBack(e: KeyEvent): Boolean =
        e.keyCode == KeyEvent.KEYCODE_BACK && e.scanCode == 158 && (flagsFor(e.deviceId) and DEV_GAMEPAD) != 0

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    companion object {
        private const val DEV_GPIO = 1
        private const val DEV_GAMEPAD = 2
        const val AYN_SCAN = 194
        const val GPIO_VENDOR = 0x0001
        const val REPEAT_DELAY_MS = 280L
        const val REPEAT_MS = 65L
        const val DOUBLE_TAP_MS = 260L
        const val HOLD_MS = 450L
        const val SCOPE_DOUBLE_MS = 600L
        val REPEATING = setOf(AynAction.BRIGHTNESS_UP, AynAction.BRIGHTNESS_DOWN)
    }
}
