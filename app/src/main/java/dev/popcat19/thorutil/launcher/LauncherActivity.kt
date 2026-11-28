package dev.popcat19.thorutil.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
        shown = if (q.isBlank()) {
            val fav = LauncherStore.favorites(this)
            list.sortedBy { a -> fav.indexOf(a.pkg).let { if (it < 0) Int.MAX_VALUE else it } }
        } else {
            list
        }
        adapter.notifyDataSetChanged()
        if (shown.isNotEmpty()) grid.setSelection(0)
    }

    private fun launchApp(a: LauncherApp) = LauncherApps.launch(a, display)

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
                1 -> LauncherActions.appInfo(a.pkg, display)
                2 -> LauncherActions.uninstall(a.pkg, display)
            }
        }.show()
    }

    // Clamps at grid edges: no wrap, no focus escape to the search field.
    private fun moveSelection(code: Int) {
        val last = shown.lastIndex
        if (last < 0) return
        val cols = grid.numColumns.coerceAtLeast(1)
        val cur = grid.selectedItemPosition.coerceAtLeast(0).coerceAtMost(last)
        val target = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (cur % cols == 0) cur else cur - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (cur % cols == cols - 1 || cur == last) cur else cur + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (cur < cols) cur else cur - cols
            else -> if (cur + cols <= last) cur + cols else if (cur / cols < last / cols) last else cur
        }
        if (target != cur) grid.setSelection(target)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = when (code) {
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
