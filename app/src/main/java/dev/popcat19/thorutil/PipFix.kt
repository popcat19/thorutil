package dev.popcat19.thorutil

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout

// Android 13 SystemUI hardcodes display 0 for pip_input_consumer and PipMenuView
// (AOSP b/113087003), leaving PiP windows on display 4 deaf to touch. Two workarounds:
// OVERLAY places a touchable control overlay over bottom PiP; MOVE_TOP relocates bottom
// PiP to display 0 so native drag, resize, and menu work.
enum class PipFixMode(val id: Int, val label: String, val shortLabel: String) {
    OFF(0, "Off", "Off"),
    OVERLAY(1, "Bottom overlay", "Overlay"),
    MOVE_TOP(2, "Move to top", "Move top");

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: OFF
    }
}

object PipFix {
    private const val TAG = "ThorUtil/PipFix"
    private const val POLL_IDLE_MS = 2_000L
    private const val POLL_ACTIVE_MS = 750L
    private const val MENU_TIMEOUT_MS = 3_500L
    private const val MENU_INTERACT_MS = 2_500L
    private const val DOUBLE_TAP_MS = 280L
    private const val MOVE_COOLDOWN_MS = 2_000L

    private val HEAD = Regex("""^RootTask id=(\d+) bounds=\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\] displayId=(\d+)""")
    private val TOP_ACT = Regex("""topActivity=ComponentInfo\{([^}]+)\}""")
    private val CHILD_COMP = Regex("""^\s+taskId=\d+: (\S+/\S+)""")

    data class PipTask(val rootId: Int, val displayId: Int, val bounds: Rect, val component: String)

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var worker: Handler? = null

    @Volatile
    private var running = false

    @Volatile
    private var interactive = true

    @Volatile
    private var bottomPipActive = false

    private var lastMovedRoot = -1
    private var lastMovedAt = 0L

    // Main-thread only overlay state.
    private var overlayView: FrameLayout? = null
    private var controlsView: FrameLayout? = null
    private var shownRootId = -1
    private var shownBounds: Rect? = null
    private var shownComponent = ""
    private var lastTapAt = 0L

    private val hideControls = Runnable {
        controlsView?.visibility = View.GONE
    }

    private val pollRun: Runnable = object : Runnable {
        override fun run() {
            if (!running || !interactive) return
            val ctx = appCtx ?: return
            val mode = Prefs.pipFixMode(ctx)
            if (mode == PipFixMode.OFF) {
                bottomPipActive = false
                main.post { removeOverlay() }
                return
            }
            tick(ctx, mode)
            if (running && interactive) {
                val delay = if (bottomPipActive) POLL_ACTIVE_MS else POLL_IDLE_MS
                worker?.postDelayed(this, delay)
            }
        }
    }

    fun sync(ctx: Context, handler: Handler, isInteractive: Boolean) {
        val app = ctx.applicationContext
        appCtx = app
        worker = handler
        interactive = isInteractive
        val mode = Prefs.pipFixMode(app)
        handler.removeCallbacks(pollRun)
        if (mode == PipFixMode.OFF || !isInteractive) {
            running = mode != PipFixMode.OFF
            bottomPipActive = false
            main.post { removeOverlay() }
            return
        }
        running = true
        if (mode != PipFixMode.OVERLAY) {
            main.post { removeOverlay() }
        }
        handler.post(pollRun)
    }

    fun stop() {
        running = false
        bottomPipActive = false
        worker?.removeCallbacks(pollRun)
        main.post { removeOverlay() }
    }

    // Schedules a quick check after Home/Back/focus/rotation transitions.
    fun nudge(delayMs: Long = 250L) {
        if (!running || !interactive) return
        val h = worker ?: return
        h.removeCallbacks(pollRun)
        h.postDelayed(pollRun, delayMs)
    }

    fun query(): PipTask? {
        val out = Priv.sh("cmd activity stack info 2 1").out.trim()
        if (out.isEmpty() || out == "null") return null
        var rootId = -1
        var displayId = -1
        var bounds: Rect? = null
        var visible = false
        var component: String? = null
        for (line in out.lines()) {
            HEAD.find(line)?.let { m ->
                rootId = m.groupValues[1].toInt()
                bounds = Rect(
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(),
                )
                displayId = m.groupValues[6].toInt()
                return@let
            }
            if (line.contains("visible=true")) visible = true
            TOP_ACT.find(line)?.let { component = it.groupValues[1] }
            if (component == null) {
                CHILD_COMP.find(line)?.let { component = it.groupValues[1] }
            }
        }
        val rect = bounds ?: return null
        if (rootId < 0 || !visible || rect.width() <= 20 || rect.height() <= 20) return null
        val comp = component?.filter { it.isLetterOrDigit() || it in "._/$" }?.takeIf { '/' in it } ?: ""
        return PipTask(rootId, displayId, rect, comp)
    }

    private fun tick(ctx: Context, mode: PipFixMode) {
        val pip = query()
        if (pip == null || pip.displayId != Rotation.BOTTOM_DISPLAY) {
            bottomPipActive = false
            main.post { removeOverlay() }
            return
        }
        bottomPipActive = true
        when (mode) {
            PipFixMode.OFF -> main.post { removeOverlay() }
            PipFixMode.MOVE_TOP -> {
                main.post { removeOverlay() }
                val now = SystemClock.uptimeMillis()
                if (pip.rootId != lastMovedRoot || now - lastMovedAt >= MOVE_COOLDOWN_MS) {
                    lastMovedRoot = pip.rootId
                    lastMovedAt = now
                    Log.i(TAG, "moving bottom PiP root=${pip.rootId} (${pip.component}) -> top")
                    moveToTop(pip.rootId)
                }
            }
            PipFixMode.OVERLAY -> main.post { syncOverlay(ctx, pip) }
        }
    }

    private fun layoutParams(bounds: Rect): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

    // Main thread.
    private fun syncOverlay(ctx: Context, pip: PipTask) {
        if (!Rotation.canOverlay(ctx)) {
            removeOverlay()
            return
        }
        val existing = overlayView
        if (existing != null) {
            shownRootId = pip.rootId
            shownComponent = pip.component
            if (shownBounds != pip.bounds) {
                shownBounds = Rect(pip.bounds)
                runCatching {
                    existing.context.getSystemService(WindowManager::class.java)
                        .updateViewLayout(existing, layoutParams(pip.bounds))
                }
            }
            return
        }

        val display = ctx.getSystemService(DisplayManager::class.java)
            .getDisplay(Rotation.BOTTOM_DISPLAY) ?: return
        val wctx = ctx.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val dm = wctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * dm).toInt()

        val root = FrameLayout(wctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val controls = FrameLayout(wctx).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0x99000000.toInt())
                cornerRadius = dp(12).toFloat()
            }
        }

        fun iconBtn(iconRes: Int, desc: String, sizeDp: Int, onClick: () -> Unit): ImageButton =
            ImageButton(wctx).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setBackgroundResource(R.drawable.bg_hint)
                val pad = dp(6)
                setPadding(pad, pad, pad, pad)
                contentDescription = desc
                layoutParams = FrameLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
                setOnClickListener { onClick() }
            }

        val btnTop = iconBtn(R.drawable.ic_pip_top, "Move PiP to top screen", 34) {
            val id = shownRootId
            removeOverlay()
            if (id >= 0) Bg.post {
                moveToTop(id)
                nudge(400)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(6), dp(6), 0, 0)
            }
        }

        val btnClose = iconBtn(R.drawable.ic_pip_close, "Close PiP", 34) {
            val id = shownRootId
            removeOverlay()
            if (id >= 0) Bg.post {
                close(id)
                nudge(400)
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            }
        }

        val centerRow = LinearLayout(wctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            addView(
                iconBtn(R.drawable.ic_pip_play_pause, "Play or pause", 40) {
                    togglePlayPause(ctx)
                    scheduleHide(MENU_INTERACT_MS)
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                        marginEnd = dp(12)
                    }
                }
            )
            addView(
                iconBtn(R.drawable.ic_pip_expand, "Expand PiP", 40) {
                    val comp = shownComponent
                    removeOverlay()
                    if (comp.isNotEmpty()) Bg.post {
                        expand(comp)
                        nudge(400)
                    }
                }.apply {
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                }
            )
        }

        controls.addView(btnTop)
        controls.addView(btnClose)
        controls.addView(centerRow)
        root.addView(
            controls,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )

        root.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            val shown = controls.visibility == View.VISIBLE
            if (shown && now - lastTapAt <= DOUBLE_TAP_MS) {
                val comp = shownComponent
                removeOverlay()
                if (comp.isNotEmpty()) Bg.post {
                    expand(comp)
                    nudge(400)
                }
                return@setOnClickListener
            }
            lastTapAt = now
            if (shown) {
                main.removeCallbacks(hideControls)
                controls.visibility = View.GONE
            } else {
                controls.visibility = View.VISIBLE
                scheduleHide(MENU_TIMEOUT_MS)
            }
        }

        runCatching {
            wctx.getSystemService(WindowManager::class.java).addView(root, layoutParams(pip.bounds))
            overlayView = root
            controlsView = controls
            shownRootId = pip.rootId
            shownBounds = Rect(pip.bounds)
            shownComponent = pip.component
            lastTapAt = 0L
        }.onFailure {
            Log.w(TAG, "failed to add bottom PiP overlay", it)
        }
    }

    private fun scheduleHide(delayMs: Long) {
        main.removeCallbacks(hideControls)
        main.postDelayed(hideControls, delayMs)
    }

    // Main thread.
    private fun removeOverlay() {
        main.removeCallbacks(hideControls)
        val v = overlayView ?: return
        overlayView = null
        controlsView = null
        shownRootId = -1
        shownBounds = null
        shownComponent = ""
        runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) }
    }

    private fun togglePlayPause(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val now = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
        am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 0))
    }

    private fun expand(component: String) {
        val r = Priv.sh("cmd activity start-activity --display ${Rotation.BOTTOM_DISPLAY} --windowingMode 1 -f 0x30020000 -n '$component'")
        if (r.code != 0 || r.out.contains("Error:")) {
            Priv.sh("cmd activity start-activity --display ${Rotation.BOTTOM_DISPLAY} -f 0x10200000 -n '$component'")
        }
    }

    private fun moveToTop(rootId: Int) {
        Priv.sh("cmd activity display move-stack $rootId ${Display.DEFAULT_DISPLAY}")
    }

    private fun close(rootId: Int) {
        Priv.sh("cmd activity stack remove $rootId")
    }
}
