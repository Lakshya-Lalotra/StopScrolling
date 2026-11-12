package com.scrollstop.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import kotlin.math.roundToInt

/** Small helpers to build Material 3 screens in code. */
class Ui(private val ctx: Context) {

    enum class Btn { FILLED, TONAL, OUTLINED, TEXT }

    fun dp(v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density).roundToInt()

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun header(parent: LinearLayout, title: String, subtitle: String?) {
        text(parent, title, com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(8), dp(4), 0)
        }
        subtitle?.let {
            text(parent, it, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted = true)
                .setPadding(dp(4), dp(2), dp(4), dp(14))
        }
    }

    /** A filled card; returns the vertical container to put rows into. */
    fun card(parent: LinearLayout, title: String? = null, subtitle: String? = null): LinearLayout {
        val card = MaterialCardView(ctx, null, com.google.android.material.R.attr.materialCardViewFilledStyle)
        card.layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        card.radius = dp(20).toFloat()
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(inner, ViewGroup.LayoutParams(MATCH, WRAP))
        parent.addView(card)
        title?.let { title(inner, it) }
        subtitle?.let { body(inner, it, muted = true).setPadding(0, dp(2), 0, dp(4)) }
        return inner
    }

    fun title(parent: LinearLayout, s: String): TextView =
        text(parent, s, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    fun body(parent: LinearLayout, s: CharSequence, muted: Boolean = false): TextView =
        text(parent, s, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted)

    fun small(parent: LinearLayout, s: CharSequence): TextView =
        text(parent, s, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)

    fun text(parent: ViewGroup?, s: CharSequence, appearance: Int, muted: Boolean = false): TextView {
        val tv = TextView(ctx)
        TextViewCompat.setTextAppearance(tv, appearance)
        tv.text = s
        if (muted) tv.setTextColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
        parent?.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        return tv
    }

    fun mutedColor() = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
    fun primaryColor() = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0)

    /**
     * A row with a title, optional subtitle and a switch on the right.
     * [onChange] gets the new value and a function that puts the switch back (for refused changes).
     */
    fun switchRow(
        parent: LinearLayout,
        title: String,
        subtitle: String?,
        checked: Boolean,
        enabled: Boolean = true,
        onChange: (Boolean, () -> Unit) -> Unit,
    ): MaterialSwitch {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(4), 0, dp(4))
            alpha = if (enabled) 1f else 0.45f
        }
        val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        text(texts, title, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        subtitle?.let { text(texts, it, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true) }
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(12) })
        val sw = MaterialSwitch(ctx)
        sw.isChecked = checked
        sw.isEnabled = enabled
        row.addView(sw, LinearLayout.LayoutParams(WRAP, WRAP))
        var silent = false
        sw.setOnCheckedChangeListener { _, c ->
            if (silent) return@setOnCheckedChangeListener
            onChange(c) {
                silent = true
                sw.isChecked = !c
                silent = false
            }
        }
        if (enabled) row.setOnClickListener { sw.toggle() }
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return sw
    }

    fun button(parent: ViewGroup?, label: String, style: Btn = Btn.FILLED, onClick: () -> Unit): MaterialButton {
        val themed = when (style) {
            Btn.FILLED -> ctx
            Btn.TONAL -> ContextThemeWrapper(ctx, R.style.ThemeOverlay_ScrollStop_TonalButton)
            Btn.OUTLINED -> ContextThemeWrapper(ctx, R.style.ThemeOverlay_ScrollStop_OutlinedButton)
            Btn.TEXT -> ContextThemeWrapper(ctx, R.style.ThemeOverlay_ScrollStop_TextButton)
        }
        val b = MaterialButton(themed)
        b.text = label
        b.isAllCaps = false
        b.maxLines = 1
        b.ellipsize = TextUtils.TruncateAt.END
        b.setOnClickListener { onClick() }
        parent?.addView(b, LinearLayout.LayoutParams(WRAP, WRAP))
        return b
    }

    /** Buttons sharing a row equally. */
    fun buttonRow(parent: LinearLayout, vararg items: Triple<String, Btn, () -> Unit>): List<MaterialButton> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val out = items.mapIndexed { i, (label, style, click) ->
            button(null, label, style, click).also {
                row.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                    if (i > 0) marginStart = dp(8)
                })
            }
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        return out
    }

    /** Big numbers with captions, side by side. Returns the number TextViews. */
    fun stats(parent: LinearLayout, captions: List<String>): List<TextView> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val nums = captions.map { cap ->
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val n = text(col, "0", com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall).apply {
                gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(primaryColor())
            }
            text(col, cap, com.google.android.material.R.style.TextAppearance_Material3_BodySmall, muted = true)
                .gravity = Gravity.CENTER
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            n
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8); bottomMargin = dp(4) })
        return nums
    }

    fun divider(parent: LinearLayout) {
        val v = View(ctx)
        v.setBackgroundColor(MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, 0))
        parent.addView(v, LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    fun space(parent: LinearLayout, heightDp: Int) {
        parent.addView(View(ctx), LinearLayout.LayoutParams(MATCH, dp(heightDp)))
    }
}

/** A simple bar chart for the last few days. */
class BarChartView(context: Context) : View(context) {
    data class Bar(val value: Float, val label: String, val valueText: String, val highlight: Boolean)

    var bars: List<Bar> = emptyList()
        set(v) {
            field = v
            contentDescription = v.joinToString(", ") { "${it.label}: ${it.valueText}" }
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11 * resources.displayMetrics.scaledDensity
    }
    private val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
    private val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (170 * density).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val top = 18 * density
        val bottom = height - 20 * density
        val slot = width / bars.size.toFloat()
        val barW = slot * 0.5f
        val max = bars.maxOf { it.value }.coerceAtLeast(1f)
        bars.forEachIndexed { i, b ->
            val cx = slot * i + slot / 2
            val h = ((bottom - top) * (b.value / max)).coerceAtLeast(if (b.value > 0) 3 * density else 0f)
            barPaint.color = if (b.highlight) primary else (primary and 0x00FFFFFF) or 0x66000000
            canvas.drawRoundRect(RectF(cx - barW / 2, bottom - h, cx + barW / 2, bottom), 6 * density, 6 * density, barPaint)
            textPaint.color = muted
            canvas.drawText(b.label, cx, height - 4 * density, textPaint)
            if (b.value > 0) canvas.drawText(b.valueText, cx, bottom - h - 4 * density, textPaint)
        }
    }
}
