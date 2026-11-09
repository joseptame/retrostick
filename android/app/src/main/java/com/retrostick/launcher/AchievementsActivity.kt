package com.retrostick.launcher

import android.app.Activity
import android.graphics.Color
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
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A game's achievements, like the PS5's trophy list (RetroAchievements through api/ra.ts): the progress at the
 * top (percentage, achievements earned, points), a filter (all / earned / still to get) and every achievement
 * with its badge, what it asks for, its points, how rare it is and the day it was earned. Opened with R2 from
 * the home screen. Sizes in dp for the 960×540 dp screen.
 */
class AchievementsActivity : Activity() {
    /** A game on the stick (R2 on the home screen), or null when opened from Mis logros by its RetroAchievements id */
    private var game: Game? = null
    private var raId = 0
    private var gameTitle = ""
    /** Progress fetched by id (Mis logros); [loaded] once it answered */
    private var remote: RetroAchievements.Progress? = null
    private var loaded = false
    private var error: String? = null
    private lateinit var covers: CoverLoader
    private lateinit var icon: ImageView
    private lateinit var title: TextView
    private lateinit var percent: TextView
    private lateinit var bar: View
    private lateinit var barFill: View
    private lateinit var summary: TextView
    private lateinit var filters: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var loading: LoadingBars

    private var filter = 0
    private var selected = 0
    private var shown = listOf<RetroAchievements.Achievement>()
    private var rowViews = listOf<View>()
    private val listener: () -> Unit = { if (!isDestroyed) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        raId = intent.getIntExtra(EXTRA_RA_ID, 0)
        val consoleName: String
        if (raId > 0) {
            gameTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            consoleName = intent.getStringExtra(EXTRA_CONSOLE_NAME).orEmpty()
        } else {
            val console = CONSOLES.firstOrNull { it.id == intent.getStringExtra(EXTRA_CONSOLE) } ?: return finish()
            val g = Game(console, File(intent.getStringExtra(EXTRA_FILE) ?: return finish()))
            game = g
            gameTitle = g.title
            consoleName = console.name
        }
        covers = CoverLoader(this)

        icon = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 10) }
        title = text(22f, R.color.text).apply { typeface = font(R.font.inter_extrabold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; text = gameTitle }
        val sub = text(10f, R.color.text_2).apply { text = listOf("Logros", "RetroAchievements", consoleName).filter { it.isNotEmpty() }.joinToString(" · ") }
        percent = text(28f, R.color.text).apply { typeface = font(R.font.inter_extrabold); gravity = Gravity.END }
        bar = FrameLayout(this).apply { background = round(Color.parseColor("#26FFFFFF"), 3) }
        barFill = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(getColor(R.color.accent_start), getColor(R.color.brand_accent))).apply { cornerRadius = dp(3).toFloat() } }
        (bar as FrameLayout).addView(barFill, FrameLayout.LayoutParams(0, -1))
        summary = text(10.5f, R.color.text_2).apply { gravity = Gravity.END }
        filters = LinearLayout(this)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
            addView(rows, FrameLayout.LayoutParams(-1, -2))
        }
        status = text(13f, R.color.text_2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        loading = LoadingBars(this, lines = 5)

        val head = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
            addView(LinearLayout(this@AchievementsActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16); marginEnd = dp(20) })
            addView(LinearLayout(this@AchievementsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(percent)
                addView(bar, LinearLayout.LayoutParams(dp(240), dp(6)).apply { topMargin = dp(2) })
                addView(summary, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            })
        }
        val hints = text(10.5f, R.color.chip_text)
        setHints(hints, "▲ ▼ Moverse      [L1R1]  Todos · Conseguidos · Por conseguir      [O]  Volver")
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@AchievementsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(28), dp(40), dp(36))
                addView(head)
                addView(filters, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
                addView(loading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(80); rightMargin = dp(80) })
            addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40); bottomMargin = dp(14) })
        })
        val g = game
        if (g != null) {
            RetroAchievements.addListener(listener)
            // Fresh progress every time (achievements may have just been earned)
            RetroAchievements.request(this, g, force = true)
        } else Thread {
            val r = runCatching { RetroAchievements.fetchById(this, raId) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loaded = true
                remote = r.getOrNull()
                error = r.exceptionOrNull()?.message
                render()
            }
        }.start()
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        RetroAchievements.removeListener(listener)
    }

    private fun render() {
        val g = game
        val p = if (g != null) RetroAchievements.cached(g) else remote
        val known = if (g != null) RetroAchievements.known(g) else loaded
        loading.show(!known && RetroAchievements.account(this) != null)
        status.visibility = View.GONE
        when {
            RetroAchievements.account(this) == null -> return showStatus("RetroAchievements no está activado en este stick.")
            !known -> return
            error != null -> return showStatus("No se han podido cargar los logros: $error")
            p == null -> return showStatus("$gameTitle no tiene logros en RetroAchievements (o no se ha encontrado esta versión del juego).")
            !p.supported -> return showStatus("Tu versión de $gameTitle no es compatible con sus logros de RetroAchievements: están hechos para otra versión del juego (normalmente la de EE. UU.). Por eso los logros están desactivados en este juego.")
        }
        p!!
        title.text = p.title.ifEmpty { gameTitle }
        p.icon?.let { url -> if (icon.tag != url) { icon.tag = url; covers.loadUrl(url, dp(56)) { b -> icon.setImageBitmap(b) } } }
        val pct = if (p.total > 0) p.earned * 100 / p.total else 0
        percent.text = "$pct %"
        bar.post { barFill.layoutParams = barFill.layoutParams.apply { width = bar.width * pct / 100 }; barFill.requestLayout() }
        summary.text = "${p.earned} de ${p.total} logros  ·  ${p.earnedPoints} / ${p.points} puntos"

        val lists = listOf(
            "Todos" to p.achievements,
            "Conseguidos" to p.achievements.filter { it.earned != null },
            "Por conseguir" to p.achievements.filter { it.earned == null },
        )
        filters.removeAllViews()
        lists.forEachIndexed { i, (name, l) ->
            val on = i == filter
            filters.addView(text(11.5f, R.color.text).apply {
                text = "$name  ${l.size}"
                typeface = font(if (on) R.font.inter_bold else R.font.inter_semibold)
                setTextColor(if (on) getColor(R.color.button_text) else getColor(R.color.text))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = round(if (on) Color.WHITE else Color.parseColor("#14FFFFFF"), 15)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        val list = lists[filter].second
        if (list.map { it.id } != shown.map { it.id }) {
            shown = list
            rows.removeAllViews()
            rowViews = list.map { row(it).also { v -> rows.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) } }
            selected = selected.coerceIn(0, maxOf(0, list.size - 1))
        }
        if (list.isEmpty()) showStatus(if (filter == 1) "Aún no has conseguido ningún logro de este juego." else "¡Los tienes todos!")
        rowViews.forEachIndexed { i, v ->
            val sel = i == selected
            v.background = if (sel) round(Color.parseColor("#1FFFFFFF"), 10).apply { setStroke(dp(1.5f), Color.WHITE) } else null
        }
        rowViews.getOrNull(selected)?.let { v ->
            scroll.post {
                val h = scroll.height - scroll.paddingTop - scroll.paddingBottom
                when {
                    v.top < scroll.scrollY -> scroll.smoothScrollTo(0, v.top - dp(4))
                    v.bottom > scroll.scrollY + h -> scroll.smoothScrollTo(0, v.bottom - h + dp(4))
                }
            }
        }
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    private fun row(a: RetroAchievements.Achievement): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(7), dp(14), dp(7))
        val got = a.earned != null
        val badge = ImageView(this@AchievementsActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 6) }
        val url = if (got) a.badge else a.badgeLocked
        covers.loadUrl(url, dp(44)) { b -> badge.setImageBitmap(b) }
        addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@AchievementsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(12.5f, if (got) R.color.text else R.color.text).apply {
                text = a.title; typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                alpha = if (got) 1f else 0.85f
            })
            addView(text(10f, R.color.text_2).apply { text = a.description; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(16) })
        addView(LinearLayout(this@AchievementsActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(text(11.5f, R.color.brand_accent).apply { text = "${a.points} pts"; typeface = font(R.font.inter_bold); gravity = Gravity.END },
                LinearLayout.LayoutParams(-1, -2))
            addView(text(9.5f, if (got) R.color.ok else R.color.text_3).apply {
                text = if (got) "✓ Conseguido ${formatDate(a.earned!!)}" else rarityText(a.rarity)
                typeface = font(R.font.inter_semibold); gravity = Gravity.END
            }, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(dp(170), -2))
    }

    /** PS5-like rarity: share of the game's players who have it. */
    private fun rarityText(r: Double?): String {
        if (r == null) return ""
        val label = when { r < 5 -> "Ultra raro"; r < 15 -> "Muy raro"; r < 50 -> "Raro"; else -> "Común" }
        return "$label · " + String.format(Locale("es", "ES"), "%.1f %%", r)
    }

    private fun formatDate(s: String): String = runCatching {
        val d = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!
        SimpleDateFormat("d MMM yyyy", Locale("es", "ES")).format(d).replace(".", "")
    }.getOrDefault(s.substringBefore(' '))

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        followPad(event)
        val code = StickStatus.standardKey(event.device, event.keyCode)
        val n = shown.size
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < n - 1) { selected++; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_DPAD_UP -> if (selected > 0) { selected--; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0 && filter < 2) { filter++; selected = 0; Sounds.play(Sounds.Sound.TAB); render() }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0 && filter > 0) { filter--; selected = 0; Sounds.play(Sounds.Sound.TAB); render() }
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

    /** The pad that pressed a key now decides the glyphs. */
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
        const val EXTRA_RA_ID = "ra_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_CONSOLE_NAME = "console_name"
    }
}
