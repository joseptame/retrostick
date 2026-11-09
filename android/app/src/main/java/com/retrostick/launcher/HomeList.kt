package com.retrostick.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * The home screen's list view (docs/maquetas: "Inicio en lista"): the console's games on the left, the selected
 * one's sheet on the right over its own screenshot — cover, title, rating, year, genres, players, languages,
 * play stats, description in Spanish, buttons, and the trailer + screenshots row. MainActivity owns the
 * selection and the keys; this only draws. Sizes in dp for the 960×540 dp screen.
 */
class HomeList(private val ctx: Context, private val covers: CoverLoader) {
    sealed class Entry {
        abstract val key: String
        class Stick(val game: Game, val playing: Boolean) : Entry() { override val key get() = "s:" + game.key }
        class Drive(val item: DriveCollection.Item, val probe: Game) : Entry() { override val key get() = "d:" + item.key }
        class Header(val text: String) : Entry() { override val key get() = "h:$text" }
        /** «Añadir juegos» of [console] (ShopCard): its cartridge or disc, RetroShop's count */
        class Add(val console: Console) : Entry() { override val key get() = "add:${console.id}" }
    }

    /** What the sheet shows for the selected entry (MainActivity fills it from the game and its stats). */
    class Sheet(
        val title: String, val consoleId: String, val consoleName: String, val coverGame: Game?,
        val last: String, val time: String, val save: String, val saveOk: Boolean,
        /** "earned/total" of RetroAchievements; null = no "Logros" column */
        val achievements: String?,
        val playLabel: String, val languages: List<String>, val finished: String?,
        val manual: Boolean, val manualGlyph: String, val inDrive: Boolean,
        /** Confirm button: the PlayStation cross icon, or a letter ("A", "OK") for other controllers */
        val playCross: Boolean = true, val playLetter: String = "",
    )

    private val main = Handler(Looper.getMainLooper())
    private var pending: String? = null

    val backdrop = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.5f; visibility = View.GONE }
    val shade = View(ctx).apply {
        visibility = View.GONE
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.parseColor("#FF0D0B1F"), Color.parseColor("#F00D0B1F"), Color.parseColor("#B30D0B1F"), Color.parseColor("#8C0D0B1F")),
        )
    }
    val view = FrameLayout(ctx).apply { visibility = View.GONE; clipChildren = false }

    private val listTitle = text(10.5f, R.color.text_2).apply { typeface = font(R.font.inter_semibold) }
    private val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false; isFocusable = false; overScrollMode = View.OVER_SCROLL_NEVER
        addView(rows, FrameLayout.LayoutParams(-1, -2))
    }
    private val cover = CoverView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; setBackgroundResource(R.drawable.bg_cover); elevation = dp(10).toFloat() }
    private val title = text(24f, R.color.text).apply { typeface = font(R.font.inter_extrabold); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val meta = text(11.5f, R.color.brand_accent).apply { typeface = font(R.font.inter_semibold) }
    private val langs = text(10f, R.color.text_2)
    private val statLast = stat(); private val statTime = stat(); private val statAch = stat(); private val statSave = stat()
    private val desc = text(11f, R.color.text_2).apply { setLineSpacing(0f, 1.3f); maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
    /** Pulsing bars while IGDB answers (description + thumbnails) */
    private val loading = LoadingBars(ctx, lines = 3, thumbs = 4)
    private val playBtn = button(true)
    private val manualBtn = button(false)
    private val mediaTitle = text(8.5f, R.color.text_3).apply { letterSpacing = 0.15f; text = "TRÁILER Y CAPTURAS" }
    private val mediaRow = LinearLayout(ctx).apply { clipChildren = false; clipToPadding = false }
    private val mediaViews = List(MAX_MEDIA) {
        ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 7) }
    }

    /** -1 = the list has the focus; otherwise the trailer / screenshot with the focus. */
    var mediaFocus = -1
        private set
    /** Trailer id and screenshots of the shown game (once IGDB answered). */
    var media: IgdbInfo? = null
        private set
    private var rowViews = listOf<View>()
    private var rowKeys = listOf<String>()
    private var shownKey: String? = null

    init {
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(listTitle, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(6) })
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        val stats = LinearLayout(ctx).apply {
            addView(statLast.first, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
            addView(statTime.first, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
            addView(statAch.first, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(24) })
            addView(statSave.first)
        }
        val infoCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(meta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(langs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
            addView(stats, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        }
        val top = LinearLayout(ctx).apply {
            addView(cover, LinearLayout.LayoutParams(dp(132), dp(132)).apply { marginEnd = dp(20) })
            addView(infoCol, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val buttons = LinearLayout(ctx).apply {
            addView(playBtn, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(10) })
            addView(manualBtn, LinearLayout.LayoutParams(-2, dp(32)))
        }
        mediaViews.forEach { mediaRow.addView(it, LinearLayout.LayoutParams(dp(104), dp(58)).apply { marginEnd = dp(8) }) }
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(top)
            addView(desc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(loading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            addView(buttons, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(mediaTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            addView(mediaRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
        }
        view.addView(left, FrameLayout.LayoutParams(dp(286), -1).apply { leftMargin = dp(40) })
        view.addView(right, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(352); rightMargin = dp(40) })
        setStatLabels()
    }

    fun show(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.GONE
        view.visibility = v
        shade.visibility = v
        backdrop.visibility = if (visible && backdrop.drawable != null) View.VISIBLE else View.GONE
        if (!visible) { mediaFocus = -1; loading.show(false) }
    }

    /** Draws the list (rebuilt only when its entries change) and the sheet of the selected entry. */
    fun bind(listName: String, entries: List<Entry>, selected: Int, focused: Boolean, sheet: Sheet?) {
        listTitle.text = listName
        val keys = entries.map { it.key }
        if (keys != rowKeys) {
            rowKeys = keys
            rows.removeAllViews()
            rowViews = entries.map { e -> row(e).also { rows.addView(it) } }
        }
        rowViews.forEachIndexed { i, v -> styleRow(v, entries[i], i == selected && focused && mediaFocus < 0) }
        rowViews.getOrNull(selected)?.let { v ->
            scroll.post {
                val h = scroll.height
                when {
                    v.top < scroll.scrollY -> scroll.smoothScrollTo(0, v.top - dp(4))
                    v.bottom > scroll.scrollY + h -> scroll.smoothScrollTo(0, v.bottom - h + dp(4))
                }
            }
        }
        bindSheet(sheet, entries.getOrNull(selected))
    }

    private fun bindSheet(sheet: Sheet?, entry: Entry?) {
        val key = entry?.key
        if (key != shownKey) {
            shownKey = key
            mediaFocus = -1
            media = null
            backdrop.setImageDrawable(null)
            backdrop.visibility = View.GONE
            cover.setImageDrawable(null)
        }
        if (sheet == null) {
            // "Añade juegos desde RetroShop"
            title.text = "Descubre más juegos"
            meta.text = "RetroShop"
            langs.text = ""
            listOf(statLast, statTime, statAch, statSave).forEach { it.first.visibility = View.GONE }
            desc.text = "Abre la tienda para buscar y descargar juegos directamente al stick con el mando."
            setPlay("Abrir la tienda", true, "", true)
            manualBtn.visibility = View.GONE
            cover.setImageBitmap(null)
            mediaTitle.visibility = View.GONE
            mediaRow.visibility = View.GONE
            desc.visibility = View.VISIBLE
            loading.show(false)
            return
        }
        listOf(statLast, statTime, statSave).forEach { it.first.visibility = View.VISIBLE }
        title.text = sheet.title
        statLast.second.text = sheet.last
        statTime.second.text = sheet.time
        statAch.first.visibility = if (sheet.achievements != null) View.VISIBLE else View.GONE
        statAch.second.text = sheet.achievements
        statSave.second.text = sheet.save
        statSave.second.setTextColor(ctx.getColor(if (sheet.saveOk) R.color.ok else R.color.text))
        setPlay(sheet.playLabel, sheet.playCross, sheet.playLetter, mediaFocus < 0)
        manualBtn.visibility = if (sheet.manual) View.VISIBLE else View.GONE
        styleButton(manualBtn, false)
        manualBtn.text = PillSpan.text(sheet.manualGlyph, "Manual", manualBtn.currentTextColor, ctx.resources.displayMetrics.density,
            if (sheet.manualGlyph == "Share") ctx.getDrawable(R.drawable.ic_share) else null)
        langs.text = langs.withIcons(android.text.SpannableStringBuilder().apply {
            if (sheet.languages.isNotEmpty()) {
                append("Idiomas   ")
                sheet.languages.forEachIndexed { i, l ->
                    if (i > 0) append("  ·  ")
                    val s = length
                    append(l)
                    if (l == "Español") {
                        setSpan(android.text.style.ForegroundColorSpan(ctx.getColor(R.color.ok)), s, length, 0)
                        setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                    }
                }
            }
            sheet.finished?.let { if (isNotEmpty()) append("      "); append("$TROPHY $it") }
        })
        sheet.coverGame?.let { g ->
            if (cover.tag != g.key) {
                cover.tag = g.key
                covers.load(g, dp(132)) { b -> if (cover.tag == g.key) cover.setImageBitmap(b ?: logoCover(ctx, g.console, dp(132))) }
            }
        }
        // The ficha kept on the stick (Fichas): at once; fetched (or refreshed) when missing or old
        val entry = Fichas.cached(ctx, sheet.title, sheet.consoleId)
        val known = entry != null || Fichas.failed(sheet.title, sheet.consoleId)
        val i = entry?.info
        meta.text = meta.withIcons(listOfNotNull(
            i?.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') },
            i?.year?.toString(),
            i?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
            i?.players?.takeIf { it >= 2 }?.let { "$PLAYERS Hasta $it" },
            sheet.consoleName.takeIf { i == null } ,
        ).joinToString("   ·   "))
        // Loading: the pulsing bars stand in for the description and the screenshots
        desc.visibility = if (known) View.VISIBLE else View.GONE
        loading.show(!known && view.visibility == View.VISIBLE)
        desc.text = when {
            !known -> ""
            i?.description != null -> i.description
            ShopApi.token == null -> "Vincula RetroShop (Retro Ajustes) para ver la descripción, el tráiler y las capturas."
            else -> "Sin descripción."
        }
        media = i
        renderMedia()
        if (entry == null || entry.stale) fetch(sheet)
        i?.screenshots?.firstOrNull()?.let { url ->
            if (backdrop.tag != url) {
                backdrop.tag = url
                covers.loadUrl(url, dp(480)) { b -> if (backdrop.tag == url && view.visibility == View.VISIBLE) { backdrop.setImageBitmap(b); backdrop.visibility = if (b != null) View.VISIBLE else View.GONE } }
            }
        }
    }

    /** A ficha not on the stick yet: asked for a short moment after the selection settles (Fichas tells MainActivity). */
    private fun fetch(sheet: Sheet) {
        val key = "${sheet.consoleId}|${sheet.title}"
        pending = key
        main.postDelayed({ if (pending == key) Fichas.request(ctx, sheet.title, sheet.consoleId) }, 250)
    }

    /** Trailer thumbnail first (with a play badge), then screenshots. */
    val mediaUrls: List<String>
        get() {
            val i = media ?: return emptyList()
            return Fichas.mediaUrls(i, MAX_MEDIA)
        }

    private fun renderMedia() {
        val urls = mediaUrls
        val has = urls.isNotEmpty()
        mediaTitle.visibility = if (has) View.VISIBLE else View.GONE
        mediaRow.visibility = if (has) View.VISIBLE else View.GONE
        mediaViews.forEachIndexed { i, v ->
            val url = urls.getOrNull(i)
            v.visibility = if (url == null) View.GONE else View.VISIBLE
            if (url == null) return@forEachIndexed
            if (v.tag != url) {
                v.tag = url
                v.setImageDrawable(null)
                covers.loadUrl(url, dp(104)) { b -> if (v.tag == url) v.setImageBitmap(b) }
            }
            val focus = i == mediaFocus
            val play = if (i == 0 && media?.trailerId != null) ctx.getDrawable(R.drawable.ic_shop_play_badge) else null
            val border = if (focus) ctx.getDrawable(R.drawable.fg_hero_border) else null
            v.foreground = listOfNotNull(play, border).let { if (it.size > 1) android.graphics.drawable.LayerDrawable(it.toTypedArray()) else it.firstOrNull() }
            v.scaleX = if (focus) 1.08f else 1f
            v.scaleY = v.scaleX
        }
    }

    /** → / ← inside the media row; returns false at the left end (focus goes back to the list). */
    fun moveMedia(delta: Int): Boolean {
        val n = mediaUrls.size
        if (n == 0) return false
        val next = mediaFocus + delta
        if (next < 0) { mediaFocus = -1; renderMedia(); return false }
        mediaFocus = next.coerceAtMost(n - 1)
        renderMedia()
        return true
    }

    fun leaveMedia() { mediaFocus = -1; renderMedia() }

    // ---- rows ----

    private fun row(e: Entry): View = when (e) {
        is Entry.Header -> text(9f, R.color.text_3).apply {
            text = e.text.uppercase(); letterSpacing = 0.14f
            setPadding(dp(6), dp(12), 0, dp(4))
        }
        else -> LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(5), dp(4), dp(8), dp(4))
            val thumb = CoverView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 5) }
            addView(thumb, LinearLayout.LayoutParams(dp(38), dp(38)))
            val texts = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            val t = text(11.5f, R.color.text).apply { typeface = font(R.font.inter_semibold); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; tag = "t" }
            val s = text(9f, R.color.text_3).apply { maxLines = 1; tag = "s" }
            texts.addView(t); texts.addView(s)
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            val badge = text(8f, R.color.button_text).apply { typeface = font(R.font.inter_bold); tag = "b"; setPadding(dp(5), dp(1), dp(5), dp(1)) }
            addView(badge)
            when (e) {
                is Entry.Stick -> {
                    t.text = e.game.title
                    s.text = rowSub(e.game)
                    badge.text = badge.withIcons(when { e.playing -> "▶ Jugando"; e.game.finished != null -> TROPHY; e.game.favorite -> "★"; else -> "" })
                    covers.load(e.game, dp(38)) { b -> thumb.setImageBitmap(b ?: logoCover(ctx, e.game.console, dp(38))) }
                }
                is Entry.Drive -> {
                    t.text = e.item.title
                    s.text = "En tu Drive · ${android.text.format.Formatter.formatShortFileSize(ctx, e.item.size)}"
                    badge.text = DriveCollection.jobFor(e.item)?.takeIf { it.state == DriveCollection.State.RUNNING }?.let { "${(it.progress * 100).toInt()} %" } ?: "Drive"
                    thumb.alpha = 0.65f
                    covers.load(e.probe, dp(38)) { b -> thumb.setImageBitmap(b ?: logoCover(ctx, e.item.console, dp(38))) }
                }
                is Entry.Add -> {
                    t.text = "Añadir juegos"
                    s.text = ShopCard.countText(ctx, e.console) { s.text = it }
                    // The console's cartridge or disc on its colour, like the shelf's card
                    thumb.background = ShopCard.background(e.console, dp(5).toFloat())
                    thumb.setImageBitmap(null)
                    (thumb.parent as? LinearLayout)?.let { row ->
                        val i = row.indexOfChild(thumb)
                        row.removeView(thumb)
                        row.addView(FrameLayout(ctx).apply {
                            background = ShopCard.background(e.console, dp(5).toFloat())
                            addView(ConsoleMediaView(ctx, e.console), FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                        }, i, LinearLayout.LayoutParams(dp(38), dp(38)))
                    }
                }
                else -> Unit
            }
            badge.visibility = if (badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun rowSub(g: Game): String {
        if (g.lastPlayed == 0L) return "Sin jugar"
        val mins = (System.currentTimeMillis() - g.lastPlayed) / 60_000
        val whenText = when {
            mins < 60 -> "Hace ${mins.coerceAtLeast(1)} min"
            mins < 24 * 60 -> "Hoy"
            mins < 48 * 60 -> "Ayer"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale("es", "ES")).format(g.lastPlayed)
        }
        val s = g.runtimeSeconds
        val time = when { s >= 3600 -> "${s / 3600} h ${s % 3600 / 60} min"; s >= 60 -> "${s / 60} min"; s > 0 -> "< 1 min"; else -> null }
        return listOfNotNull(whenText, time).joinToString(" · ")
    }

    private fun styleRow(v: View, e: Entry, sel: Boolean) {
        if (e is Entry.Header) return
        v.background = if (sel) round(Color.WHITE, 9) else null
        (v as LinearLayout).let { r ->
            r.findViewWithTag<TextView>("t")?.setTextColor(if (sel) Color.parseColor("#17132F") else ctx.getColor(R.color.text))
            r.findViewWithTag<TextView>("s")?.setTextColor(if (sel) Color.parseColor("#5B5585") else ctx.getColor(R.color.text_3))
            r.findViewWithTag<TextView>("b")?.background = round(if (sel) ctx.getColor(R.color.accent_start) else ctx.getColor(R.color.text), 4)
            r.findViewWithTag<TextView>("b")?.setTextColor(if (sel) Color.WHITE else ctx.getColor(R.color.button_text))
        }
    }

    // ---- helpers ----

    private fun stat(): Pair<View, TextView> {
        val value = text(12.5f, R.color.text).apply { typeface = font(R.font.inter_semibold) }
        val label = text(7.5f, R.color.text_3).apply { letterSpacing = 0.14f }
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(label); addView(value) }
        box.tag = label
        return box to value
    }

    fun setStatLabels() {
        (statLast.first.tag as TextView).text = "ÚLTIMA PARTIDA"
        (statTime.first.tag as TextView).text = "TIEMPO JUGADO"
        (statAch.first.tag as TextView).text = "LOGROS"
        (statSave.first.tag as TextView).text = "PARTIDA GUARDADA"
    }

    /** The play button with the same confirm glyph as the covers view: the ✕ in a disc (or a boxed letter). */
    private fun setPlay(label: String, cross: Boolean, letter: String, primary: Boolean) {
        styleButton(playBtn, primary)
        if (cross) {
            // Same glyph as the covers view's button: the ✕ in an outlined circle, in the button's text colour
            val d = ctx.getDrawable(R.drawable.ic_btn_cross_outline)!!.mutate().apply { setTint(playBtn.currentTextColor); setBounds(0, 0, dp(18), dp(18)) }
            playBtn.setCompoundDrawablesRelative(d, null, null, null)
            playBtn.compoundDrawablePadding = dp(8)
            playBtn.text = label
        } else {
            playBtn.setCompoundDrawablesRelative(null, null, null, null)
            playBtn.text = PillSpan.text(letter, label, playBtn.currentTextColor, ctx.resources.displayMetrics.density)
        }
    }

    private fun button(primary: Boolean) = text(12.5f, if (primary) R.color.button_text else R.color.text).apply {
        typeface = font(R.font.inter_extrabold); gravity = Gravity.CENTER; setPadding(dp(16), 0, dp(16), 0)
        styleButton(this, primary)
    }

    private fun styleButton(b: TextView, primary: Boolean) {
        b.background = round(if (primary) Color.WHITE else Color.parseColor("#1FFFFFFF"), 8).apply {
            if (!primary) setStroke(dp(1), Color.parseColor("#2EFFFFFF"))
        }
        b.setTextColor(if (primary) ctx.getColor(R.color.button_text) else ctx.getColor(R.color.text))
    }

    private fun text(sp: Float, color: Int) = TextView(ctx).apply {
        setTextColor(ctx.getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font(R.font.inter_regular)
    }
    private fun font(id: Int) = ctx.resources.getFont(id)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    // Five 104 dp thumbnails fill the sheet's width; a sixth would be cut by the screen edge
    companion object { const val MAX_MEDIA = 5 }
}
