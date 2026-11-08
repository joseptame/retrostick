package com.retrostick.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.widget.ImageView

/**
 * Box art in a square slot. Near-square boxes fill it (centre crop). Wide boxes (European SNES, N64…) and
 * portrait ones (NES, Game Boy…) would lose their sides or their top and bottom that way, so they are shown whole,
 * over a blurred, darkened copy of themselves that fills the gaps.
 */
class CoverView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint().apply { color = Color.argb(90, 0, 0, 0) }
    private var blurOf: Bitmap? = null
    private var blur: Bitmap? = null

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    private val bitmap get() = (drawable as? BitmapDrawable)?.bitmap

    /** More than 8 % off the slot's shape either way: cropping would cut part of the box. */
    private fun needsFit(b: Bitmap): Boolean {
        val box = b.width.toFloat() / b.height
        val slot = width.toFloat() / height.coerceAtLeast(1)
        return box / slot > 1.08f || slot / box > 1.08f
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap
        if (b == null || b.isRecycled || width == 0 || height == 0 || !needsFit(b)) return super.onDraw(canvas)

        // Backdrop: the cover shrunk to a few pixels and stretched back with filtering = a cheap blur.
        if (blurOf !== b) {
            blurOf = b
            val w = 12
            blur = Bitmap.createScaledBitmap(b, w, (w * b.height / b.width).coerceAtLeast(4), true)
        }
        blur?.let { canvas.drawBitmap(it, null, coverRect(it), paint) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

        // The whole box, fitted and centred
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        val left = (width - w) / 2
        val top = (height - h) / 2
        canvas.drawBitmap(b, Rect(0, 0, b.width, b.height), RectF(left, top, left + w, top + h), paint)
    }

    /** Destination that covers the whole view (centre crop) for [b]. */
    private fun coverRect(b: Bitmap): RectF {
        val scale = maxOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = b.width * scale
        val h = b.height * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }
}
