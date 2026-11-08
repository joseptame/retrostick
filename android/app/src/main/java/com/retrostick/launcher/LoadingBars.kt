package com.retrostick.launcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * "Loading" placeholder for a ficha: rounded bars where the description will be (and, optionally, a row of
 * thumbnails), pulsing one after another like a soft wave. Shown while IGDB answers; [show] starts and stops it.
 */
class LoadingBars(ctx: Context, lines: Int = 3, thumbs: Int = 0) : LinearLayout(ctx) {
    private val pieces = mutableListOf<View>()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1400
        repeatCount = ValueAnimator.INFINITE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener { a ->
            val t = a.animatedValue as Float
            pieces.forEachIndexed { i, v ->
                // each piece a little behind the previous one: a wave going down / across
                val phase = ((t - i * 0.12f) % 1f + 1f) % 1f
                val k = (kotlin.math.sin(phase * 2 * Math.PI) * 0.5 + 0.5).toFloat()
                v.alpha = 0.35f + 0.5f * k
            }
        }
    }

    init {
        orientation = VERTICAL
        val widths = listOf(1f, 0.93f, 0.62f, 0.85f, 0.5f)
        repeat(lines) { i ->
            val row = LinearLayout(ctx)
            val bar = View(ctx).apply { background = round(Color.parseColor("#2EFFFFFF"), 5) }
            row.addView(bar, LayoutParams(0, dp(11), widths[i % widths.size]))
            row.addView(View(ctx), LayoutParams(0, 1, 1f - widths[i % widths.size]))
            addView(row, LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            pieces += bar
        }
        if (thumbs > 0) {
            val row = LinearLayout(ctx)
            repeat(thumbs) {
                val t = View(ctx).apply { background = round(Color.parseColor("#24FFFFFF"), 7) }
                row.addView(t, LayoutParams(dp(104), dp(58)).apply { marginEnd = dp(8) })
                pieces += t
            }
            addView(row, LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        visibility = GONE
    }

    fun show(on: Boolean) {
        visibility = if (on) VISIBLE else GONE
        if (on && !animator.isStarted) animator.start()
        if (!on) animator.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
