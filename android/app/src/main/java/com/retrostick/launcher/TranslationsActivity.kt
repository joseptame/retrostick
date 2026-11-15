package com.retrostick.launcher

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Detalles → Idioma: the game's Spanish fan translations (Translations), «Original» first. [X] uses one: it downloads
 * and the game opens translated from then on; «Original» goes back. Design: https://claude.ai/artifact/MonZppVqKizExqBcoxdndv
 * (2 · Elegir traducción). Sizes in dp for the 960×540 dp screen.
 */
class TranslationsActivity : Activity() {
    private lateinit var game: Game
    /** null = «Original» */
    private var options: List<Translations.Translation?> = listOf(null)
    private var sel = 0
    private var top = 0
    private var busy: String? = null
    private var busyDone = 0L

    private lateinit var header: TextView
    private lateinit var count: TextView
    private lateinit var list: LinearLayout
    private lateinit var more: TextView
    private lateinit var message: TextView
    private val rows = mutableListOf<Row>()

    private class Row(val view: LinearLayout, val radio: FrameLayout, val name: TextView, val sub: TextView, val badge: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val console = CONSOLES.firstOrNull { it.id == intent.getStringExtra(EXTRA_CONSOLE) } ?: return finish()
        game = Game(console, File(intent.getStringExtra(EXTRA_FILE) ?: return finish()))
        build()
        Translations.cachedFor(this, game)?.let { show(it) } ?: run { message.text = "Buscando traducciones…" }
        Thread {
            val r = runCatching { Translations.forGame(this, game) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess(::show).onFailure { if (options.size <= 1) message.text = "No se pudo bajar la lista de traducciones: ${it.message}" }
            }
        }.start()
    }

    /** «Original»: the languages its name says («(En,Fr,De)», or the region's: USA → Inglés) */
    private val originalLanguages by lazy {
        Languages.of(game.file.name).takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "Tu juego tal cual"
    }

    private fun show(list: List<Translations.Translation>) {
        options = listOf<Translations.Translation?>(null) + list
        val chosen = Translations.chosen(this, game)
        sel = options.indexOfFirst { it?.file == chosen }.coerceAtLeast(0)
        if (sel >= ROWS) top = sel - ROWS + 1
        message.text = if (list.isEmpty()) "No hay traducciones al español de este juego." else ""
        message.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        render()
    }

    // ---- Layout ----

    private fun build() {
        val crumbs = text(11f, R.color.text_2).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("Retro"); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                val s = length; append("Stick")
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), s, length, 0)
                append("   /   ${game.console.name}   /   ${game.title}   /   Idioma")
            }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#1AFFFFFF"), 11); elevation = dp(8).toFloat() }
        CoverLoader(this).load(game, dp(235)) { b -> cover.setImageBitmap(b ?: logoCover(this, game.console, dp(235))) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cover, LinearLayout.LayoutParams(dp(235), dp(235)))
            addView(text(22f, R.color.text).apply { typeface = font(R.font.inter_extrabold); text = "Idioma del juego" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(text(10.5f, R.color.text_2).apply {
                text = "Traducciones hechas por aficionados. Al elegir una se descarga y el juego se abre ya traducido; tu versión original no se toca y puedes volver a ella."
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(text(9.5f, R.color.text).apply {
                text = "Con una traducción, RetroAchievements no suele reconocer el juego: los logros cuentan en el original. Tus partidas se mantienen."
                setLineSpacing(0f, 1.25f); setPadding(dp(9), dp(8), dp(9), dp(8))
                background = round(Color.parseColor("#1AEAB308"), 7).apply { setStroke(1, Color.parseColor("#38EAB308")) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }

        header = text(8.5f, R.color.text_3).apply { letterSpacing = 0.14f; typeface = font(R.font.inter_semibold); text = "ELIGE CÓMO JUGARLO" }
        count = text(8.5f, R.color.text_3)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(ROWS) { i ->
            val row = row()
            rows += row
            list.addView(row.view, LinearLayout.LayoutParams(-1, dp(46)).apply { if (i > 0) topMargin = dp(6) })
        }
        message = text(11f, R.color.text_2).apply { setLineSpacing(0f, 1.3f) }
        more = text(9f, R.color.text_3).apply { gravity = Gravity.CENTER }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@TranslationsActivity).apply {
                addView(header, LinearLayout.LayoutParams(0, -2, 1f))
                addView(count)
            })
            addView(message, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(list, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) })
            addView(more, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }
        val body = LinearLayout(this).apply {
            addView(left, LinearLayout.LayoutParams(dp(235), -1).apply { marginEnd = dp(32) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val hints = text(9.5f, R.color.chip_text)
        hints.text = PadHints.build(this, "[X]  Usar esta      ▲ ▼  Moverse      [O]  Volver", StickStatus.firstPadStyle(), hints.currentTextColor, (hints.textSize * 1.25f).toInt())
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(Color.parseColor("#8C0A081A"))
            addView(hints)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@TranslationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), 0)
                addView(crumbs)
                addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
            }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
            addView(View(this@TranslationsActivity).apply { setBackgroundColor(Color.parseColor("#12FFFFFF")) },
                FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        })
        render()
    }

    private fun row(): Row {
        val radio = FrameLayout(this).apply { addView(View(this@TranslationsActivity), FrameLayout.LayoutParams(dp(7), dp(7), Gravity.CENTER)) }
        val name = text(13f, R.color.text).apply { typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = text(8.5f, R.color.text_3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val badge = text(8.5f, R.color.ok).apply { typeface = font(R.font.inter_bold); setPadding(dp(7), dp(3), dp(7), dp(3)) }
        val view = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            addView(radio, LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginEnd = dp(12) })
            addView(LinearLayout(this@TranslationsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(10) })
            addView(badge)
        }
        return Row(view, radio, name, sub, badge)
    }

    private fun render() {
        val chosen = Translations.chosen(this, game)
        val n = options.size - 1
        count.text = if (n == 0) "" else "$n ${if (n == 1) "traducción" else "traducciones"} al español"
        list.visibility = if (n == 0 && options.size == 1 && message.text.isNotEmpty()) View.GONE else View.VISIBLE
        rows.forEachIndexed { i, r ->
            val idx = top + i
            if (idx >= options.size) { r.view.visibility = View.INVISIBLE; return@forEachIndexed }
            r.view.visibility = View.VISIBLE
            val t = options[idx]
            val inUse = if (t == null) chosen == null else t.file == chosen
            val selected = idx == sel
            r.name.text = t?.name ?: "Original"
            r.sub.text = when {
                t != null && busy == t.file -> "Descargando… ${if (t.size > 0) (busyDone * 100 / t.size).coerceAtMost(100) else 0} %"
                t == null -> originalLanguages
                else -> t.details
            }
            r.name.setTextColor(getColor(R.color.text))
            r.badge.text = if (inUse) "En uso" else ""
            r.badge.background = if (inUse) round(Color.parseColor("#294ADE80"), 6) else null
            r.view.background = round(Color.parseColor(if (selected) "#1FFFFFFF" else "#0FFFFFFF"), 8).apply {
                if (selected) setStroke(dp(2), Color.WHITE) else setStroke(1, Color.parseColor("#14FFFFFF"))
            }
            r.view.elevation = if (selected) dp(6).toFloat() else 0f
            r.radio.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (inUse) setColor(getColor(R.color.brand_accent)) else setStroke(dp(2), Color.parseColor("#4DFFFFFF"))
            }
            r.radio.getChildAt(0).background = if (inUse) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } else null
        }
        val below = options.size - (top + ROWS)
        more.text = if (below > 0) "▼  $below más" else ""
    }

    private fun move(d: Int) {
        val to = (sel + d).coerceIn(0, options.size - 1)
        if (to == sel) return
        sel = to
        if (sel < top) top = sel
        if (sel >= top + ROWS) top = sel - ROWS + 1
        Sounds.play(Sounds.Sound.MOVE)
        render()
    }

    private fun choose() {
        if (busy != null) return
        val t = options.getOrNull(sel)
        if (t == null) {
            if (Translations.chosen(this, game) == null) return
            Translations.useOriginal(this, game)
            Sounds.play(Sounds.Sound.BACK)
            Toast.makeText(this, "${game.title}: versión original", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        if (t.file == Translations.chosen(this, game)) return
        busy = t.file; busyDone = 0
        Sounds.play(Sounds.Sound.OPEN)
        render()
        Thread {
            val r = runCatching {
                Translations.use(this, game, t) { done ->
                    busyDone = done
                    runOnUiThread { if (!isDestroyed) render() }
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                busy = null
                r.onSuccess {
                    Sounds.play(Sounds.Sound.DONE)
                    Toast.makeText(this, "${game.title} se jugará en español (${t.by})", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Sounds.play(Sounds.Sound.ERROR)
                    Toast.makeText(this, "No se pudo descargar: ${it.message}", Toast.LENGTH_LONG).show()
                }
                render()
            }
        }.start()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> choose()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font(R.font.inter_regular)
    }
    private fun font(id: Int) = resources.getFont(id)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_CONSOLE = "console"
        const val EXTRA_FILE = "file"
        private const val ROWS = 7
    }
}
