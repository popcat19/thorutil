package dev.popcat19.thorutil.recents

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.Prefs
import dev.popcat19.thorutil.R
import dev.popcat19.thorutil.Rotation
import dev.popcat19.thorutil.ayn.AynActions
import dev.popcat19.thorutil.feedback.Feedback
import dev.popcat19.thorutil.feedback.FeedbackType
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

enum class RecentsTab(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
    ALL("All"),
}

object RecentsOverlay {
    private const val TAG = "ThorUtil/Recents"
    private const val REPEAT_DELAY_MS = 280L
    private const val REPEAT_MS = 90L
    private const val CLEAR_CONFIRM_MS = 2_500L

    private val main = Handler(Looper.getMainLooper())
    private val sessionSeq = AtomicLong(0L)

    @Volatile
    private var openFlag = false

    // Main-thread only state.
    private var appCtx: Context? = null
    private var wm: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var scrollView: ScrollView? = null
    private var listContainer: LinearLayout? = null
    private var tabViews: Map<RecentsTab, TextView> = emptyMap()
    private var clearBtn: TextView? = null
    private var footerView: TextView? = null
    private var rowViews: List<View> = emptyList()

    private var overlayDisplay = AynActions.TOP
    private var activeTab = RecentsTab.TOP
    private var allTasks: List<RecentsTask> = emptyList()
    private var shownTasks: List<RecentsTask> = emptyList()
    private var selectedIndex = 0
    private var lockedPkgs: Set<String> = emptySet()
    private var clearConfirmPending = false
    private var repeatKey = 0
    private var repeatTask: Runnable? = null

    private val clearConfirmTimeout = Runnable {
        if (clearConfirmPending) {
            clearConfirmPending = false
            updateHeaderAndFooter()
        }
    }

    private fun buzz(ctx: Context, value: Boolean) {
        if (Feedback.hapticOn(ctx, FeedbackType.RECENTS)) {
            Feedback.buzz(ctx, Feedback.hapticFor(ctx, value))
        }
    }

    fun isOpen(): Boolean = openFlag

    fun toggle(ctx: Context, display: Int? = null) {
        if (openFlag) {
            dismiss()
        } else {
            open(ctx, display)
        }
    }

    fun open(ctx: Context, display: Int? = null) {
        val app = ctx.applicationContext
        val seq = sessionSeq.incrementAndGet()
        openFlag = true
        Bg.post {
            val locked = AynActions.lockedDisplay()
            val targetDisplay = when {
                locked != null -> if (locked == AynActions.BOTTOM) AynActions.TOP else AynActions.BOTTOM
                display != null -> display
                else -> AynActions.targetDisplay(app)
            }
            val tasks = RecentsTasks.query(app)
            val pkgs = Prefs.recentsLockedPackages(app)
            main.post {
                if (sessionSeq.get() != seq) return@post
                showOnMain(app, targetDisplay, tasks, pkgs)
            }
        }
    }

    fun dismiss() {
        sessionSeq.incrementAndGet()
        openFlag = false
        main.post { removeOnMain() }
    }

    fun refreshIfOpen(ctx: Context) {
        if (!openFlag) return
        val app = ctx.applicationContext
        val seq = sessionSeq.get()
        Bg.post {
            val tasks = RecentsTasks.query(app)
            val pkgs = Prefs.recentsLockedPackages(app)
            main.post {
                if (!openFlag || sessionSeq.get() != seq) return@post
                allTasks = tasks
                lockedPkgs = pkgs
                rebuildList(keepSelection = true)
            }
        }
    }

    fun onDisplayChanged() {
        if (openFlag) dismiss()
    }

    fun onKeyEvent(ctx: Context, e: KeyEvent): Boolean {
        if (!openFlag) return false
        val code = e.keyCode
        if (!isHandledKey(code)) return false
        if (e.action == KeyEvent.ACTION_UP) {
            if (code == repeatKey) stopRepeat()
            return true
        }
        if (e.action != KeyEvent.ACTION_DOWN) return true
        if (e.repeatCount > 0) return true

        val app = ctx.applicationContext
        main.post {
            if (!openFlag) return@post
            handleKeyDownOnMain(app, code)
        }
        return true
    }

    private fun isHandledKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_L1,
        KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BACK,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT,
        KeyEvent.KEYCODE_BUTTON_R2,
        KeyEvent.KEYCODE_BUTTON_THUMBR -> true
        else -> false
    }

    private fun handleKeyDownOnMain(ctx: Context, code: Int) {
        if (code != KeyEvent.KEYCODE_BUTTON_R2 && code != KeyEvent.KEYCODE_BUTTON_THUMBR) {
            cancelClearConfirm()
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1, wrap = true)
                startRepeat(code, -1)
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1, wrap = true)
                startRepeat(code, 1)
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> stepTab(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> stepTab(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                shownTasks.getOrNull(selectedIndex)?.let { triggerOpen(ctx, it) }
            }
            KeyEvent.KEYCODE_BUTTON_X -> {
                shownTasks.getOrNull(selectedIndex)?.let { triggerClose(ctx, it) }
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                shownTasks.getOrNull(selectedIndex)?.let { triggerLock(ctx, it) }
            }
            KeyEvent.KEYCODE_BUTTON_START -> {
                shownTasks.getOrNull(selectedIndex)?.let { triggerMove(ctx, it) }
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> {
                shownTasks.getOrNull(selectedIndex)?.let { triggerAppInfo(ctx, it) }
            }
            KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_THUMBR -> {
                if (clearConfirmPending) {
                    cancelClearConfirm()
                    triggerClearAll(ctx)
                } else if (clearableCount() > 0) {
                    clearConfirmPending = true
                    main.removeCallbacks(clearConfirmTimeout)
                    main.postDelayed(clearConfirmTimeout, CLEAR_CONFIRM_MS)
                    buzz(ctx, false)
                    updateHeaderAndFooter()
                } else {
                    Feedback.show(ctx, FeedbackType.RECENTS, "Nothing to clear", false, overlayDisplay)
                }
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> dismiss()
        }
    }

    private fun startRepeat(code: Int, delta: Int) {
        stopRepeat()
        repeatKey = code
        val r = object : Runnable {
            override fun run() {
                if (!openFlag || repeatKey != code) return stopRepeat()
                moveSelection(delta, wrap = false)
                main.postDelayed(this, REPEAT_MS)
            }
        }
        repeatTask = r
        main.postDelayed(r, REPEAT_DELAY_MS)
    }

    private fun stopRepeat() {
        repeatTask?.let { main.removeCallbacks(it) }
        repeatTask = null
        repeatKey = 0
    }

    private fun cancelClearConfirm() {
        if (!clearConfirmPending) return
        clearConfirmPending = false
        main.removeCallbacks(clearConfirmTimeout)
        updateHeaderAndFooter()
    }

    private fun showOnMain(
        app: Context,
        displayId: Int,
        tasks: List<RecentsTask>,
        pkgs: Set<String>,
    ) {
        removeOnMain()

        val a11y = Feedback.a11y
        val base = a11y ?: app.takeIf { Rotation.canOverlay(it) }
        if (base == null) {
            openFlag = false
            Log.w(TAG, "cannot show recents overlay: neither a11y service nor overlay permission")
            return
        }
        val type = if (a11y != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        if (d == null) {
            openFlag = false
            return
        }
        val wctx = base.createDisplayContext(d).createWindowContext(type, null)
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        appCtx = app
        overlayDisplay = displayId
        activeTab = if (displayId == AynActions.BOTTOM) RecentsTab.BOTTOM else RecentsTab.TOP
        allTasks = tasks
        lockedPkgs = pkgs
        selectedIndex = 0
        clearConfirmPending = false

        val scrim = FrameLayout(wctx).apply {
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            isFocusable = a11y == null
            isFocusableInTouchMode = a11y == null
            setOnClickListener { dismiss() }
            if (a11y == null) {
                setOnKeyListener { _, _, event -> onKeyEvent(app, event) }
            }
        }

        val panelW = min((dm.widthPixels * 0.92f).toInt(), dp(540))
        val maxListH = (min((dm.heightPixels * 0.86f).toInt(), dp(620)) - dp(124)).coerceAtLeast(dp(160))

        val panel = LinearLayout(wctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            background = GradientDrawable().apply {
                setColor(0xF2181A1F.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = FrameLayout.LayoutParams(
                panelW,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }

        // Header: tabs on left, Clear all + Close on right.
        val header = LinearLayout(wctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }

        val tabsRow = LinearLayout(wctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val builtTabs = LinkedHashMap<RecentsTab, TextView>()
        for (tab in RecentsTab.entries) {
            val tv = TextView(wctx).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10), dp(6), dp(10), dp(6))
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener {
                    cancelClearConfirm()
                    if (activeTab != tab) {
                        activeTab = tab
                        selectedIndex = 0
                        rebuildList(keepSelection = false)
                    }
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) }
            }
            builtTabs[tab] = tv
            tabsRow.addView(tv)
        }
        header.addView(tabsRow)

        val clearView = TextView(wctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            maxLines = 1
            setOnClickListener {
                cancelClearConfirm()
                triggerClearAll(app)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(6) }
        }
        header.addView(clearView)

        val closeOverlayBtn = ImageButton(wctx).apply {
            setImageResource(R.drawable.ic_pip_close)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x24FFFFFF)
            }
            val p = dp(6)
            setPadding(p, p, p, p)
            contentDescription = "Close recents"
            setOnClickListener { dismiss() }
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
        }
        header.addView(closeOverlayBtn)
        panel.addView(header)

        val listBox = LinearLayout(wctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val scroller = object : ScrollView(wctx) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxListH, MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(listBox)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        panel.addView(scroller)

        val footer = TextView(wctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(0x99FFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(8), dp(4), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        panel.addView(footer)

        scrim.addView(panel)

        val flags = if (a11y != null) {
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        } else {
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            title = "ThorUtil recents"
        }

        val manager = wctx.getSystemService(WindowManager::class.java)
        runCatching {
            manager.addView(scrim, lp)
            wm = manager
            rootView = scrim
            scrollView = scroller
            listContainer = listBox
            tabViews = builtTabs
            clearBtn = clearView
            footerView = footer
            openFlag = true
            rebuildList(keepSelection = false)
            if (a11y == null) scrim.requestFocus()
            buzz(app, true)
        }.onFailure {
            openFlag = false
            Log.w(TAG, "failed to add recents overlay", it)
        }
    }

    private fun removeOnMain() {
        stopRepeat()
        main.removeCallbacks(clearConfirmTimeout)
        clearConfirmPending = false
        val v = rootView
        val manager = wm
        rootView = null
        wm = null
        scrollView = null
        listContainer = null
        tabViews = emptyMap()
        clearBtn = null
        footerView = null
        rowViews = emptyList()
        allTasks = emptyList()
        shownTasks = emptyList()
        if (v != null && manager != null) {
            runCatching { manager.removeView(v) }
        }
    }

    private fun tasksForTab(tab: RecentsTab): List<RecentsTask> = when (tab) {
        RecentsTab.TOP -> allTasks.filter { it.display == AynActions.TOP }
        RecentsTab.BOTTOM -> allTasks.filter { it.display == AynActions.BOTTOM }
        RecentsTab.ALL -> allTasks
    }

    private fun clearableCount(): Int {
        val lockedDisp = AynActions.lockedDisplay()
        return shownTasks.count { t ->
            (lockedDisp == null || t.display != lockedDisp) && t.pkg !in lockedPkgs
        }
    }

    private fun stepTab(delta: Int) {
        val tabs = RecentsTab.entries
        val next = (tabs.indexOf(activeTab) + delta + tabs.size) % tabs.size
        activeTab = tabs[next]
        selectedIndex = 0
        rebuildList(keepSelection = false)
    }

    private fun moveSelection(delta: Int, wrap: Boolean) {
        val n = shownTasks.size
        if (n <= 1) return
        val next = if (wrap) {
            (selectedIndex + delta + n) % n
        } else {
            (selectedIndex + delta).coerceIn(0, n - 1)
        }
        if (next == selectedIndex) return
        selectedIndex = next
        updateRowSelectionStyles()
        scrollToSelected()
    }

    private fun rebuildList(keepSelection: Boolean) {
        val container = listContainer ?: return
        val app = appCtx ?: return
        val wctx = container.context
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()

        shownTasks = tasksForTab(activeTab)
        selectedIndex = if (shownTasks.isEmpty()) {
            0
        } else if (keepSelection) {
            selectedIndex.coerceIn(0, shownTasks.lastIndex)
        } else {
            0
        }

        container.removeAllViews()
        val builtRows = ArrayList<View>(shownTasks.size)
        val screenLocked = AynActions.lockedDisplay()

        if (shownTasks.isEmpty()) {
            val empty = TextView(wctx).apply {
                text = when (activeTab) {
                    RecentsTab.TOP -> "No open apps on top screen"
                    RecentsTab.BOTTOM -> "No open apps on bottom screen"
                    RecentsTab.ALL -> "No open apps"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(0x99FFFFFF.toInt())
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            }
            container.addView(empty)
        } else {
            shownTasks.forEachIndexed { idx, task ->
                val isAppLocked = task.pkg in lockedPkgs
                val isOnLockedScreen = screenLocked != null && task.display == screenLocked
                val moveBlocked = screenLocked != null
                val closeBlocked = isAppLocked || isOnLockedScreen

                val row = LinearLayout(wctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(56)
                    setPadding(dp(10), dp(8), dp(8), dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        if (idx > 0) topMargin = dp(6)
                    }
                    setOnClickListener {
                        cancelClearConfirm()
                        selectedIndex = idx
                        triggerOpen(app, task)
                    }
                }

                val iconView = ImageView(wctx).apply {
                    if (task.icon != null) {
                        setImageDrawable(task.icon)
                    } else {
                        setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                        marginEnd = dp(10)
                    }
                }
                row.addView(iconView)

                val textCol = LinearLayout(wctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(6)
                    }
                }
                val titleTv = TextView(wctx).apply {
                    text = task.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(0xFFFFFFFF.toInt())
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                val metaParts = ArrayList<String>(4)
                metaParts.add(if (task.display == AynActions.BOTTOM) "Bottom" else "Top")
                if (task.visible) metaParts.add("Active")
                if (isAppLocked) metaParts.add("Locked")
                if (isOnLockedScreen) metaParts.add("Screen locked")
                val metaColor = when {
                    isAppLocked || isOnLockedScreen -> 0xFFFBBF24.toInt()
                    task.visible -> 0xFF4ADE80.toInt()
                    else -> 0x99FFFFFF.toInt()
                }
                val subTv = TextView(wctx).apply {
                    text = metaParts.joinToString(" · ")
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                    setTextColor(metaColor)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
                textCol.addView(titleTv)
                textCol.addView(subTv)
                row.addView(textCol)

                fun actionBtn(
                    iconRes: Int,
                    desc: String,
                    tint: Int,
                    bg: Int,
                    alphaVal: Float = 1f,
                    onClick: () -> Unit,
                ): ImageButton = ImageButton(wctx).apply {
                    setImageResource(iconRes)
                    imageTintList = ColorStateList.valueOf(tint)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(bg)
                    }
                    val p = dp(6)
                    setPadding(p, p, p, p)
                    alpha = alphaVal
                    contentDescription = desc
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                        marginStart = dp(4)
                    }
                    setOnClickListener {
                        cancelClearConfirm()
                        selectedIndex = idx
                        onClick()
                    }
                }

                row.addView(
                    actionBtn(
                        iconRes = if (isAppLocked) R.drawable.ic_recents_lock else R.drawable.ic_recents_unlock,
                        desc = if (isAppLocked) "Unlock app" else "Lock app",
                        tint = if (isAppLocked) 0xFFFBBF24.toInt() else 0xCCFFFFFF.toInt(),
                        bg = if (isAppLocked) 0x33FBBF24 else 0x1EFFFFFF,
                    ) { triggerLock(app, task) }
                )
                row.addView(
                    actionBtn(
                        iconRes = R.drawable.ic_recents_move,
                        desc = "Move screen",
                        tint = 0xE6FFFFFF.toInt(),
                        bg = 0x1EFFFFFF,
                        alphaVal = if (moveBlocked) 0.3f else 1f,
                    ) { triggerMove(app, task) }
                )
                row.addView(
                    actionBtn(
                        iconRes = R.drawable.ic_recents_info,
                        desc = "App info",
                        tint = 0xE6FFFFFF.toInt(),
                        bg = 0x1EFFFFFF,
                    ) { triggerAppInfo(app, task) }
                )
                row.addView(
                    actionBtn(
                        iconRes = R.drawable.ic_pip_close,
                        desc = "Close app",
                        tint = 0xFFF87171.toInt(),
                        bg = 0x26EF4444,
                        alphaVal = if (closeBlocked) 0.25f else 1f,
                    ) { triggerClose(app, task) }
                )

                builtRows.add(row)
                container.addView(row)
            }
        }

        rowViews = builtRows
        updateRowSelectionStyles()
        updateHeaderAndFooter()
        scrollToSelected()
    }

    private fun updateRowSelectionStyles() {
        val wctx = listContainer?.context ?: return
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        rowViews.forEachIndexed { idx, row ->
            val selected = idx == selectedIndex
            row.background = GradientDrawable().apply {
                setColor(if (selected) 0x383B82F6 else 0x14FFFFFF)
                cornerRadius = dp(12).toFloat()
                if (selected) setStroke(dp(2), 0xFF60A5FA.toInt())
            }
            val textCol = (row as? LinearLayout)?.getChildAt(1) as? LinearLayout
            val titleTv = textCol?.getChildAt(0) as? TextView
            titleTv?.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun updateHeaderAndFooter() {
        val wctx = rootView?.context ?: return
        val dm = wctx.resources.displayMetrics
        fun dp(v: Int) = (v * dm.density).toInt()
        val screenLocked = AynActions.lockedDisplay()

        val topCount = allTasks.count { it.display == AynActions.TOP }
        val bottomCount = allTasks.count { it.display == AynActions.BOTTOM }
        val allCount = allTasks.size

        for ((tab, tv) in tabViews) {
            val count = when (tab) {
                RecentsTab.TOP -> topCount
                RecentsTab.BOTTOM -> bottomCount
                RecentsTab.ALL -> allCount
            }
            val lockTag = when {
                tab == RecentsTab.TOP && screenLocked == AynActions.TOP -> " [L]"
                tab == RecentsTab.BOTTOM && screenLocked == AynActions.BOTTOM -> " [L]"
                else -> ""
            }
            val selected = tab == activeTab
            tv.text = "${tab.label} ($count)$lockTag"
            tv.setTextColor(if (selected) Color.WHITE else 0xCCFFFFFF.toInt())
            tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            tv.background = GradientDrawable().apply {
                setColor(if (selected) 0xFF3B82F6.toInt() else 0x22FFFFFF)
                cornerRadius = dp(10).toFloat()
            }
        }

        clearBtn?.let { btn ->
            val canClear = clearableCount() > 0
            btn.text = if (clearConfirmPending) "Confirm (R3)" else "Clear all"
            btn.alpha = if (canClear || clearConfirmPending) 1f else 0.35f
            btn.background = GradientDrawable().apply {
                setColor(if (clearConfirmPending) 0xCCDC2626.toInt() else 0x38EF4444)
                cornerRadius = dp(10).toFloat()
            }
        }

        footerView?.let { fv ->
            if (clearConfirmPending) {
                fv.text = "Press R3 / R2 again to clear unlocked apps in ${activeTab.label}"
                fv.setTextColor(0xFFFCA5A5.toInt())
            } else {
                fv.text = "A Open · X Close · Y Lock · Start Move · Select Info · L1/R1 Tab · R3 Clear · B Back"
                fv.setTextColor(0x99FFFFFF.toInt())
            }
        }
    }

    private fun scrollToSelected() {
        val scroller = scrollView ?: return
        val row = rowViews.getOrNull(selectedIndex) ?: return
        scroller.post {
            val pad = (12 * scroller.resources.displayMetrics.density).toInt()
            val top = row.top
            val bottom = row.bottom
            val curTop = scroller.scrollY
            val curBottom = curTop + scroller.height
            if (top - pad < curTop) {
                scroller.smoothScrollTo(0, (top - pad).coerceAtLeast(0))
            } else if (bottom + pad > curBottom) {
                scroller.smoothScrollTo(0, (bottom + pad - scroller.height).coerceAtLeast(0))
            }
        }
    }

    private fun triggerOpen(ctx: Context, task: RecentsTask) {
        val locked = AynActions.lockedDisplay()
        if (locked != null && task.display == locked) {
            val name = if (locked == AynActions.BOTTOM) "Bottom" else "Top"
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "$name locked", false, locked)
            return
        }
        dismiss()
        Bg.post { RecentsTasks.open(ctx, task) }
    }

    private fun triggerClose(ctx: Context, task: RecentsTask) {
        val locked = AynActions.lockedDisplay()
        if (locked != null && task.display == locked) {
            val name = if (locked == AynActions.BOTTOM) "Bottom" else "Top"
            Feedback.show(ctx, FeedbackType.SCREEN_LOCK, "$name locked", false, locked)
            return
        }
        if (task.pkg in lockedPkgs) {
            Feedback.show(ctx, FeedbackType.RECENTS, "Locked: ${task.label}", false, overlayDisplay)
            return
        }
        val seq = sessionSeq.get()
        Bg.post {
            if (RecentsTasks.close(ctx, task)) {
                buzz(ctx, false)
            }
            val updated = RecentsTasks.query(ctx)
            val pkgs = Prefs.recentsLockedPackages(ctx)
            main.post {
                if (!openFlag || sessionSeq.get() != seq) return@post
                allTasks = updated
                lockedPkgs = pkgs
                rebuildList(keepSelection = true)
            }
        }
    }

    private fun triggerLock(ctx: Context, task: RecentsTask) {
        val nowLocked = Prefs.toggleRecentsLockedPackage(ctx, task.pkg)
        lockedPkgs = Prefs.recentsLockedPackages(ctx)
        val msg = if (nowLocked) "Locked: ${task.label}" else "Unlocked: ${task.label}"
        Feedback.show(ctx, FeedbackType.RECENTS, msg, nowLocked, overlayDisplay)
        rebuildList(keepSelection = true)
    }

    private fun triggerMove(ctx: Context, task: RecentsTask) {
        val seq = sessionSeq.get()
        Bg.post {
            RecentsTasks.move(ctx, task)
            val updated = RecentsTasks.query(ctx)
            val pkgs = Prefs.recentsLockedPackages(ctx)
            main.post {
                if (!openFlag || sessionSeq.get() != seq) return@post
                allTasks = updated
                lockedPkgs = pkgs
                rebuildList(keepSelection = true)
            }
        }
    }

    private fun triggerAppInfo(ctx: Context, task: RecentsTask) {
        val display = overlayDisplay
        dismiss()
        Bg.post { RecentsTasks.openAppInfo(ctx, task, display) }
    }

    private fun triggerClearAll(ctx: Context) {
        val targets = shownTasks.toList()
        if (targets.isEmpty()) return
        val seq = sessionSeq.get()
        Bg.post {
            val res = RecentsTasks.clearAll(ctx, targets)
            val msg = when {
                res.cleared == 0 && res.skippedScreenLocked > 0 -> "Screen locked"
                res.cleared == 0 && res.skippedLocked > 0 -> "All apps locked"
                res.cleared == 0 -> "Nothing to clear"
                res.skippedLocked > 0 -> "Cleared ${res.cleared} (${res.skippedLocked} locked)"
                else -> "Cleared ${res.cleared}"
            }
            Feedback.show(ctx, FeedbackType.RECENTS, msg, res.cleared > 0, overlayDisplay)
            val updated = RecentsTasks.query(ctx)
            val pkgs = Prefs.recentsLockedPackages(ctx)
            main.post {
                if (!openFlag || sessionSeq.get() != seq) return@post
                allTasks = updated
                lockedPkgs = pkgs
                rebuildList(keepSelection = true)
            }
        }
    }
}
