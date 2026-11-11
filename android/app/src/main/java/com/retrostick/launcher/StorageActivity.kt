package com.retrostick.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.StatFs
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * Retro Ajustes → Espacio (design: https://claude.ai/artifact/4yvir7DWrMsidGBjgwQkfP): what each console takes on
 * the stick and the games that free the most (big and not played for a while), to take off the stick. Taking a game
 * off is DriveSync.removeGame without Drive's bin: the ROM goes to the Drive collection (the home screen's Drive row
 * can pass it back) and the saves stay. RetroShop sends here when a download doesn't fit (EXTRA_NEED).
 * Sizes in dp for the 960×540 dp screen.
 */
class StorageActivity : Activity() {
    companion object {
        /** Bytes a RetroShop download needs: shown at the top («Faltan … para …»). */
        const val EXTRA_NEED = "need"
        const val EXTRA_NEED_TITLE = "needTitle"
        /** A game is "recent" (listed after the rest) when played in the last two weeks. */
        private const val RECENT_MS = 14L * 24 * 3600_000
    }

    private class Row(val game: Game, val bytes: Long)

    private lateinit var usage: TextView
    private lateinit var freeText: TextView
    private lateinit var bar: LinearLayout
    private lateinit var legend: LinearLayout
    private lateinit var listTitle: TextView
    private lateinit var rows: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var action: TextView
    private lateinit var loading: LoadingBars
    private val io = Executors.newSingleThreadExecutor()

    private var list = listOf<Row>()
    private val marked = mutableSetOf<String>()
    private var selected = 0
    private var rowViews = listOf<View>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kicker = text(10f, R.color.brand_accent).apply { text = "RETRO AJUSTES"; letterSpacing = 0.18f; typeface = font(R.font.inter_semibold) }
        val title = text(22f, R.color.text).apply { text = "Espacio en el stick"; typeface = font(R.font.inter_extrabold) }
        usage = text(13f, R.color.text)
        freeText = text(13f, R.color.ok).apply { typeface = font(R.font.inter_bold); gravity = Gravity.END }
        bar = LinearLayout(this).apply { clipToOutline = true; background = round(Color.parseColor("#1FFFFFFF"), 6) }
        legend = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        listTitle = text(11.5f, R.color.brand_accent)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; overScrollMode = View.OVER_SCROLL_NEVER
            addView(rows, FrameLayout.LayoutParams(-1, -2))
        }
        action = text(12.5f, R.color.button_text).apply {
            typeface = font(R.font.inter_bold); setPadding(dp(16), dp(9), dp(16), dp(9))
            background = round(Color.WHITE, 10); visibility = View.INVISIBLE
        }
        loading = LoadingBars(this, lines = 5)
        val hints = text(10.5f, R.color.chip_text)
        setHints(hints, "▲ ▼ Moverse      [X]  Marcar      [OPT]  Borrar los marcados      [O]  Volver")

        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@StorageActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), dp(40))
                addView(kicker)
                addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                addView(LinearLayout(this@StorageActivity).apply {
                    addView(usage, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(freeText, LinearLayout.LayoutParams(-2, -2))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                addView(bar, LinearLayout.LayoutParams(-1, dp(14)).apply { topMargin = dp(8) })
                addView(legend, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                addView(listTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(6) })
                addView(loading, LinearLayout.LayoutParams(-1, -2))
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(action, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(40); bottomMargin = dp(10) })
            addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40); bottomMargin = dp(16) })
        })
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdownNow()
    }

    /** Sizes are read off the main thread: every ROM (and the discs / tracks it lists), RetroArch and the saves. */
    private fun load() {
        loading.show(true)
        io.execute {
            val games = Library(this).scan()
            val rowsAll = games.values.flatten().map { Row(it, DriveSync.romFiles(it).sumOf { f -> f.length() }) }
            fun dirBytes(path: String) = File(path).walkTopDown().filter { it.isFile }.sumOf { it.length() }
            val retroarch = dirBytes("$SDCARD/RetroArch") + dirBytes("$SDCARD/Partidas")
            val stat = StatFs(SDCARD)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                loading.show(false)
                val now = System.currentTimeMillis()
                // Not played lately first, biggest first; then the recent ones, biggest first
                list = rowsAll.sortedWith(compareBy<Row> { now - it.game.lastPlayed < RECENT_MS }.thenByDescending { it.bytes })
                marked.retainAll(list.map { it.game.key }.toSet())
                selected = selected.coerceIn(0, maxOf(0, list.size - 1))
                renderSummary(rowsAll, retroarch, stat.totalBytes, stat.availableBytes)
                renderRows()
            }
        }
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(this, bytes)

    private fun renderSummary(all: List<Row>, retroarch: Long, total: Long, available: Long) {
        val used = total - available
        usage.text = "${size(used)} usados de ${size(total)}"
        freeText.text = "${size(available)} libres"
        intent.getLongExtra(EXTRA_NEED, 0).takeIf { it > 0 }?.let { need ->
            val name = intent.getStringExtra(EXTRA_NEED_TITLE) ?: "la descarga"
            usage.text = "${usage.text}  ·  ${name} necesita ${size(need)}"
        }
        // Each console's games, then RetroArch with the saves, the rest of the stick (Android, apps) and the free space
        val byConsole = CONSOLES.map { c -> c to all.filter { it.game.console == c }.sumOf { it.bytes } }.filter { it.second > 0 }
            .sortedByDescending { it.second }
        val parts = byConsole.map { (c, b) -> Triple(c.name, b, ShopCard.style(c).accent) } +
            listOf(
                Triple("RetroArch y partidas", retroarch, Color.parseColor("#8B84A8")),
                Triple("Android y apps", (used - byConsole.sumOf { it.second } - retroarch).coerceAtLeast(0), Color.parseColor("#4A4566")),
            )
        bar.removeAllViews()
        (parts + Triple("Libre", available, Color.TRANSPARENT)).forEach { (_, b, c) ->
            if (b <= 0 || total <= 0) return@forEach
            bar.addView(View(this).apply { setBackgroundColor(c) }, LinearLayout.LayoutParams(0, -1, b.toFloat() / total).apply { marginEnd = dp(1) })
        }
        legend.removeAllViews()
        parts.filter { it.second > 0 }.forEach { (name, b, c) ->
            legend.addView(View(this).apply { background = round(c, 2) }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(5) })
            legend.addView(text(10f, R.color.text_2).apply { text = "$name ${size(b)}"; maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        }
        val auth = DriveAuth(this)
        listTitle.text = if (auth.isConfigured && auth.isConnected)
            "Juegos que más espacio liberan · al borrarlos eliges si se quedan en tu colección de Drive; las partidas no se tocan"
        else "Juegos que más espacio liberan · sin Drive conectado se borran del stick (las partidas se quedan)"
    }

    private fun renderRows() {
        rows.removeAllViews()
        val now = System.currentTimeMillis()
        rowViews = list.mapIndexed { i, r ->
            val on = r.game.key in marked
            val box = text(11f, R.color.button_text).apply {
                text = if (on) "✓" else ""; gravity = Gravity.CENTER; typeface = font(R.font.inter_extrabold)
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    if (on) setColor(getColor(R.color.ok)) else setStroke(dp(2), Color.WHITE)
                }
            }
            val played = when {
                r.game.lastPlayed <= 0 -> "Nunca jugado"
                else -> "Jugado " + ago(now - r.game.lastPlayed)
            }
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(7), dp(12), dp(7))
                addView(box, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(12) })
                addView(text(12f, R.color.text).apply { text = r.game.title; typeface = font(R.font.inter_semibold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(11f, R.color.text_2).apply { text = r.game.console.name; maxLines = 1 }, LinearLayout.LayoutParams(dp(130), -2))
                addView(text(11.5f, R.color.text).apply { text = size(r.bytes); gravity = Gravity.END }, LinearLayout.LayoutParams(dp(70), -2))
                addView(text(11f, R.color.text_2).apply { text = played; gravity = Gravity.END; maxLines = 1 }, LinearLayout.LayoutParams(dp(150), -2))
            }.also { rows.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) }) }
        }
        if (list.isEmpty()) rows.addView(text(12f, R.color.text_2).apply { text = "No hay juegos en el stick." })
        renderSelection()
    }

    private fun renderSelection() {
        rowViews.forEachIndexed { i, v ->
            v.background = if (i == selected) GradientDrawable().apply {
                setColor(Color.parseColor("#5B3FD6")); cornerRadius = dp(10).toFloat(); setStroke(dp(2), Color.WHITE)
            } else null
        }
        rowViews.getOrNull(selected)?.let { v -> scroll.post { scroll.smoothScrollTo(0, (v.top - scroll.height / 2 + v.height / 2).coerceAtLeast(0)) } }
        val chosen = list.filter { it.game.key in marked }
        action.visibility = if (chosen.isEmpty()) View.INVISIBLE else View.VISIBLE
        action.text = "Borrar ${chosen.size} ${if (chosen.size == 1) "juego" else "juegos"} · libera ${size(chosen.sumOf { it.bytes })}"
    }

    private fun ago(ms: Long): String {
        val days = ms / (24 * 3600_000)
        return when {
            days < 1 -> "hoy"
            days == 1L -> "ayer"
            days < 14 -> "hace $days días"
            days < 60 -> "hace ${days / 7} semanas"
            days < 365 -> "hace ${days / 30} meses"
            else -> "hace más de un año"
        }
    }

    /** Takes the marked games off the stick, one after another (DriveSync runs them in order). */
    private fun confirmRemove() {
        val chosen = list.filter { it.game.key in marked }
        if (chosen.isEmpty() || busy) return
        val auth = DriveAuth(this)
        val withDrive = auth.isConfigured && auth.isConnected
        val names = chosen.take(6).joinToString("\n") { "·  ${it.game.title} (${size(it.bytes)})" } +
            if (chosen.size > 6) "\n·  y ${chosen.size - 6} más" else ""
        val count = "${chosen.size} ${if (chosen.size == 1) "juego" else "juegos"}"
        if (!withDrive) {
            AlertDialog.Builder(this, R.style.Dialog_RetroStick)
                .setTitle("Borrar $count del stick")
                .setMessage("$names\n\nDrive no está conectado: se borran del stick. Puedes volver a bajarlos de RetroShop. Las partidas guardadas no se tocan.")
                .setPositiveButton("Borrar") { _, _ -> remove(chosen, alsoDrive = false) }
                .setNegativeButton("Cancelar", null)
                .showForPad()
            return
        }
        // As in Detalles: off the stick only (kept in the Drive collection) or also from Drive (its bin, 30 days)
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Borrar $count · ${size(chosen.sumOf { it.bytes })}")
            .setItems(arrayOf(
                "Quitar solo del stick (se quedan en tu colección de Drive)",
                "Borrar también de Drive (van a la papelera de Drive)",
            )) { _, which ->
                if (which == 0) remove(chosen, alsoDrive = false)
                else AlertDialog.Builder(this, R.style.Dialog_RetroStick)
                    .setTitle("Borrar $count del stick y de Drive")
                    .setMessage("$names\n\nVan a la papelera de Drive (se pueden recuperar durante 30 días). Las partidas guardadas no se tocan.")
                    .setPositiveButton("Borrar") { _, _ -> remove(chosen, alsoDrive = true) }
                    .setNegativeButton("Cancelar", null)
                    .showForPad()
            }
            .setNegativeButton("Cancelar", null)
            .showForPad()
    }

    private fun remove(chosen: List<Row>, alsoDrive: Boolean) {
        busy = true
        Toast.makeText(this, "Borrando ${chosen.size} ${if (chosen.size == 1) "juego" else "juegos"}…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        var left = chosen.size
        val errors = mutableListOf<String>()
        chosen.forEach { r ->
            DriveSync.removeGame(this, r.game, alsoDrive) { error ->
                if (error != null) errors += "${r.game.title}: $error" else Fichas.forget(app, r.game)
                if (--left == 0) {
                    busy = false
                    Sounds.play(if (errors.isEmpty()) Sounds.Sound.BACK else Sounds.Sound.ERROR)
                    Toast.makeText(app, if (errors.isEmpty()) "Hecho: ${size(chosen.sumOf { it.bytes })} libres más"
                        else "No se pudo borrar ${errors.joinToString("; ")}", Toast.LENGTH_LONG).show()
                    DriveCollection.refresh(app, force = true)
                    marked.clear()
                    if (!isDestroyed) load()
                }
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        followPad(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < list.size - 1) { selected++; Sounds.play(Sounds.Sound.MOVE); renderSelection() }
            KeyEvent.KEYCODE_DPAD_UP -> if (selected > 0) { selected--; Sounds.play(Sounds.Sound.MOVE); renderSelection() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> list.getOrNull(selected)?.let { r ->
                if (!marked.remove(r.game.key)) marked += r.game.key
                Sounds.play(Sounds.Sound.MOVE)
                renderRows()
            }
            KeyEvent.KEYCODE_BUTTON_START -> if (event.repeatCount == 0) confirmRemove()
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
}
