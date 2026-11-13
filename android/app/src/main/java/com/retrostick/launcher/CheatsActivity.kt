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
 * Trucos of a game (Detalles → Trucos): the list of its cheats with a switch each, the common ones in Spanish
 * first. What's on is kept per game (Cheats.chosen) and RetroArch gets it on every launch.
 * Design: https://claude.ai/artifact/Tfrr7L7WGvjXJHAov2dHX3 (3 · Elegir trucos). Sizes in dp for 960×540 dp.
 */
class CheatsActivity : Activity() {
    private lateinit var game: Game
    private var cheats: List<Cheats.Cheat> = emptyList()
    private var chosen = mutableSetOf<String>()
    private var sel = 0
    private var top = 0

    private lateinit var header: TextView
    private lateinit var count: TextView
    private lateinit var list: LinearLayout
    private lateinit var more: TextView
    private lateinit var message: TextView
    private lateinit var versionNote: TextView
    private val rows = mutableListOf<Row>()

    private class Row(val view: LinearLayout, val name: TextView, val sub: TextView, val track: FrameLayout, val knob: View)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val console = CONSOLES.firstOrNull { it.id == intent.getStringExtra(EXTRA_CONSOLE) } ?: return finish()
        game = Game(console, File(intent.getStringExtra(EXTRA_FILE) ?: return finish()))
        chosen = Cheats.chosen(this, game).toMutableSet()
        build()
        val cached = Cheats.cached(this, game)
        if (cached != null) show(cached)
        else {
            message.text = "Buscando trucos…"
            Thread {
                val r = runCatching { Cheats.load(this, game) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    r.onSuccess(::show).onFailure { message.text = "No se pudieron bajar los trucos: ${it.message}\nComprueba la conexión y vuelve a entrar." }
                }
            }.start()
        }
    }

    private fun show(g: Cheats.GameCheats) {
        cheats = g.visible
        // Chosen ones the list no longer has don't count
        chosen.retainAll(g.cheats.map { it.id }.toSet())
        if (!g.exact) {
            versionNote.text = "Son trucos de otra versión del juego${g.version?.let { " ($it)" } ?: ""}: puede que alguno no funcione."
            versionNote.visibility = View.VISIBLE
        }
        message.text = if (cheats.isEmpty()) "No hay trucos para este juego." else ""
        message.visibility = if (cheats.isEmpty()) View.VISIBLE else View.GONE
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
                append("   /   ${game.console.name}   /   ${game.title}   /   Trucos")
            }
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }

        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#1AFFFFFF"), 11); elevation = dp(8).toFloat() }
        CoverLoader(this).load(game, dp(235)) { b -> cover.setImageBitmap(b ?: logoCover(this, game.console, dp(235))) }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cover, LinearLayout.LayoutParams(dp(235), dp(235)))
            addView(text(22f, R.color.text).apply { typeface = font(R.font.inter_extrabold); text = "Trucos" }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(text(10.5f, R.color.text_2).apply {
                text = "Se activan al empezar a jugar, también al seguir donde lo dejaste. Quítalos aquí cuando quieras."
                setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            if (RetroAchievements.supports(game.console) && RetroAchievements.account(this@CheatsActivity) != null) {
                addView(note("✓  Los logros siguen contando con trucos puestos.", "#1A4ADE80", "#384ADE80", R.color.text),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            }
            versionNote = note("", "#1AEAB308", "#38EAB308", R.color.text).apply { visibility = View.GONE }
            addView(versionNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        header = text(8.5f, R.color.text_3).apply { letterSpacing = 0.14f; typeface = font(R.font.inter_semibold) }
        count = text(8.5f, R.color.text_3)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        repeat(ROWS) { i ->
            val row = row()
            rows += row
            list.addView(row.view, LinearLayout.LayoutParams(-1, dp(48)).apply { if (i > 0) topMargin = dp(6) })
        }
        message = text(11f, R.color.text_2).apply { setLineSpacing(0f, 1.3f) }
        more = text(9f, R.color.text_3).apply { gravity = Gravity.CENTER }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@CheatsActivity).apply {
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
        setHints(hints, "[X]  Activar / quitar      ▲ ▼  Moverse      [TRI]  Quitar todos      [O]  Volver")
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(Color.parseColor("#8C0A081A"))
            addView(hints)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@CheatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), 0)
                addView(crumbs)
                addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
            }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
            addView(View(this@CheatsActivity).apply { setBackgroundColor(Color.parseColor("#12FFFFFF")) },
                FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        })
        render()
    }

    private fun row(): Row {
        val name = text(13f, R.color.text).apply { typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val sub = text(8.5f, R.color.text_3).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val knob = View(this)
        val track = FrameLayout(this).apply { addView(knob, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER_VERTICAL)) }
        val view = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), 0, dp(13), 0)
            addView(LinearLayout(this@CheatsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(14) })
            addView(track, LinearLayout.LayoutParams(dp(38), dp(20)))
        }
        return Row(view, name, sub, track, knob)
    }

    private fun note(label: String, bg: String, stroke: String, color: Int) = text(9.5f, color).apply {
        text = label; setLineSpacing(0f, 1.25f)
        setPadding(dp(9), dp(8), dp(9), dp(8))
        background = round(Color.parseColor(bg), 7).apply { setStroke(1, Color.parseColor(stroke)) }
    }

    // ---- State ----

    private fun render() {
        val on = cheats.count { it.id in chosen }
        header.text = "TRUCOS DE ESTE JUEGO" + if (on > 0) " · $on ${if (on == 1) "ACTIVO" else "ACTIVOS"}" else ""
        count.text = if (cheats.isEmpty()) "" else "${cheats.size} ${if (cheats.size == 1) "truco" else "trucos"}"
        list.visibility = if (cheats.isEmpty()) View.GONE else View.VISIBLE
        rows.forEachIndexed { i, r ->
            val c = cheats.getOrNull(top + i)
            r.view.visibility = if (c == null) View.INVISIBLE else View.VISIBLE
            if (c == null) return@forEachIndexed
            val selected = top + i == sel
            val enabled = c.id in chosen
            r.name.text = c.name
            r.sub.text = if (c.spanish != null) c.desc else ""
            r.sub.visibility = if (c.spanish != null) View.VISIBLE else View.GONE
            r.view.background = round(Color.parseColor(if (selected) "#1AFFFFFF" else "#0FFFFFFF"), 8).apply {
                if (selected) setStroke(dp(2), Color.WHITE) else setStroke(1, Color.parseColor("#14FFFFFF"))
            }
            r.view.elevation = if (selected) dp(6).toFloat() else 0f
            r.track.background = round(if (enabled) getColor(R.color.brand_accent) else Color.parseColor("#29FFFFFF"), 10)
            r.knob.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (enabled) Color.WHITE else getColor(R.color.text_2)) }
            r.knob.layoutParams = (r.knob.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or if (enabled) Gravity.END else Gravity.START
                marginStart = dp(2); marginEnd = dp(2)
            }
        }
        val below = cheats.size - (top + ROWS)
        more.text = if (below > 0) "▼  $below más" else ""
    }

    private fun move(d: Int) {
        if (cheats.isEmpty()) return
        val to = (sel + d).coerceIn(0, cheats.size - 1)
        if (to == sel) return
        sel = to
        if (sel < top) top = sel
        if (sel >= top + ROWS) top = sel - ROWS + 1
        Sounds.play(Sounds.Sound.MOVE)
        render()
    }

    private fun toggle() {
        val c = cheats.getOrNull(sel) ?: return
        val on = c.id !in chosen
        if (on) chosen += c.id else chosen -= c.id
        Cheats.setChosen(this, game, chosen)
        Sounds.play(if (on) Sounds.Sound.OPEN else Sounds.Sound.BACK)
        render()
    }

    private fun clearAll() {
        if (chosen.isEmpty()) return
        chosen.clear()
        Cheats.setChosen(this, game, chosen)
        Sounds.play(Sounds.Sound.BACK)
        Toast.makeText(this, "Trucos quitados", Toast.LENGTH_SHORT).show()
        render()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        followPad(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_BUTTON_L1 -> move(-ROWS)
            KeyEvent.KEYCODE_BUTTON_R1 -> move(ROWS)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> toggle()
            KeyEvent.KEYCODE_BUTTON_Y -> clearAll()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    // ---- Hints with the buttons of the pad in use (PadHints) ----
    private var hintsTemplate = ""
    private var hintsView: TextView? = null
    private var hintsStyle = PadStyle.PLAYSTATION

    private fun setHints(view: TextView, template: String) {
        hintsView = view
        hintsTemplate = template
        hintsStyle = StickStatus.firstPadStyle()
        showHints()
    }

    private fun showHints() {
        val v = hintsView ?: return
        v.text = PadHints.build(this, hintsTemplate, hintsStyle, v.currentTextColor, (v.textSize * 1.25f).toInt())
    }

    private fun followPad(event: KeyEvent) {
        val style = StickStatus.padStyle(event.device)
        if (style != hintsStyle) { hintsStyle = style; showHints() }
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
