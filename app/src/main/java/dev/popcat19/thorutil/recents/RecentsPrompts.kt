package dev.popcat19.thorutil.recents

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

enum class PromptGlyph(
    val label: String,
    val face: Boolean,
    val fillColor: Int,
    val textColor: Int,
) {
    A("A", true, 0xFFE8EEF7.toInt(), 0xFF10151D.toInt()),
    B("B", true, 0xFFE8EEF7.toInt(), 0xFF10151D.toInt()),
    X("X", true, 0xFFE8EEF7.toInt(), 0xFF10151D.toInt()),
    Y("Y", true, 0xFFE8EEF7.toInt(), 0xFF10151D.toInt()),
    L1R1("L1/R1", false, 0xFF2B3342.toInt(), 0xFFF1F5F9.toInt()),
    DPAD("D-pad", false, 0xFF2B3342.toInt(), 0xFFF1F5F9.toInt()),
    START("Start", false, 0xFF2B3342.toInt(), 0xFFF1F5F9.toInt()),
    SELECT("Select", false, 0xFF2B3342.toInt(), 0xFFF1F5F9.toInt()),
    R2R3("R2/R3", false, 0xFF2B3342.toInt(), 0xFFF1F5F9.toInt()),
}

data class ActionPrompt(
    val glyph: PromptGlyph,
    val label: String,
    val muted: Boolean = false,
    val accentColor: Int? = null,
)

object RecentsPrompts {
    fun buildGlyphBadge(
        ctx: Context,
        glyph: PromptGlyph,
        compact: Boolean = false,
    ): TextView {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val size = dp(if (compact) 17 else 19)
        return TextView(ctx).apply {
            text = glyph.label
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                when {
                    glyph.face -> if (compact) 10f else 11f
                    else -> if (compact) 9.5f else 10f
                },
            )
            setTextColor(glyph.textColor)
            isFocusable = false
            isFocusableInTouchMode = false
            if (glyph.face) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(glyph.fillColor)
                }
                layoutParams = LinearLayout.LayoutParams(size, size)
            } else {
                background = GradientDrawable().apply {
                    setColor(glyph.fillColor)
                    cornerRadius = dp(5).toFloat()
                    setStroke(max(1, dp(1)), 0x38FFFFFF)
                }
                val hPad = dp(if (compact) 5 else 6)
                setPadding(hPad, 0, hPad, 0)
                minWidth = size
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    size,
                )
            }
        }
    }

    fun buildPromptChip(ctx: Context, prompt: ActionPrompt): LinearLayout {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = false
            isFocusableInTouchMode = false
            alpha = if (prompt.muted) 0.45f else 1f
            addView(buildGlyphBadge(ctx, prompt.glyph))
            addView(
                TextView(ctx).apply {
                    text = prompt.label
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                    setTextColor(prompt.accentColor ?: 0xDCE2E8F0.toInt())
                    maxLines = 1
                    isFocusable = false
                    isFocusableInTouchMode = false
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(5) }
                },
            )
        }
    }
}

// Centered horizontal flow layout for SteamOS-style footer button prompts.
class PromptFlowLayout(
    ctx: Context,
    private val hGapPx: Int,
    private val vGapPx: Int,
) : ViewGroup(ctx) {
    private data class Row(val children: ArrayList<View> = ArrayList(), var width: Int = 0, var height: Int = 0)

    private val rows = ArrayList<Row>()

    init {
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        }
        val childWSpec = if (maxW == Int.MAX_VALUE) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST)
        }
        val childHSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        rows.clear()
        var cur = Row()
        var widest = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(childWSpec, childHSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight
            val addW = if (cur.children.isEmpty()) cw else hGapPx + cw
            if (cur.children.isNotEmpty() && cur.width + addW > maxW) {
                rows.add(cur)
                widest = max(widest, cur.width)
                cur = Row()
            }
            if (cur.children.isNotEmpty()) cur.width += hGapPx
            cur.children.add(child)
            cur.width += cw
            cur.height = max(cur.height, ch)
        }
        if (cur.children.isNotEmpty()) {
            rows.add(cur)
            widest = max(widest, cur.width)
        }

        var totalH = paddingTop + paddingBottom
        rows.forEachIndexed { idx, row ->
            totalH += row.height
            if (idx > 0) totalH += vGapPx
        }
        val desiredW = widest + paddingLeft + paddingRight
        setMeasuredDimension(
            resolveSize(desiredW, widthMeasureSpec),
            resolveSize(totalH, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availW = (r - l) - paddingLeft - paddingRight
        var y = paddingTop
        for (row in rows) {
            var x = paddingLeft + ((availW - row.width).coerceAtLeast(0) / 2)
            for (child in row.children) {
                val cw = child.measuredWidth
                val ch = child.measuredHeight
                val cy = y + (row.height - ch) / 2
                child.layout(x, cy, x + cw, cy + ch)
                x += cw + hGapPx
            }
            y += row.height + vGapPx
        }
    }
}
