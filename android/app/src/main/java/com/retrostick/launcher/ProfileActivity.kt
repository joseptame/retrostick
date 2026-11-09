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
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The user's trophy profile, like the PS5's (Retro Ajustes → Mis logros): avatar, points and rank at the top,
 * then two lists (L1 / R1): every game with achievements they've played, with its progress, and the last
 * achievements earned. RetroAchievements through api/ra.ts?profile=1. Sizes in dp for the 960×540 dp screen.
 */
class ProfileActivity : Activity() {
    private lateinit var covers: CoverLoader
    private lateinit var avatar: ImageView
    private lateinit var name: TextView
    private lateinit var stats: TextView
    private lateinit var points: TextView
    private lateinit var rank: TextView
    private lateinit var tabs: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var loading: LoadingBars

    private var profile: RetroAchievements.Profile? = null
    private var tab = 0
    private var selected = 0
    private var shownTab = -1
    private var rowViews = listOf<View>()
    private val io = Executors.newSingleThreadExecutor()
    private val es = Locale("es", "ES")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        covers = CoverLoader(this)
        avatar = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 32) }
        name = text(22f, R.color.text).apply { typeface = font(R.font.inter_extrabold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; text = RetroAchievements.account(this@ProfileActivity)?.user ?: "Mis logros" }
        stats = text(10.5f, R.color.text_2).apply { text = "Mis logros · RetroAchievements" }
        points = text(28f, R.color.text).apply { typeface = font(R.font.inter_extrabold); gravity = Gravity.END }
        rank = text(10.5f, R.color.text_2).apply { gravity = Gravity.END }
        tabs = LinearLayout(this)
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
            addView(avatar, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(name)
                addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16); marginEnd = dp(20) })
            addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(points, LinearLayout.LayoutParams(-1, -2))
                addView(rank, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            }, LinearLayout.LayoutParams(dp(300), -2))
        }
        val hints = text(10.5f, R.color.chip_text).apply { text = "▲ ▼ Moverse      L1 / R1  Juegos · Recientes      ○ Volver" }
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@ProfileActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(28), dp(40), dp(36))
                addView(head)
                addView(tabs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
                addView(loading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(80); rightMargin = dp(80) })
            addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40); bottomMargin = dp(14) })
        })
        loading.show(true)
        io.execute {
            val r = runCatching { RetroAchievements.fetchProfile(this) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading.show(false)
                r.onSuccess { profile = it; render() }
                    .onFailure { showStatus("No se han podido cargar tus logros: ${it.message}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    private fun render() {
        val p = profile ?: return
        status.visibility = View.GONE
        name.text = p.user
        p.avatar?.let { url -> if (avatar.tag != url) { avatar.tag = url; covers.loadUrl(url, dp(64)) { b -> avatar.setImageBitmap(b) } } }
        val earned = p.games.sumOf { it.earned }
        val done = p.games.count { it.total > 0 && it.earned >= it.total }
        fun n(count: Int, one: String, many: String) = "$count ${if (count == 1) one else many}"
        stats.text = listOf(n(p.games.size, "juego", "juegos"), n(earned, "logro", "logros"), n(done, "completado", "completados")).joinToString("  ·  ")
        points.text = NumberFormat.getIntegerInstance(es).format(p.points) + " pts"
        rank.text = p.rank?.let { "Puesto nº " + NumberFormat.getIntegerInstance(es).format(it) + " en RetroAchievements" } ?: ""

        val counts = listOf("Juegos" to p.games.size, "Recientes" to p.recent.size)
        tabs.removeAllViews()
        counts.forEachIndexed { i, (label, n) ->
            val on = i == tab
            tabs.addView(text(11.5f, R.color.text).apply {
                text = "$label  $n"
                typeface = font(if (on) R.font.inter_bold else R.font.inter_semibold)
                setTextColor(if (on) getColor(R.color.button_text) else getColor(R.color.text))
                setPadding(dp(14), dp(6), dp(14), dp(6))
                background = round(if (on) Color.WHITE else Color.parseColor("#14FFFFFF"), 15)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
        if (shownTab != tab) {
            shownTab = tab
            rows.removeAllViews()
            rowViews = if (tab == 0) p.games.map { gameRow(it) } else p.recent.map { recentRow(it) }
            rowViews.forEach { rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) }) }
            selected = selected.coerceIn(0, maxOf(0, rowViews.size - 1))
            scroll.scrollTo(0, 0)
        }
        if (rowViews.isEmpty()) showStatus(if (tab == 0) "Todavía no has jugado a ningún juego con logros." else "No has conseguido logros en los últimos 30 días.")
        rowViews.forEachIndexed { i, v ->
            v.background = if (i == selected) round(Color.parseColor("#1FFFFFFF"), 10).apply { setStroke(dp(1.5f), Color.WHITE) } else null
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

    private fun image(url: String?, size: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 6)
        url?.let { covers.loadUrl(it, dp(size)) { b -> setImageBitmap(b) } }
    }

    private fun gameRow(g: RetroAchievements.ProfileGame): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(7), dp(14), dp(7))
        val pct = if (g.total > 0) g.earned * 100 / g.total else 0
        val complete = g.total > 0 && g.earned >= g.total
        addView(image(g.icon, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(12.5f, R.color.text).apply { text = g.title; typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text(10f, R.color.text_2).apply {
                text = listOfNotNull(g.console, g.last?.let { "último logro ${formatDate(it)}" }).joinToString("  ·  ")
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(16) })
        addView(LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(text(11.5f, if (complete) R.color.ok else R.color.text).apply {
                text = if (complete) "✓ Completado" else "$pct %"
                typeface = font(R.font.inter_bold); gravity = Gravity.END
            }, LinearLayout.LayoutParams(-1, -2))
            val bar = FrameLayout(this@ProfileActivity).apply { background = round(Color.parseColor("#26FFFFFF"), 3) }
            val fill = View(this@ProfileActivity).apply {
                background = if (complete) round(getColor(R.color.ok), 3)
                else GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(getColor(R.color.accent_start), getColor(R.color.brand_accent))).apply { cornerRadius = dp(3).toFloat() }
            }
            bar.addView(fill, FrameLayout.LayoutParams(dp(150) * pct / 100, -1))
            addView(bar, LinearLayout.LayoutParams(dp(150), dp(5)).apply { topMargin = dp(4) })
            addView(text(9.5f, R.color.text_3).apply { text = "${g.earned} de ${g.total} logros"; gravity = Gravity.END },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }, LinearLayout.LayoutParams(dp(170), -2))
    }

    private fun recentRow(a: RetroAchievements.Recent): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(7), dp(14), dp(7))
        addView(image(a.badge, 44), LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(12.5f, R.color.text).apply { text = a.title; typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text(10f, R.color.text_2).apply { text = a.description; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(text(9.5f, R.color.brand_accent).apply { text = a.game; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = font(R.font.inter_semibold) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14); marginEnd = dp(16) })
        addView(LinearLayout(this@ProfileActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(text(11.5f, R.color.brand_accent).apply { text = "${a.points} pts"; typeface = font(R.font.inter_bold); gravity = Gravity.END },
                LinearLayout.LayoutParams(-1, -2))
            addView(text(9.5f, R.color.ok).apply { text = "✓ ${formatDate(a.date)}"; typeface = font(R.font.inter_semibold); gravity = Gravity.END },
                LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(dp(150), -2))
    }

    /** "2026-11-08 22:48:20" or "2026-11-08T22:48:20+00:00" → "8 nov 2026" */
    private fun formatDate(s: String): String {
        val d = listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd HH:mm:ss")
            .firstNotNullOfOrNull { p -> runCatching { SimpleDateFormat(p, Locale.US).parse(s) }.getOrNull() }
            ?: return s.take(10)
        return SimpleDateFormat("d MMM yyyy", es).format(d).replace(".", "")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val code = StickStatus.standardKey(event.device, event.keyCode)
        val n = rowViews.size
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < n - 1) { selected++; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_DPAD_UP -> if (selected > 0) { selected--; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0 && tab < 1) { tab++; selected = 0; Sounds.play(Sounds.Sound.TAB); render() }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0 && tab > 0) { tab--; selected = 0; Sounds.play(Sounds.Sound.TAB); render() }
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
}
