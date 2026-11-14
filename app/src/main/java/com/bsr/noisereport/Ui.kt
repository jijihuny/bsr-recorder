package com.bsr.noisereport

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny view DSL so the prototype needs no XML layouts. Sizes are dp, text sizes sp. */
object C {
    val bg = Color.parseColor("#0E1116")
    val card = Color.parseColor("#181D25")
    val card2 = Color.parseColor("#222935")
    val line = Color.parseColor("#2E3644")
    val ink = Color.parseColor("#F2F4F8")
    val muted = Color.parseColor("#97A1B2")
    val accent = Color.parseColor("#4F8CFF")
    val rec = Color.parseColor("#FF4D4F")
    val warn = Color.parseColor("#FFB020")
    val ok = Color.parseColor("#3DD68C")
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

fun rounded(color: Int, radius: Float, stroke: Int? = null, strokeW: Int = 2) = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (stroke != null) setStroke(strokeW, stroke)
}

fun Context.vbox(pad: Int = 0, gap: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(gap), dp(gap)) } }
    block()
}

fun Context.hbox(pad: Int = 0, gap: Int = 0, block: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    if (gap > 0) { showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE; dividerDrawable = GradientDrawable().apply { setSize(dp(gap), dp(gap)) } }
    block()
}

fun Context.text(s: CharSequence, size: Float = 22f, color: Int = C.ink, bold: Boolean = false) = TextView(this).apply {
    text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    setLineSpacing(0f, 1.15f)
}

fun Context.cardBox(pad: Int = 24, gap: Int = 12, color: Int = C.card, block: LinearLayout.() -> Unit) =
    vbox(pad, gap, block).apply { background = rounded(color, dp(18).toFloat()) }

/** Big touch button. [kind]: primary, danger, ghost. */
fun Context.button(label: String, kind: String = "primary", size: Float = 24f, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = size; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
    setPadding(dp(32), dp(20), dp(32), dp(20))
    val (bgc, fg, stroke) = when (kind) {
        "danger" -> Triple(C.rec, Color.WHITE, null)
        "ghost" -> Triple(Color.TRANSPARENT, C.ink, C.line)
        "warn" -> Triple(C.warn, Color.BLACK, null)
        else -> Triple(C.accent, Color.WHITE, null)
    }
    setTextColor(fg); background = rounded(bgc, dp(16).toFloat(), stroke, dp(2))
    isClickable = true; isFocusable = true
    setOnClickListener { onClick() }
}

fun TextView.setEnabledLook(enabled: Boolean) { isEnabled = enabled; alpha = if (enabled) 1f else 0.35f }

/** Selectable chip. */
fun Context.chip(label: String, selected: Boolean, size: Float = 20f, onClick: () -> Unit) = TextView(this).apply {
    text = label; textSize = size; gravity = Gravity.CENTER
    setPadding(dp(22), dp(12), dp(22), dp(12))
    setTextColor(if (selected) Color.WHITE else C.ink)
    background = rounded(if (selected) C.accent else C.card2, dp(30).toFloat(), if (selected) null else C.line, dp(1))
    setOnClickListener { onClick() }
}

/** Wrapping row of chips (simple flow layout). */
class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap; rowH = maxOf(rowH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + rowH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += rowH + vGap; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + hGap; rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

fun Context.flow(block: FlowLayout.() -> Unit) = FlowLayout(this, dp(12), dp(12)).apply(block)

fun Context.scroll(child: View) = ScrollView(this).apply { isFillViewport = true; addView(child) }

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f) =
    LinearLayout.LayoutParams(w, h, weight)

fun View.weight(w: Float, vertical: Boolean = false): View = apply {
    layoutParams = if (vertical) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, w)
    else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
}

fun Context.frame(block: FrameLayout.() -> Unit) = FrameLayout(this).apply(block)

@Suppress("unused")
fun tint(c: Int) = ColorStateList.valueOf(c)
