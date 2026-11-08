package com.retrostick.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan

/**
 * A controller button drawn inside text as a small outlined box, like the hints bar's "L2" or "Options":
 * "[Share]  Manual". [scale] sizes the box's text against the surrounding text.
 */
class PillSpan(private val color: Int, private val density: Float, private val scale: Float = 0.62f) : ReplacementSpan() {
    private fun boxPaint(p: Paint) = Paint(p).apply { textSize = p.textSize * scale; isFakeBoldText = true }

    override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        val bp = boxPaint(paint)
        fm?.let { paint.getFontMetricsInt(it) }
        return (bp.measureText(text, start, end) + 10 * density).toInt()
    }

    override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val bp = boxPaint(paint).apply { this.color = this@PillSpan.color }
        val w = bp.measureText(text, start, end) + 10 * density
        val fm = paint.fontMetrics
        // Centred on the surrounding text's x-height band
        val mid = y + (fm.ascent + fm.descent) / 2
        val h = (bp.fontMetrics.descent - bp.fontMetrics.ascent) + 3 * density
        val box = RectF(x + density, mid - h / 2, x + w - density, mid + h / 2)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density; this.color = this@PillSpan.color }
        canvas.drawRoundRect(box, 3.5f * density, 3.5f * density, stroke)
        val bfm = bp.fontMetrics
        canvas.drawText(text, start, end, x + 5 * density, mid - (bfm.ascent + bfm.descent) / 2, bp)
    }

    companion object {
        /** "[label]  rest" with the label boxed; just [rest] when there is no label (TV remote). */
        fun text(label: String, rest: String, color: Int, density: Float): CharSequence {
            if (label.isEmpty()) return rest
            return SpannableStringBuilder(label).apply {
                setSpan(PillSpan(color, density), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ").append(rest)
            }
        }
    }
}
