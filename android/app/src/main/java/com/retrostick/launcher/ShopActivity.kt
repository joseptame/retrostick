package com.retrostick.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/**
 * RetroShop (Retro Ajustes): browse the webapp's catalog with the controller and download games to the stick.
 * Three zones like the home screen: console tabs, filters and the cover grid; L1/R1 switch console anywhere.
 */
class ShopActivity : Activity() {
    private enum class Zone { TABS, FILTERS, GRID }
    private enum class Sort(val label: String) { RATING("Mejor valorados"), NAME("A–Z"), YEAR("Más nuevos") }

    private val io = Executors.newSingleThreadExecutor()
    private val covers by lazy { CoverLoader(this) }

    private var consoleIndex = 0
    private var zone = Zone.GRID
    private var filterIndex = 0
    private var sort = Sort.RATING
    private var genre: String? = null
    /** Text search (🔍 chip or the top face button): every word must appear in the title */
    private var query: String? = null
    private var catalog: ShopCatalog? = null
    private var loading = false
    private var error: String? = null
    private var shown: List<ShopGame> = emptyList()
    private var sel = 0
    private var topRow = 0
    private var owned: Set<String> = emptySet()
    private var padStyle = PadStyle.REMOTE

    private lateinit var tabs: LinearLayout
    private lateinit var filters: LinearLayout
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private lateinit var subtitle: TextView
    private lateinit var message: TextView
    private val tabViews = mutableListOf<View>()
    private val filterViews = mutableListOf<TextView>()
    private val cards = mutableListOf<Card>()

    // Pairing (first time): QR + code on screen, the phone leaves the pass in Drive (ShopPairing).
    private val main = Handler(Looper.getMainLooper())
    private lateinit var pairPanel: View
    private lateinit var qrView: ImageView
    private lateinit var pairCodeView: TextView
    private lateinit var pairNote: TextView
    private var pairCode: String? = null
    private val pollPairing = object : Runnable {
        override fun run() {
            val code = pairCode ?: return
            io.execute {
                val paired = runCatching { ShopPairing.poll(this@ShopActivity, code) }.getOrDefault(false)
                runOnUiThread {
                    if (pairCode != code) return@runOnUiThread
                    if (paired) {
                        pairCode = null
                        ShopApi.token = ShopPairing.token(this@ShopActivity)
                        pairPanel.visibility = View.GONE
                        loadCatalog()
                    } else {
                        main.postDelayed(this, POLL_MS)
                    }
                }
            }
        }
    }

    private val downloadsListener: () -> Unit = {
        refreshOwned()
        renderStatus()
        renderGrid()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consoleIndex = CONSOLES.indexOfFirst { it.id == intent.getStringExtra(EXTRA_CONSOLE) }.coerceAtLeast(0)
        setContentView(build())
        ShopApi.token = ShopPairing.token(this)
        if (ShopApi.token == null) startPairing() else loadCatalog()
    }

    private fun startPairing() {
        pairPanel.visibility = View.VISIBLE
        message.visibility = View.GONE
        if (!DriveAuth(this).isConnected) {
            pairCode = null
            qrView.setImageDrawable(null)
            pairCodeView.text = ""
            pairNote.text = "Primero conecta Google Drive en Retro Ajustes → Cuenta de Drive: el móvil deja ahí la vinculación."
            return
        }
        val code = ShopPairing.newCode()
        pairCode = code
        pairCodeView.text = code.substring(0, 4) + " " + code.substring(4)
        pairNote.text = "En el móvil: app RetroStick → Stick → Escanear QR de la tele.\nO abre la cámara y escanéalo."
        io.execute {
            val bmp = runCatching { ShopPairing.qrBitmap(code, dp(210)) }
            runOnUiThread {
                bmp.onSuccess { qrView.setImageBitmap(it) }
                    .onFailure { pairNote.text = "No se pudo generar el QR (${it.message}). Escribe el código en la app." }
            }
        }
        main.removeCallbacks(pollPairing)
        main.postDelayed(pollPairing, POLL_MS)
    }

    override fun onResume() {
        super.onResume()
        padStyle = StickStatus.firstPadStyle()
        ShopDownloads.addListener(downloadsListener)
        downloadsListener()
        if (pairCode != null) main.postDelayed(pollPairing, POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        ShopDownloads.removeListener(downloadsListener)
        main.removeCallbacks(pollPairing)
    }

    // ---- Layout (built in code: one screen, 960×540 dp) ----

    private fun build(): View {
        val root = FrameLayout(this).apply { setBackgroundResource(R.drawable.bg_screen); clipChildren = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(40), dp(24), dp(40), dp(16))
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // Header: brand + collection, download status on the right
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = android.text.SpannableString("RetroShop").apply {
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), 5, 9, 0)
            }
            textSize = 17f
            typeface = resources.getFont(R.font.inter_extrabold)
        })
        subtitle = text(10f, R.color.text_3).apply { setPadding(dp(14), dp(3), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        header.addView(subtitle, LinearLayout.LayoutParams(0, -2, 1f))
        status = text(10.5f, R.color.text_2).apply { gravity = Gravity.END; maxLines = 1 }
        header.addView(status, LinearLayout.LayoutParams(dp(330), -2))
        col.addView(header)

        tabs = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(tabs, LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(16) })
        CONSOLES.forEach { c ->
            val v = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                addView(ImageView(this@ShopActivity).apply {
                    setImageResource(c.logo)
                    adjustViewBounds = true
                    maxWidth = dp(92)
                }, LinearLayout.LayoutParams(-2, dp(15)))
            }
            tabs.addView(v, LinearLayout.LayoutParams(dp(118), dp(36)).apply { marginEnd = dp(9) })
            tabViews += v
        }

        filters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(filters, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(12) })
        // 🔍 Buscar · the sorts · Género
        repeat(Sort.values().size + 2) {
            val v = text(10f, R.color.chip_text).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
            }
            filters.addView(v, LinearLayout.LayoutParams(-2, dp(26)).apply { marginEnd = dp(7) })
            filterViews += v
        }

        // The focused card grows 6 %: nothing may clip it.
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        col.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        repeat(ROWS) {
            val row = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            repeat(COLUMNS) {
                val card = Card()
                row.addView(card.view, LinearLayout.LayoutParams(dp(CARD_DP), -2).apply { marginEnd = dp(GAP_DP) })
                cards += card
            }
        }
        message = text(13f, R.color.text_2).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(message, FrameLayout.LayoutParams(-1, -1))

        // Pairing panel over everything until the stick has a pass
        qrView = ImageView(this).apply { setBackgroundColor(Color.WHITE) }
        pairCodeView = text(26f, R.color.text).apply {
            typeface = resources.getFont(R.font.inter_extrabold)
            letterSpacing = 0.12f
        }
        pairNote = text(11.5f, R.color.text_2).apply { setLineSpacing(0f, 1.3f) }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(20f, R.color.text).apply {
                text = "Vincula RetroShop"
                typeface = resources.getFont(R.font.inter_extrabold)
            })
            addView(text(12f, R.color.text_3).apply {
                text = "Una sola vez: así la tienda usa tu cuenta de la webapp."
                setPadding(0, dp(6), 0, dp(18))
            })
            addView(text(9f, R.color.text_3).apply { text = "CÓDIGO"; letterSpacing = 0.15f })
            addView(pairCodeView)
            addView(pairNote, LinearLayout.LayoutParams(dp(330), -2).apply { topMargin = dp(16) })
        }
        pairPanel = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_screen)
            isClickable = true
            visibility = View.GONE
            addView(qrView, LinearLayout.LayoutParams(dp(210), dp(210)).apply { marginEnd = dp(40) })
            addView(info)
        }
        root.addView(pairPanel, FrameLayout.LayoutParams(-1, -1))

        col.addView(text(9.5f, R.color.text_3).apply {
            text = "OK  Ver juego     △ / Y  Buscar     L1 / R1  Consola     B  Volver"
            gravity = Gravity.END
        })
        return root
    }

    private inner class Card {
        val cover = ImageView(this@ShopActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.bg_cover)
        }
        val rating = text(8.5f, R.color.text).apply {
            typeface = resources.getFont(R.font.inter_bold)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = round(Color.parseColor("#CC0D0B1F"), 6)
        }
        val badge = text(8.5f, R.color.button_text).apply {
            typeface = resources.getFont(R.font.inter_bold)
            setPadding(dp(5), dp(1), dp(5), dp(1))
        }
        val bar = View(this@ShopActivity).apply { background = round(getColor(R.color.ok), 2) }
        val title = text(9.5f, R.color.text).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            typeface = resources.getFont(R.font.inter_semibold)
        }
        val meta = text(8.5f, R.color.text_3).apply { maxLines = 1 }
        val frame = FrameLayout(this@ShopActivity).apply {
            addView(cover, FrameLayout.LayoutParams(-1, -1))
            addView(rating, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(4), dp(4), 0) })
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(4), 0, 0, dp(4)) })
            addView(bar, FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM))
        }
        val view = LinearLayout(this@ShopActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(dp(CARD_DP), dp(CARD_DP)))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(meta)
        }
        var bound: ShopGame? = null
    }

    // ---- Data ----

    private val console get() = CONSOLES[consoleIndex]

    private fun loadCatalog() {
        val c = console
        catalog = ShopApi.cached(this, c)
        error = null
        loading = true
        applyFilters(resetSelection = true)
        io.execute {
            val result = runCatching { ShopApi.fetch(this, c) }
            runOnUiThread {
                if (c != console) return@runOnUiThread
                loading = false
                result.onSuccess { catalog = it }.onFailure { error = it.message }
                if (result.exceptionOrNull() is NotPairedException) {
                    // Pass missing or revoked (AUTH_SECRET changed): pair again.
                    ShopPairing.forget(this)
                    ShopApi.token = null
                    catalog = null
                    startPairing()
                    return@runOnUiThread
                }
                applyFilters(resetSelection = catalog == null || result.isFailure)
            }
        }
    }

    private fun refreshOwned() {
        val files = console.romDir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }.orEmpty()
        owned = files.map { looseKey(displayTitle(it.nameWithoutExtension)) }.toSet()
    }

    private fun isOwned(g: ShopGame) = g.looseTitle in owned || ShopDownloads.jobFor(g)?.state == ShopDownloads.State.DONE

    private fun applyFilters(resetSelection: Boolean) {
        val all = catalog?.games.orEmpty()
        var list = if (genre != null) all.filter { genre in it.genres } else all
        val words = query?.split(Regex("""\s+"""))?.map(::looseKey)?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isNotEmpty()) list = list.filter { g ->
            val hay = g.looseTitle + " " + looseKey(g.name)
            words.all { it in hay }
        }
        list = when (sort) {
            Sort.RATING -> list // already sorted by the webapp
            Sort.NAME -> list.sortedBy { it.title.lowercase() }
            Sort.YEAR -> list.sortedByDescending { it.year ?: 0 }
        }
        shown = list
        if (resetSelection) { sel = 0; topRow = 0 }
        sel = sel.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
        refreshOwned()
        render()
    }

    // ---- Rendering ----

    private fun render() {
        val cat = catalog
        subtitle.text = when {
            cat != null -> "${console.name} · ${cat.title} · ${shown.size} juegos" + if (loading) " · actualizando…" else ""
            else -> console.name
        }
        tabViews.forEachIndexed { i, v ->
            val selected = i == consoleIndex
            v.setBackgroundResource(if (selected) (if (zone == Zone.TABS) R.drawable.bg_tab_sel_focus else R.drawable.bg_tab_sel) else R.drawable.bg_tab)
            val logo = (v as LinearLayout).getChildAt(0) as ImageView
            // Unselected logos in white, like the home screen (GBA has its own white version without the plate).
            val c = CONSOLES[i]
            when {
                selected -> { logo.setImageResource(c.logo); logo.clearColorFilter() }
                c.logoWhite != null -> { logo.setImageResource(c.logoWhite); logo.clearColorFilter() }
                else -> { logo.setImageResource(c.logo); logo.setColorFilter(Color.WHITE) }
            }
            v.alpha = if (selected) 1f else 0.75f
        }
        val labels = listOf(query?.let { "🔍  «$it»" } ?: "🔍  Buscar") + Sort.values().map { it.label } + "Género: ${genre ?: "Todos"}"
        filterViews.forEachIndexed { i, v ->
            v.text = labels[i]
            val on = when {
                i == 0 -> query != null
                i <= Sort.values().size -> Sort.values()[i - 1] == sort
                else -> genre != null
            }
            val focus = zone == Zone.FILTERS && i == filterIndex
            v.background = round(if (on) getColor(R.color.accent_start) else getColor(R.color.surface), 13).apply {
                setStroke(dp(if (focus) 2 else 1), if (focus) Color.WHITE else getColor(R.color.surface_border))
            }
            v.setTextColor(if (on || focus) Color.WHITE else getColor(R.color.chip_text))
        }
        message.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        message.text = when {
            shown.isNotEmpty() -> ""
            loading -> "Cargando el catálogo de ${console.name}…"
            error != null -> "No se pudo cargar el catálogo:\n$error"
            query != null -> "Ningún juego coincide con «$query»."
            genre != null -> "Ningún juego de ese género."
            else -> "No hay juegos para ${console.name}."
        }
        renderStatus()
        renderGrid()
    }

    private fun renderGrid() {
        val rowsTotal = (shown.size + COLUMNS - 1) / COLUMNS
        val selRow = sel / COLUMNS
        if (selRow < topRow) topRow = selRow
        if (selRow >= topRow + ROWS) topRow = selRow - ROWS + 1
        topRow = topRow.coerceIn(0, (rowsTotal - ROWS).coerceAtLeast(0))
        cards.forEachIndexed { i, card ->
            val index = topRow * COLUMNS + i
            val g = shown.getOrNull(index)
            card.view.visibility = if (g == null) View.INVISIBLE else View.VISIBLE
            if (g == null) return@forEachIndexed
            val focused = zone == Zone.GRID && index == sel
            card.frame.foreground = if (focused) getDrawable(R.drawable.fg_hero_border) else null
            card.view.scaleX = if (focused) 1.06f else 1f
            card.view.scaleY = card.view.scaleX
            card.view.elevation = if (focused) dp(6).toFloat() else 0f
            card.title.text = g.title
            card.meta.text = listOfNotNull(sizeOf(g), g.year?.toString(), g.genres.firstOrNull()).joinToString(" · ")
            card.rating.visibility = if (g.rating != null) View.VISIBLE else View.GONE
            card.rating.text = g.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') }
            val job = ShopDownloads.jobFor(g)
            when {
                isOwned(g) -> badge(card, "✓ En el stick", getColor(R.color.ok))
                job?.state == ShopDownloads.State.RUNNING -> badge(card, "${(job.progress * 100).toInt()} %", Color.WHITE)
                job?.state == ShopDownloads.State.QUEUED -> badge(card, "En cola", Color.WHITE)
                job?.state == ShopDownloads.State.ERROR -> badge(card, "Error", Color.parseColor("#F87171"))
                else -> card.badge.visibility = View.GONE
            }
            val running = job?.state == ShopDownloads.State.RUNNING
            card.bar.visibility = if (running) View.VISIBLE else View.GONE
            if (running) card.bar.layoutParams = (card.bar.layoutParams as FrameLayout.LayoutParams).apply { width = (dp(CARD_DP) * job!!.progress).toInt() }
            if (card.bound !== g) {
                card.bound = g
                card.cover.setImageDrawable(null)
                loadCover(g) { bmp -> if (card.bound === g) card.cover.setImageBitmap(bmp) }
            }
        }
        // Next row ready before you get there
        for (i in (topRow + ROWS) * COLUMNS until (topRow + ROWS + 1) * COLUMNS) {
            shown.getOrNull(i)?.let { loadCover(it) {} }
        }
    }

    /** The webapp's box art when it sent one; otherwise looked up by name like the home screen. */
    private fun loadCover(g: ShopGame, onLoaded: (android.graphics.Bitmap?) -> Unit) {
        val url = g.cover
        if (url != null) covers.loadUrl(url, dp(CARD_DP), onLoaded) else covers.load(coverProbe(g), dp(CARD_DP), onLoaded)
    }

    /** A Game named like the download, for CoverLoader (multi-disc box art is named without "(Disc 1)"). */
    private fun coverProbe(g: ShopGame): Game {
        val name = g.name.replace(Regex("""\s*\(Disc \d+\)""", RegexOption.IGNORE_CASE), "")
        return Game(console, File(console.romDir, "$name.${g.file.substringAfterLast('.')}"))
    }

    /** Download size (all discs of a multi-disc game). */
    private fun sizeOf(g: ShopGame) =
        Formatter.formatShortFileSize(this, if (g.discs.isNotEmpty()) g.discs.sumOf { it.second } else g.size)

    private fun badge(card: Card, label: String, color: Int) {
        card.badge.visibility = View.VISIBLE
        card.badge.text = label
        card.badge.background = round(color, 6)
    }

    private fun renderStatus() {
        val job = ShopDownloads.active
        val queued = ShopDownloads.jobs.count { it.state == ShopDownloads.State.QUEUED }
        status.text = when {
            job != null -> "⬇ ${job.game.title} · ${(job.progress * 100).toInt()} %" +
                if (queued > (if (job.state == ShopDownloads.State.QUEUED) 1 else 0)) " · ${queued} en cola" else ""
            ShopDownloads.jobs.lastOrNull()?.state == ShopDownloads.State.ERROR -> "No se pudo descargar ${ShopDownloads.jobs.last().game.title}"
            ShopDownloads.jobs.isNotEmpty() -> "Descargas terminadas · se suben a Drive solas"
            else -> ""
        }
    }

    // ---- Input ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = StickStatus.standardKey(event.device, event.keyCode)
        if (code !in KEYS) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            padStyle = StickStatus.padStyle(event.device)
            // Nintendo pads confirm with the right button (see MainActivity.semanticKey)
            val key = when {
                padStyle != PadStyle.NINTENDO -> code
                code == KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                else -> code
            }
            onKey(key)
        }
        return true
    }

    private fun onKey(code: Int) {
        if (pairPanel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) finish()
            return
        }
        when (code) {
            KeyEvent.KEYCODE_BUTTON_Y -> askSearch()
            KeyEvent.KEYCODE_BUTTON_L1 -> switchConsole(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> switchConsole(1)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (zone == Zone.GRID) finish() else { zone = Zone.GRID; render() }
            KeyEvent.KEYCODE_DPAD_LEFT -> when (zone) {
                Zone.TABS -> switchConsole(-1)
                Zone.FILTERS -> { filterIndex = (filterIndex - 1).coerceAtLeast(0); render() }
                Zone.GRID -> if (sel % COLUMNS > 0) { sel--; renderGrid() }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when (zone) {
                Zone.TABS -> switchConsole(1)
                Zone.FILTERS -> { filterIndex = (filterIndex + 1).coerceAtMost(filterViews.size - 1); render() }
                Zone.GRID -> if (sel % COLUMNS < COLUMNS - 1 && sel + 1 < shown.size) { sel++; renderGrid() }
            }
            KeyEvent.KEYCODE_DPAD_UP -> when (zone) {
                Zone.TABS -> Unit
                Zone.FILTERS -> { zone = Zone.TABS; render() }
                Zone.GRID -> if (sel >= COLUMNS) { sel -= COLUMNS; renderGrid() } else { zone = Zone.FILTERS; render() }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when (zone) {
                Zone.TABS -> { zone = Zone.FILTERS; render() }
                Zone.FILTERS -> { zone = Zone.GRID; render() }
                Zone.GRID -> if (sel + COLUMNS < shown.size) { sel += COLUMNS; renderGrid() }
                    else if (sel / COLUMNS < (shown.size - 1) / COLUMNS) { sel = shown.size - 1; renderGrid() }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> when (zone) {
                Zone.TABS -> { zone = Zone.GRID; render() }
                Zone.FILTERS -> chooseFilter()
                Zone.GRID -> shown.getOrNull(sel)?.let(::showGame)
            }
        }
    }

    private fun switchConsole(delta: Int) {
        consoleIndex = (consoleIndex + delta + CONSOLES.size) % CONSOLES.size
        genre = null
        loadCatalog()
    }

    private fun chooseFilter() {
        if (filterIndex == 0) return askSearch()
        if (filterIndex <= Sort.values().size) {
            sort = Sort.values()[filterIndex - 1]
            applyFilters(resetSelection = true)
            return
        }
        val counts = catalog?.games.orEmpty().flatMap { it.genres }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
        val options = listOf("Todos") + counts.map { "${it.key} (${it.value})" }
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Género")
            .setItems(options.toTypedArray()) { _, which ->
                genre = if (which == 0) null else counts[which - 1].key
                applyFilters(resetSelection = true)
            }
            .show()
    }

    /** The TV's on-screen keyboard: type part of a title ("zelda", "mario kart"). */
    private fun askSearch() {
        val input = android.widget.EditText(this).apply {
            setText(query.orEmpty())
            setSelection(text.length)
            hint = "Título del juego"
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setTextColor(getColor(R.color.text))
            setHintTextColor(getColor(R.color.text_3))
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        var dialog: AlertDialog? = null
        val apply = { text: String? ->
            query = text?.trim()?.takeIf { it.isNotEmpty() }
            zone = Zone.GRID
            applyFilters(resetSelection = true)
            dialog?.dismiss()
        }
        input.setOnEditorActionListener { _, _, _ -> apply(input.text.toString()); true }
        dialog = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Buscar en ${console.name}")
            .setView(box)
            .setPositiveButton("Buscar") { _, _ -> apply(input.text.toString()) }
            .apply { if (query != null) setNeutralButton("Quitar búsqueda") { _, _ -> apply(null) } }
            .setNegativeButton("Cancelar", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun showGame(g: ShopGame) {
        val cat = catalog ?: return
        val size = sizeOf(g)
        val lines = listOfNotNull(
            listOfNotNull(g.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') }, g.year?.toString(), g.genres.joinToString(", ").takeIf { it.isNotEmpty() })
                .joinToString(" · ").takeIf { it.isNotEmpty() },
            g.name,
            if (g.discs.size > 1) "${g.discs.size} discos · $size" else size,
        )
        val job = ShopDownloads.jobFor(g)
        val builder = AlertDialog.Builder(this, R.style.Dialog_RetroStick).setTitle(g.title)
        when {
            isOwned(g) -> builder.setMessage(lines.joinToString("\n") + "\n\nYa está en el stick.").setPositiveButton("Cerrar", null)
            job?.state == ShopDownloads.State.RUNNING || job?.state == ShopDownloads.State.QUEUED ->
                builder.setMessage(lines.joinToString("\n") + "\n\nSe está descargando.").setPositiveButton("Cerrar", null)
            else -> builder.setMessage(lines.joinToString("\n") + "\n\nSe guarda en el stick y se copia a tu Drive.")
                .setPositiveButton("Descargar") { _, _ -> ShopDownloads.enqueue(this, cat, g) }
                .setNegativeButton("Cancelar", null)
        }
        builder.show()
    }

    // ---- Helpers ----

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(getColor(color))
        typeface = Typeface.create(resources.getFont(R.font.inter_regular), Typeface.NORMAL)
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_CONSOLE = "console"
        private const val POLL_MS = 3000L
        private const val COLUMNS = 8
        private const val ROWS = 2
        private const val CARD_DP = 98
        private const val GAP_DP = 12
        private val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        )
    }
}
