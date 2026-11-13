package com.retrostick.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Novedades (News): what the update brings, once after updating, or again from Retro Ajustes ([EXTRA_ALL]).
 * Three at a time, ▲ ▼ for more; OK closes. Design: https://claude.ai/artifact/Tfrr7L7WGvjXJHAov2dHX3
 * (1 · Novedades tras actualizar). Sizes in dp for the 960×540 dp screen.
 */
class NewsActivity : Activity() {
    private var items = listOf<News.Item>()
    private var top = 0
    private lateinit var list: LinearLayout
    private lateinit var more: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val all = intent.getBooleanExtra(EXTRA_ALL, false)
        val releases = if (all) News.RELEASES.take(3) else News.pending(this)
        if (releases.isEmpty()) return finish()
        News.markSeen(this)
        items = releases.flatMap { it.items }

        val crumbs = text(11f, R.color.text_2).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("Retro"); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                val s = length; append("Stick")
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), s, length, 0)
                append("   /   Novedades")
            }
        }
        val kicker = text(8.5f, R.color.text_3).apply {
            text = if (all) "NOVEDADES" else "RETROSTICK SE HA ACTUALIZADO"
            letterSpacing = 0.14f; typeface = font(R.font.inter_semibold)
        }
        val title = text(32f, R.color.text).apply { typeface = font(R.font.inter_extrabold); text = "Novedades de la ${releases.first().name}" }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        more = text(9.5f, R.color.text_3).apply { gravity = Gravity.CENTER }
        val ok = TextView(this).apply {
            text = "Entendido"; setTextColor(getColor(R.color.button_text)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); typeface = font(R.font.inter_bold)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(9), 0, dp(20), 0)
            background = round(Color.WHITE, 19)
            elevation = dp(5).toFloat()
            val badge = TextView(this@NewsActivity).apply {
                text = "OK"; setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 7.5f); typeface = font(R.font.inter_extrabold); gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.button_text)) }
            }
            setCompoundDrawablesRelative(viewDrawable(badge, dp(22)), null, null, null)
            compoundDrawablePadding = dp(7)
        }
        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ok, LinearLayout.LayoutParams(-2, dp(38)))
            addView(text(10f, R.color.text_3).apply { text = "Vuelve a verlas cuando quieras en Retro Ajustes → Novedades" },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(kicker)
            addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            addView(footer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        val hints = text(9.5f, R.color.chip_text)
        hints.text = PadHints.build(this, (if (items.size > SHOWN) "▲ ▼  Más novedades      " else "") + "[X]  Cerrar",
            StickStatus.firstPadStyle(), hints.currentTextColor, (hints.textSize * 1.25f).toInt())
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(Color.parseColor("#8C0A081A"))
            addView(hints)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(crumbs, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(40); topMargin = dp(26) })
            addView(column, FrameLayout.LayoutParams(dp(620), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(66) })
            addView(View(this@NewsActivity).apply { setBackgroundColor(Color.parseColor("#12FFFFFF")) },
                FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        })
        render()
    }

    private fun render() {
        list.removeAllViews()
        items.drop(top).take(SHOWN).forEachIndexed { i, item ->
            list.addView(card(item), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(7) })
        }
        val below = items.size - (top + SHOWN)
        more.text = when {
            below > 0 -> "▼  $below más"
            top > 0 -> "▲  anteriores"
            else -> ""
        }
        more.visibility = if (items.size > SHOWN) View.VISIBLE else View.GONE
    }

    private fun card(item: News.Item) = LinearLayout(this).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = round(Color.parseColor("#0FFFFFFF"), 9).apply { setStroke(1, Color.parseColor("#14FFFFFF")) }
        val icon = FrameLayout(this@NewsActivity).apply {
            background = round(Color.parseColor("#2E9D8CFF"), 7)
            addView(ImageView(this@NewsActivity).apply {
                setImageDrawable(getDrawable(item.icon)!!.mutate().apply { setTint(getColor(R.color.brand_accent)) })
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER))
        }
        addView(icon, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(13) })
        addView(LinearLayout(this@NewsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(15f, R.color.text).apply { text = item.title; typeface = font(R.font.inter_bold) })
            addView(text(11f, R.color.text_2).apply { text = item.text; setLineSpacing(0f, 1.25f) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_DPAD_UP -> if (top > 0) { top--; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (top + SHOWN < items.size) { top++; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** A small view drawn into a drawable: the «OK» badge inside the white button. */
    private fun viewDrawable(v: View, size: Int): android.graphics.drawable.Drawable {
        v.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, size, size)
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        return android.graphics.drawable.BitmapDrawable(resources, bmp).apply { setBounds(0, 0, size, size) }
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font(R.font.inter_regular)
    }
    private fun font(id: Int) = resources.getFont(id)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_ALL = "all"
        private const val SHOWN = 3
    }
}
