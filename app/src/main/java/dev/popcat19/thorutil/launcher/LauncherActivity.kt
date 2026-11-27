package dev.popcat19.thorutil.launcher

import android.app.Activity
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

class LauncherActivity : Activity() {
    private var all: List<LauncherApp> = emptyList()
    private var shown: List<LauncherApp> = emptyList()
    private lateinit var grid: GridView
    private lateinit var search: EditText
    private var display = 0

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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(grid)
        val footer = PromptFlowLayout(this, dp(14), dp(4)).apply {
            setPadding(0, dp(8), 0, 0)
            for (p in listOf(
                ActionPrompt(PromptGlyph.A, "Open"),
                ActionPrompt(PromptGlyph.Y, "Search"),
                ActionPrompt(PromptGlyph.B, "Clear"),
            )) addView(RecentsPrompts.buildPromptChip(context, p))
        }
        root.addView(footer)
        setContentView(root)
        grid.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        search.text.clear()
        Bg.post {
            val list = LauncherApps.load(applicationContext)
            runOnUiThread {
                all = list
                applyFilter()
            }
        }
    }

    private fun applyFilter() {
        shown = LauncherApps.filter(all, search.text.toString())
        adapter.notifyDataSetChanged()
        if (shown.isNotEmpty()) grid.setSelection(0)
    }

    private fun launchApp(a: LauncherApp) = LauncherApps.launch(a, display)

    private fun ime() = getSystemService(InputMethodManager::class.java)

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean = when (code) {
        KeyEvent.KEYCODE_BUTTON_Y -> {
            search.requestFocus()
            ime().showSoftInput(search, 0)
            true
        }
        KeyEvent.KEYCODE_BUTTON_B -> {
            search.text.clear()
            ime().hideSoftInputFromWindow(search.windowToken, 0)
            grid.requestFocus()
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
