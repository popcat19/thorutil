package dev.popcat19.thorutil.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import dev.popcat19.thorutil.Bg
import dev.popcat19.thorutil.recents.ActionPrompt
import dev.popcat19.thorutil.recents.PromptFlowLayout
import dev.popcat19.thorutil.recents.PromptGlyph
import dev.popcat19.thorutil.recents.RecentsPrompts

open class LauncherActivity : Activity() {
    private var all: List<LauncherApp> = emptyList()
    private var shown: List<LauncherApp> = emptyList()
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private var display = 0
    @Volatile private var imeUp = false
    private var favApps: List<LauncherApp> = emptyList()
    private lateinit var favRow: LinearLayout
    private lateinit var favScroll: HorizontalScrollView

    companion object {
        private val live = java.util.concurrent.ConcurrentHashMap<Int, LauncherActivity>()

        // True when the home on that display had the keyboard up and closed it, so Back must not propagate.
        fun consumeBack(display: Int): Boolean {
            val a = live[display] ?: return false
            if (!a.imeUp) return false
            a.runOnUiThread { a.dismissIme() }
            return true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(i: Int) = shown[i]
        override fun getItemId(i: Int) = i.toLong()
        override fun getView(i: Int, v: View?, p: ViewGroup): View {
            val row = (v as? LinearLayout) ?: LinearLayout(this@LauncherActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                addView(ImageView(context).apply { layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)) })
                addView(TextView(context).apply {
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 11f
                    setTextColor(Color.WHITE)
                })
            }
            val a = shown[i]
            (row.getChildAt(0) as ImageView).setImageDrawable(a.icon)
            (row.getChildAt(1) as TextView).text = a.label
            return row
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        @Suppress("DEPRECATION")
        display = windowManager.defaultDisplay.displayId
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0080B10.toInt())
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        root.addView(TextClock(this).apply {
            format12Hour = "h:mm"
            format24Hour = "H:mm"
            textSize = 44f
            setTextColor(Color.WHITE)
        })
        root.addView(TextClock(this).apply {
            format12Hour = "EEEE, MMM d"
            format24Hour = "EEEE, MMM d"
            textSize = 14f
            setTextColor(0xB3FFFFFF.toInt())
        })
        search = EditText(this).apply {
            hint = "Search apps"
            isSingleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(0x80FFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF)
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
            doAfterTextChanged { applyFilter() }
            setOnEditorActionListener { _, _, _ ->
                shown.firstOrNull()?.let { launchApp(it) }
                true
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16); bottomMargin = dp(12) }
        }
        root.addView(search)
        grid = GridView(this).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(100)
            verticalSpacing = dp(6)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = GradientDrawable().apply {
                setColor(0x38FFFFFF)
                cornerRadius = dp(12).toFloat()
            }
            adapter = this@LauncherActivity.adapter
            setOnItemClickListener { _, _, i, _ -> shown.getOrNull(i)?.let { launchApp(it) } }
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN && search.hasFocus()) dismissIme()
                false
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        favRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        favScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            addView(favRow)
        }
        root.addView(favScroll)
        root.addView(grid)
        val footer = PromptFlowLayout(this, dp(14), dp(4)).apply {
            setPadding(0, dp(8), 0, 0)
            for (p in listOf(
                ActionPrompt(PromptGlyph.A, "Open"),
                ActionPrompt(PromptGlyph.X, "Options"),
                ActionPrompt(PromptGlyph.Y, "Search"),
                ActionPrompt(PromptGlyph.B, "Clear"),
            )) addView(RecentsPrompts.buildPromptChip(context, p))
        }
        root.addView(footer)
        setContentView(root)
        root.setOnApplyWindowInsetsListener { v, insets ->
            imeUp = insets.isVisible(android.view.WindowInsets.Type.ime())
            v.onApplyWindowInsets(insets)
        }
        grid.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        live[display] = this
        search.text.clear()
        Bg.post {
            val list = LauncherApps.load(applicationContext)
            runOnUiThread {
                all = list
                applyFilter()
            }
        }
    }

    override fun onPause() {
        if (live[display] === this) live.remove(display)
        imeUp = false
        super.onPause()
    }

    private fun applyFilter() {
        val q = search.text.toString()
        val list = LauncherApps.filter(all, q)
        if (q.isBlank()) {
            val fav = LauncherStore.favorites(this)
            favApps = fav.mapNotNull { p -> all.firstOrNull { it.pkg == p } }
            shown = list.filter { it.pkg !in fav }
        } else {
            favApps = emptyList()
            shown = list
        }
        renderFavs()
        adapter.notifyDataSetChanged()
        if (shown.isNotEmpty()) grid.setSelection(0)
    }

    private fun launchApp(a: LauncherApp) {
        if (search.hasFocus()) dismissIme()
        LauncherApps.launch(a, display)
    }

    private fun renderFavs() {
        favRow.removeAllViews()
        favScroll.visibility = if (favApps.isEmpty()) View.GONE else View.VISIBLE
        for (a in favApps) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isFocusable = true
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = StateListDrawable().apply {
                    addState(
                        intArrayOf(android.R.attr.state_focused),
                        GradientDrawable().apply { setColor(0x38FFFFFF); cornerRadius = dp(12).toFloat() },
                    )
                }
                addView(ImageView(context).apply {
                    setImageDrawable(a.icon)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                })
                addView(TextView(context).apply {
                    text = a.label
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 10f
                    gravity = Gravity.CENTER
                    maxWidth = dp(72)
                    setTextColor(Color.WHITE)
                })
                setOnClickListener { launchApp(a) }
                setOnLongClickListener { showMenu(a); true }
            }
            favRow.addView(cell)
        }
    }

    private fun favFocused() = ::favRow.isInitialized && favRow.hasFocus()

    // Null means the key is not handled by the favorites row and falls through to the grid handler.
    private fun favKey(code: Int): Boolean? {
        val idx = (0 until favRow.childCount).firstOrNull { favRow.getChildAt(it).hasFocus() } ?: return null
        return when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                favRow.getChildAt((idx - 1).coerceAtLeast(0)).requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                favRow.getChildAt((idx + 1).coerceAtMost(favRow.childCount - 1)).requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                grid.requestFocus()
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> true
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                favApps.getOrNull(idx)?.let { launchApp(it) }
                true
            }
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_BUTTON_START -> {
                favApps.getOrNull(idx)?.let { showMenu(it) }
                true
            }
            else -> null
        }
    }

    private fun ime() = getSystemService(InputMethodManager::class.java)

    private fun dismissIme() {
        ime().hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
        grid.requestFocus()
    }

    private fun showMenu(a: LauncherApp) {
        val fav = LauncherStore.isFavorite(this, a.pkg)
        val items = arrayOf(if (fav) "Unfavorite" else "Favorite", "App info", "Uninstall")
        AlertDialog.Builder(this).setTitle(a.label).setItems(items) { _, which ->
            when (which) {
                0 -> {
                    LauncherActions.toggleFavorite(this, a.pkg)
                    applyFilter()
                }
                1 -> LauncherActions.appInfo(this, a.pkg)
                2 -> LauncherActions.uninstall(this, a.pkg)
            }
        }.show()
    }

    // Clamps at grid edges: no wrap, no focus escape to the search field.
    private fun moveSelection(code: Int) {
        val last = shown.lastIndex
        if (last < 0) return
        val cols = grid.numColumns.coerceAtLeast(1)
        val cur = grid.selectedItemPosition.coerceAtLeast(0).coerceAtMost(last)
        if (code == KeyEvent.KEYCODE_DPAD_UP && cur < cols && favRow.childCount > 0) {
            favRow.getChildAt(0).requestFocus()
            return
        }
        val target = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cur % cols == 0) cur else cur - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cur % cols == cols - 1 || cur == last) cur else cur + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (cur < cols) cur else cur - cols
            else -> if (cur + cols <= last) cur + cols else if (cur / cols < last / cols) last else cur
        }
        if (target != cur) grid.setSelection(target)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = (if (favFocused()) favKey(code) else null) ?: when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN -> if (currentFocus === grid) {
            moveSelection(code)
            true
        } else {
            super.onKeyDown(code, e)
        }
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_START -> {
            shown.getOrNull(grid.selectedItemPosition)?.let { showMenu(it) }
            true
        }
        KeyEvent.KEYCODE_BUTTON_Y -> {
            search.requestFocus()
            ime().showSoftInput(search, 0)
            true
        }
        KeyEvent.KEYCODE_BUTTON_B -> {
            search.text.clear()
            dismissIme()
            true
        }
        KeyEvent.KEYCODE_BUTTON_A -> if (currentFocus === grid) {
            shown.getOrNull(grid.selectedItemPosition)?.let { launchApp(it) }
            true
        } else {
            super.onKeyDown(code, e)
        }
        else -> super.onKeyDown(code, e)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() = Unit
}
