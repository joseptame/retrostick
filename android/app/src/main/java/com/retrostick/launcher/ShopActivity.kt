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
    /** «Para dos»: only games two people can play on one console */
    private var twoPlayers = false
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
    // Header download pill
    private lateinit var statusPill: View
    private lateinit var statusBar: View
    // Ficha (game sheet): same content as the webapp's (IGDB through /api/igdb), own overlay driven by onKey
    private lateinit var fichaPanel: View
    private lateinit var fichaHero: ImageView
    private lateinit var fichaCover: ImageView
    private lateinit var fichaTitle: TextView
    private lateinit var fichaMeta: TextView
    private lateinit var fichaInfo: TextView
    private lateinit var fichaLangs: TextView
    private lateinit var fichaDesc: TextView
    /** Pulsing bars in place of the description until IGDB answers */
    private lateinit var fichaLoading: LoadingBars
    private lateinit var fichaStatus: TextView
    private lateinit var fichaTrack: View
    private lateinit var fichaBar: View
    private lateinit var fichaButtonsRow: LinearLayout
    private lateinit var fichaShotsTitle: TextView
    private lateinit var fichaShotsRow: LinearLayout
    private lateinit var viewer: FrameLayout
    private lateinit var viewerImage: ImageView
    private lateinit var viewerCount: TextView
    private val fichaButtons = mutableListOf<TextView>()
    private val fichaShotViews = mutableListOf<ImageView>()
    private var ficha: ShopGame? = null
    private var fichaIgdb: IgdbInfo? = null
    /** 0 = buttons row, 1 = screenshots row */
    private var fichaRow = 0
    private var fichaShot = 0
    private var viewerIndex = -1
    private var fichaButton = 0
    /** Drive mode (EXTRA_DRIVE_FILE): only the ficha of a game in the Drive collection, to pass it to the stick. */
    private var driveItem: DriveCollection.Item? = null
    /** Stick mode (EXTRA_STICK_FILE): the ficha of a game already on the stick, whose first button plays it. */
    private var stickGame: Game? = null
    /** Opened from the home list on a trailer / screenshot (EXTRA_MEDIA): show it, and close with it. */
    private var mediaDirect = -1
    private val driveListener: () -> Unit = { renderFicha() }

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
        Sounds.init(this)
        setContentView(build())
        ShopApi.token = ShopPairing.token(this)
        mediaDirect = intent.getIntExtra(EXTRA_MEDIA, -1)
        intent.getStringExtra(EXTRA_STICK_FILE)?.let { name ->
            val file = File(console.romDir, name).takeIf { it.isFile } ?: return finish()
            val game = Game(console, file)
            stickGame = game
            showGame(ShopGame(game.title, name, file.length(), game.baseName, null, emptyList(), null, emptyList(), null, null))
            return
        }
        intent.getStringExtra(EXTRA_DRIVE_FILE)?.let { name ->
            val item = DriveCollection.find(this, console, name) ?: return finish()
            driveItem = item
            showGame(ShopGame(item.title, item.name, item.size, item.baseName, null, emptyList(), null, emptyList(), null, null))
            return
        }
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
        DriveCollection.addListener(driveListener)
        downloadsListener()
        if (pairCode != null) main.postDelayed(pollPairing, POLL_MS)
    }

    override fun onPause() {
        super.onPause()
        // The trailer would keep playing behind another app
        closeTrailer()
        ShopDownloads.removeListener(downloadsListener)
        DriveCollection.removeListener(driveListener)
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
        // Download pill: what is downloading and how far it got (hidden when idle)
        status = text(10f, R.color.text).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        statusBar = View(this).apply { background = round(getColor(R.color.ok), 2) }
        val track = FrameLayout(this).apply {
            background = round(Color.parseColor("#26FFFFFF"), 2)
            addView(statusBar, FrameLayout.LayoutParams(0, -1))
        }
        statusPill = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(7))
            background = round(getColor(R.color.surface), 12).apply { setStroke(dp(1), getColor(R.color.surface_border)) }
            addView(status)
            addView(track, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5) })
            visibility = View.GONE
        }
        header.addView(statusPill, LinearLayout.LayoutParams(dp(300), -2))
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
                    maxWidth = dp(80)
                }, LinearLayout.LayoutParams(-2, dp(15)))
            }
            tabs.addView(v, LinearLayout.LayoutParams(dp(100), dp(36)).apply { marginEnd = dp(7) })
            tabViews += v
        }

        filters = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        col.addView(filters, LinearLayout.LayoutParams(-1, dp(30)).apply { topMargin = dp(12) })
        // 🔍 Buscar · the sorts · Género
        repeat(Sort.values().size + 3) {
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
        root.addView(buildKeyboard(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(buildFicha(), FrameLayout.LayoutParams(-1, -1))
        root.addView(pairPanel, FrameLayout.LayoutParams(-1, -1))

        col.addView(text(9.5f, R.color.text_3).apply {
            text = hints("[X]  Ver juego      [TRI]  Buscar      L1 / R1  Consola      [O]  Volver")
            gravity = Gravity.END
        })
        return root
    }

    /**
     * Ficha like the webapp's: a screenshot as backdrop, cover, title, rating, genres, size, description,
     * buttons (download, trailer, back), download progress and the screenshot gallery.
     */
    private fun buildFicha(): View {
        fichaHero = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.35f }
        val shade = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#F20D0B1F"), Color.parseColor("#D90D0B1F"), Color.parseColor("#990D0B1F")),
            )
        }
        fichaCover = CoverView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            setBackgroundResource(R.drawable.bg_cover)
            elevation = dp(10).toFloat()
        }
        fichaTitle = text(23f, R.color.text).apply { typeface = resources.getFont(R.font.inter_extrabold); maxLines = 2 }
        fichaMeta = text(12f, R.color.brand_accent).apply { typeface = resources.getFont(R.font.inter_semibold) }
        fichaInfo = text(10.5f, R.color.text_3)
        fichaLangs = text(10.5f, R.color.text_2)
        fichaDesc = text(11.5f, R.color.text_2).apply {
            setLineSpacing(0f, 1.3f)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
        }
        fichaStatus = text(10.5f, R.color.text_2)
        fichaBar = View(this).apply { background = round(getColor(R.color.ok), 3) }
        fichaTrack = FrameLayout(this).apply {
            background = round(Color.parseColor("#26FFFFFF"), 3)
            addView(fichaBar, FrameLayout.LayoutParams(0, -1))
        }
        fichaButtonsRow = LinearLayout(this)
        repeat(3) {
            val b = text(12.5f, R.color.text).apply {
                typeface = resources.getFont(R.font.inter_bold)
                gravity = Gravity.CENTER
                setPadding(dp(20), 0, dp(20), 0)
            }
            fichaButtonsRow.addView(b, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(10) })
            fichaButtons += b
        }
        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(fichaTitle)
            addView(fichaMeta, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(fichaInfo, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            addView(fichaLangs, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            addView(fichaDesc, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            fichaLoading = LoadingBars(this@ShopActivity, lines = 4)
            addView(fichaLoading, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(fichaButtonsRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(fichaStatus, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            addView(fichaTrack, LinearLayout.LayoutParams(dp(FICHA_BAR_DP), dp(5)).apply { topMargin = dp(6) })
        }
        val top = LinearLayout(this).apply {
            addView(fichaCover, LinearLayout.LayoutParams(dp(200), dp(200)).apply { marginEnd = dp(32) })
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        }
        fichaShotsTitle = text(9f, R.color.text_3).apply { letterSpacing = 0.15f }
        fichaShotsRow = LinearLayout(this).apply { clipChildren = false; clipToPadding = false }
        repeat(MAX_SHOTS) {
            val v = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                background = round(getColor(R.color.surface), 8)
            }
            fichaShotsRow.addView(v, LinearLayout.LayoutParams(dp(150), dp(84)).apply { marginEnd = dp(10) })
            fichaShotViews += v
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(56), dp(36), dp(48), dp(20))
            addView(top)
            addView(fichaShotsTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
            addView(fichaShotsRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        // Full-screen screenshot
        viewerImage = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        viewerCount = text(11f, R.color.text_2).apply {
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = round(Color.parseColor("#B30D0B1F"), 12)
        }
        viewer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
            addView(viewerImage, FrameLayout.LayoutParams(-1, -1))
            addView(viewerCount, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(20) })
        }
        fichaPanel = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            isClickable = true
            clipChildren = false
            visibility = View.GONE
            addView(fichaHero, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
            addView(viewer, FrameLayout.LayoutParams(-1, -1))
        }
        return fichaPanel
    }

    private inner class Card {
        val cover = CoverView(this@ShopActivity).apply {
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
        // Two lines: Spanish genres are longer ("Plataformas", "Estrategia por turnos")
        val meta = text(8.5f, R.color.text_3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
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
        catalog = null
        error = null
        loading = true
        applyFilters(resetSelection = true)
        io.execute {
            // The last copy first (parsing the PlayStation one takes a moment: never on the UI thread)
            val cached = ShopApi.cached(this, c)
            if (cached != null) runOnUiThread {
                if (c == console && loading) { catalog = cached; applyFilters(resetSelection = true) }
            }
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
        if (twoPlayers) list = list.filter { it.players >= 2 }
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
                selected -> {
                    logo.setImageResource(c.logo)
                    if (c.whiteWhenSelected) logo.setColorFilter(Color.WHITE) else logo.clearColorFilter()
                }
                c.logoWhite != null -> { logo.setImageResource(c.logoWhite); logo.clearColorFilter() }
                else -> { logo.setImageResource(c.logo); logo.setColorFilter(Color.WHITE) }
            }
            v.alpha = if (selected) 1f else 0.75f
        }
        val labels = listOf(query?.let { "«$it»" } ?: "Buscar") + Sort.values().map { it.label } + "Género: ${genre ?: "Todos"}" + "👥 Para dos"
        filterViews.forEachIndexed { i, v ->
            v.text = labels[i]
            val on = when {
                i == 0 -> query != null
                i <= Sort.values().size -> Sort.values()[i - 1] == sort
                i == Sort.values().size + 1 -> genre != null
                else -> twoPlayers
            }
            val focus = zone == Zone.FILTERS && i == filterIndex
            v.background = round(if (on) getColor(R.color.accent_start) else getColor(R.color.surface), 13).apply {
                setStroke(dp(if (focus) 2 else 1), if (focus) Color.WHITE else getColor(R.color.surface_border))
            }
            v.setTextColor(if (on || focus) Color.WHITE else getColor(R.color.chip_text))
            if (i == 0) v.icon(R.drawable.ic_shop_search, 13, v.currentTextColor)
        }
        message.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        message.text = when {
            shown.isNotEmpty() -> ""
            loading -> "Cargando el catálogo de ${console.name}…"
            error != null -> "No se pudo cargar el catálogo:\n$error"
            query != null -> "Ningún juego coincide con «$query»."
            twoPlayers && genre != null -> "Ningún juego para dos de ese género."
            twoPlayers -> "No hay juegos para dos en esta colección."
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
                isOwned(g) -> badge(card, "En el stick", getColor(R.color.ok), R.drawable.ic_shop_check)
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
                loadCover(g) { bmp -> if (card.bound === g) card.cover.setImageBitmap(bmp ?: logoCover(this, console, dp(CARD_DP))) }
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

    private fun badge(card: Card, label: String, color: Int, icon: Int? = null) {
        card.badge.visibility = View.VISIBLE
        card.badge.text = label
        card.badge.icon(icon, 10, getColor(R.color.button_text))
        card.badge.background = round(color, 6)
    }

    private fun renderStatus() {
        val job = ShopDownloads.active
        val queued = ShopDownloads.jobs.count { it.state == ShopDownloads.State.QUEUED } - if (job?.state == ShopDownloads.State.QUEUED) 1 else 0
        val last = ShopDownloads.jobs.lastOrNull()
        status.text = when {
            job != null -> "${job.game.title}  ${(job.progress * 100).toInt()} %" + if (queued > 0) "  ·  $queued en cola" else ""
            last?.state == ShopDownloads.State.ERROR -> "No se pudo descargar ${last.game.title}"
            last != null -> "${last.game.title} ya está en el stick"
            else -> ""
        }
        status.icon(
            when {
                job != null -> R.drawable.ic_shop_download
                last?.state == ShopDownloads.State.ERROR -> R.drawable.ic_shop_x
                else -> R.drawable.ic_shop_check
            },
            12, getColor(if (job == null && last?.state == ShopDownloads.State.ERROR) R.color.xbox_b else R.color.ok),
        )
        statusPill.visibility = if (last == null) View.GONE else View.VISIBLE
        statusBar.background = round(getColor(if (last?.state == ShopDownloads.State.ERROR) R.color.xbox_b else R.color.ok), 2)
        statusBar.layoutParams = (statusBar.layoutParams as FrameLayout.LayoutParams).apply {
            width = ((dp(300) - dp(24)) * (job?.progress ?: 1f)).toInt()
        }
        renderFicha()
    }

    // ---- Input ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = StickStatus.standardKey(event.device, event.keyCode)
        if (code !in KEYS) return super.dispatchKeyEvent(event)
        // A held shoulder button repeats (more so while a big catalog loads): one press = one console
        val shoulder = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
        if (event.action == KeyEvent.ACTION_DOWN && !(shoulder && event.repeatCount > 0)) {
            padStyle = StickStatus.padStyle(event.device)
            // Nintendo pads confirm with the right button (see MainActivity.semanticKey)
            val key = when {
                padStyle != PadStyle.NINTENDO -> code
                code == KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
                code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
                else -> code
            }
            val before = navState()
            onKey(key)
            val changed = navState() != before
            val sound = when {
                isFinishing -> Sounds.Sound.BACK
                !changed -> null
                key == KeyEvent.KEYCODE_BUTTON_L1 || key == KeyEvent.KEYCODE_BUTTON_R1 -> Sounds.Sound.TAB
                key == KeyEvent.KEYCODE_BACK || key == KeyEvent.KEYCODE_BUTTON_B -> Sounds.Sound.BACK
                key in CONFIRM_KEYS || key == KeyEvent.KEYCODE_BUTTON_Y -> Sounds.Sound.OPEN
                else -> Sounds.Sound.MOVE
            }
            sound?.let(Sounds::play)
        }
        return true
    }

    /** Everything a key can change on screen: a different value after the key = a sound. */
    private fun navState() = listOf(
        consoleIndex, zone, sel, filterIndex, ficha?.file, fichaRow, fichaButton, fichaShot, viewerIndex,
        kbOpen, kbRow, kbCol, query, trailer != null, driveItem?.let { DriveCollection.jobFor(it)?.state },
        ficha?.let { ShopDownloads.jobFor(it)?.state }, sort, genre,
    ).joinToString("|")

    private fun onKey(code: Int) {
        if (pairPanel.visibility == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) finish()
            return
        }
        if (kbOpen) return keyboardKey(code)
        if (ficha != null) return fichaKey(code)
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

    /** Stops at the first and last console (no wrapping round to the other end). */
    private fun switchConsole(delta: Int) {
        val next = (consoleIndex + delta).coerceIn(0, CONSOLES.size - 1)
        if (next == consoleIndex) return
        consoleIndex = next
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
        if (filterIndex == Sort.values().size + 2) {
            twoPlayers = !twoPlayers
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
            .showForPad()
    }

    // ---- Search keyboard: our own, driven by onKey (the system keyboard gets the DualSense's raw buttons) ----

    private val kbRows = listOf(
        "1234567890".map { it.toString() },
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() } + KB_DELETE,
        "zxcvbnm".map { it.toString() } + KB_SPACE + KB_DONE,
    )
    private lateinit var kbPanel: View
    private lateinit var kbText: TextView
    private val kbKeys = mutableListOf<List<TextView>>()
    private var kbRow = 1
    private var kbCol = 0
    private val kbOpen get() = kbPanel.visibility == View.VISIBLE

    private fun buildKeyboard(): View {
        kbText = text(18f, R.color.text).apply { typeface = resources.getFont(R.font.inter_bold) }
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        kbRows.forEach { labels ->
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER }
            val views = labels.map { label ->
                text(14f, R.color.text).apply {
                    text = label.uppercase()
                    gravity = Gravity.CENTER
                    typeface = resources.getFont(R.font.inter_semibold)
                }.also { row.addView(it, LinearLayout.LayoutParams(dp(if (label.length > 1) 92 else 42), dp(38)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            }
            kbKeys += views
            rows.addView(row)
        }
        kbPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(40), dp(14), dp(40), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#EE0D0B1F"), Color.parseColor("#FF0D0B1F")))
            isClickable = true
            visibility = View.GONE
            addView(kbText)
            addView(text(9.5f, R.color.text_3).apply {
                text = hints("[X]  Escribir      [SQ]  Borrar      L1 / R1  Consola      [O]  Cerrar")
                setPadding(0, dp(4), 0, dp(8))
            })
            addView(rows)
        }
        return kbPanel
    }

    /** Opens the keyboard over the grid; results filter as you type. */
    private fun askSearch() {
        kbPanel.visibility = View.VISIBLE
        renderKeyboard()
    }

    private fun closeKeyboard() {
        kbPanel.visibility = View.GONE
        zone = Zone.GRID
        render()
    }

    private fun renderKeyboard() {
        kbText.icon(R.drawable.ic_shop_search, 20, getColor(R.color.text))
        kbText.text = (query ?: "") + "▌" + if (query != null) "     ${shown.size} juegos" else ""
        kbKeys.forEachIndexed { r, row ->
            row.forEachIndexed { c, v ->
                val focus = r == kbRow && c == kbCol
                v.background = round(if (focus) Color.WHITE else getColor(R.color.surface), 9).apply {
                    setStroke(dp(1), if (focus) Color.WHITE else getColor(R.color.surface_border))
                }
                v.setTextColor(if (focus) getColor(R.color.button_text) else getColor(R.color.text))
                if (kbRows[r][c] == KB_DELETE) v.text = iconText(R.drawable.ic_shop_backspace, 18, v.currentTextColor)
            }
        }
    }

    private fun typeKey(label: String) {
        when (label) {
            KB_DONE -> return closeKeyboard()
            KB_DELETE -> query = query?.dropLast(1)
            KB_SPACE -> query = (query ?: "") + " "
            else -> query = (query ?: "") + label
        }
        if (query.isNullOrBlank()) query = null
        applyFilters(resetSelection = true)
        renderKeyboard()
    }

    private fun keyboardKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y -> closeKeyboard()
            KeyEvent.KEYCODE_BUTTON_X -> typeKey(KB_DELETE)
            // Same search on another console
            KeyEvent.KEYCODE_BUTTON_L1 -> { switchConsole(-1); renderKeyboard() }
            KeyEvent.KEYCODE_BUTTON_R1 -> { switchConsole(1); renderKeyboard() }
            KeyEvent.KEYCODE_DPAD_LEFT -> { kbCol = (kbCol - 1).coerceAtLeast(0); renderKeyboard() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { kbCol = (kbCol + 1).coerceAtMost(kbRows[kbRow].size - 1); renderKeyboard() }
            KeyEvent.KEYCODE_DPAD_UP -> { kbRow = (kbRow - 1).coerceAtLeast(0); kbCol = kbCol.coerceAtMost(kbRows[kbRow].size - 1); renderKeyboard() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { kbRow = (kbRow + 1).coerceAtMost(kbRows.size - 1); kbCol = kbCol.coerceAtMost(kbRows[kbRow].size - 1); renderKeyboard() }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> typeKey(kbRows[kbRow][kbCol])
        }
    }

    // ---- Ficha (game sheet) ----

    private fun showGame(g: ShopGame) {
        ficha = g
        fichaIgdb = null
        fichaRow = 0
        fichaButton = 0
        fichaShot = 0
        viewerIndex = -1
        viewer.visibility = View.GONE
        fichaHero.setImageDrawable(null)
        fichaCover.setImageDrawable(null)
        loadCover(g) { bmp -> if (ficha === g) fichaCover.setImageBitmap(bmp ?: logoCover(this, console, dp(200))) }
        fichaTitle.text = g.title
        fichaMeta.text = listOfNotNull(
            g.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') },
            g.year?.toString(),
            g.genres.joinToString(", ").takeIf { it.isNotEmpty() },
            g.players.takeIf { it >= 2 }?.let { "👥 Hasta $it jugadores" },
        ).joinToString("   ·   ")
        val size = sizeOf(g)
        fichaInfo.text = listOf(if (g.discs.size > 1) "${g.discs.size} discos · $size" else size, console.name, g.name).joinToString("   ·   ")
        // Languages of this release (from its file name), Spanish highlighted
        val langs = Languages.of(g.file).ifEmpty { Languages.of(g.name) }
        fichaLangs.visibility = if (langs.isEmpty()) View.GONE else View.VISIBLE
        fichaLangs.text = android.text.SpannableStringBuilder("Idiomas   ").apply {
            langs.forEachIndexed { i, l ->
                if (i > 0) append("  ·  ")
                val start = length
                append(l)
                if (l == "Español") {
                    setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.ok)), start, length, 0)
                    setSpan(android.text.style.StyleSpan(Typeface.BOLD), start, length, 0)
                }
            }
        }
        fichaDesc.text = ""
        fichaDesc.visibility = View.GONE
        fichaLoading.show(true)
        fichaPanel.visibility = View.VISIBLE
        renderFicha()
        // Description, trailer and screenshots, like the webapp
        io.execute {
            // The ficha kept on the stick when there is one (games on the stick: Fichas), else from the webapp
            val info = Fichas.cached(this, g.title, console.id)?.takeIf { !it.stale }?.info
                ?: runCatching { ShopApi.igdb(g.title, console.id) }.getOrNull()
            runOnUiThread {
                if (ficha !== g) return@runOnUiThread
                fichaIgdb = info
                fichaLoading.show(false)
                fichaDesc.visibility = View.VISIBLE
                fichaDesc.text = info?.description ?: "Sin descripción en IGDB."
                if (g.rating == null && info?.rating != null) {
                    fichaMeta.text = "★ " + String.format("%.1f", info.rating).replace('.', ',') + "   ·   " + fichaMeta.text
                }
                info?.screenshots?.firstOrNull()?.let { url -> covers.loadUrl(url, dp(480)) { if (ficha === g) fichaHero.setImageBitmap(it) } }
                renderFicha()
                if (mediaDirect >= 0) {
                    fichaRow = 1
                    fichaShot = mediaDirect.coerceAtMost((fichaMedia.size - 1).coerceAtLeast(0))
                    if (info == null || fichaMedia.isEmpty()) finish()
                    else if (hasTrailer && fichaShot == 0) playTrailer() else openViewer(fichaShot - if (hasTrailer) 1 else 0)
                }
            }
        }
    }

    private fun closeFicha() {
        closeTrailer()
        fichaLoading.show(false)
        if (driveItem != null || stickGame != null) return finish()
        ficha = null
        fichaPanel.visibility = View.GONE
        renderGrid()
    }

    private val hasTrailer get() = fichaIgdb?.trailerId != null
    /** Screenshots shown (the trailer, when there is one, takes the first slot of the row). */
    private val fichaShots get() = fichaIgdb?.screenshots.orEmpty().take(if (hasTrailer) MAX_SHOTS - 1 else MAX_SHOTS)
    /** Items of the bottom row: trailer thumbnail first, then the screenshots. */
    private val fichaMedia get() = listOfNotNull(fichaIgdb?.trailerId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }) + fichaShots

    /** Buttons and download progress of the open sheet; also called on every download update. */
    private fun renderFicha() {
        val g = ficha ?: return
        val job = ShopDownloads.jobFor(g)
        val state = job?.state
        val item = driveItem
        val passing = item?.let { DriveCollection.jobFor(it) }
        val (first, status) = when {
            stickGame != null -> PLAY to "Está en el stick."
            item != null -> when (passing?.state) {
                DriveCollection.State.RUNNING -> "Pasando al stick ${(passing.progress * 100).toInt()} %" to
                    "${Formatter.formatShortFileSize(this, passing.done)} de ${Formatter.formatShortFileSize(this, passing.total)} · puedes volver al inicio y seguirá"
                DriveCollection.State.DONE -> OWNED to "Ya está en el stick: aparece en la pestaña ${console.name} del inicio."
                DriveCollection.State.ERROR -> RETRY to "No se pudo pasar al stick: ${passing.error}"
                null -> PASS to "Está en tu colección de Drive. Se descarga al stick y queda sincronizado."
            }
            isOwned(g) -> OWNED to "Ya está en el stick: aparece en la pestaña ${console.name} del inicio."
            state == ShopDownloads.State.RUNNING -> "Descargando ${(job.progress * 100).toInt()} %" to
                "${Formatter.formatShortFileSize(this, job.done)} de ${Formatter.formatShortFileSize(this, job.total)} · puedes seguir mirando la tienda"
            state == ShopDownloads.State.QUEUED -> "En cola…" to "Empezará cuando termine la descarga anterior."
            state == ShopDownloads.State.ERROR -> RETRY to "No se pudo descargar: ${job.error}"
            else -> {
                val cat = catalog
                val have = if (cat != null) ShopDownloads.partialBytes(cat, g) else 0L
                val total = ShopDownloads.totalBytes(g)
                if (have > 0) DOWNLOAD to "Descarga a medias: seguirá donde se quedó (${Formatter.formatShortFileSize(this, have)} de ${Formatter.formatShortFileSize(this, total)})."
                else DOWNLOAD to "Se guarda en el stick y se copia a tu Drive."
            }
        }
        fichaStatus.text = status
        val running = if (item != null) passing?.state == DriveCollection.State.RUNNING
            else state == ShopDownloads.State.RUNNING || state == ShopDownloads.State.QUEUED
        fichaTrack.visibility = if (running) View.VISIBLE else View.GONE
        fichaBar.layoutParams = (fichaBar.layoutParams as FrameLayout.LayoutParams).apply {
            width = (dp(FICHA_BAR_DP) * ((if (item != null) passing?.progress else job?.progress) ?: 0f)).toInt()
        }
        val labels = fichaLabels(first)
        fichaButton = fichaButton.coerceAtMost(labels.size - 1)
        fichaButtons.forEachIndexed { i, b ->
            b.visibility = if (i < labels.size) View.VISIBLE else View.GONE
            if (i >= labels.size) return@forEachIndexed
            b.text = labels[i]
            val focus = fichaRow == 0 && i == fichaButton
            b.background = round(if (focus) Color.WHITE else getColor(R.color.surface), 20).apply {
                setStroke(dp(1), if (focus) Color.WHITE else getColor(R.color.surface_border))
            }
            b.setTextColor(if (focus) getColor(R.color.button_text) else getColor(R.color.text))
            val icon = when (labels[i]) {
                DOWNLOAD, PASS -> R.drawable.ic_shop_download
                PLAY -> R.drawable.ic_shop_play
                OWNED -> R.drawable.ic_shop_check
                RETRY -> R.drawable.ic_shop_retry
                else -> null
            }
            b.icon(icon, 15, b.currentTextColor)
        }
        val media = fichaMedia
        fichaShotsTitle.visibility = if (media.isEmpty()) View.GONE else View.VISIBLE
        fichaShotsTitle.text = (if (hasTrailer) "TRÁILER Y CAPTURAS" else "CAPTURAS") + "  ${fichaShots.size}"
        fichaShotViews.forEachIndexed { i, v ->
            val url = media.getOrNull(i)
            v.visibility = if (url == null) View.GONE else View.VISIBLE
            if (url == null) return@forEachIndexed
            if (v.tag != url) {
                v.tag = url
                v.setImageDrawable(null)
                covers.loadUrl(url, dp(150)) { if (v.tag == url) v.setImageBitmap(it) }
            }
            val focus = fichaRow == 1 && i == fichaShot
            val play = if (i == 0 && hasTrailer) getDrawable(R.drawable.ic_shop_play_badge) else null
            val border = if (focus) getDrawable(R.drawable.fg_hero_border) else null
            v.foreground = listOfNotNull(play, border).let {
                if (it.size > 1) android.graphics.drawable.LayerDrawable(it.toTypedArray()) else it.firstOrNull()
            }
            v.scaleX = if (focus) 1.08f else 1f
            v.scaleY = v.scaleX
        }
    }

    /** [Descargar…, Volver] (the trailer plays from the bottom row) */
    private fun fichaLabels(first: String) = listOf(first, BACK)

    private fun fichaKey(code: Int) {
        if (trailer != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B) { closeTrailer(); if (mediaDirect >= 0) finish() }
            return
        }
        if (viewerIndex >= 0) return viewerKey(code)
        val g = ficha ?: return
        val labels = fichaLabels("")
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> return closeFicha()
            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (fichaRow == 0) fichaButton = (fichaButton - 1).coerceAtLeast(0) else fichaShot = (fichaShot - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT ->
                if (fichaRow == 0) fichaButton = (fichaButton + 1).coerceAtMost(labels.size - 1)
                else fichaShot = (fichaShot + 1).coerceAtMost(fichaMedia.size - 1)
            KeyEvent.KEYCODE_DPAD_DOWN -> if (fichaMedia.isNotEmpty()) fichaRow = 1
            KeyEvent.KEYCODE_DPAD_UP -> fichaRow = 0
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (fichaRow == 1) {
                    return if (hasTrailer && fichaShot == 0) playTrailer() else openViewer(fichaShot - if (hasTrailer) 1 else 0)
                }
                when (labels.getOrNull(fichaButton)) {
                    BACK -> return closeFicha()
                    else -> download(g)
                }
            }
        }
        renderFicha()
    }

    private fun download(g: ShopGame) {
        stickGame?.let { game ->
            if (launchInRetroArch(this, game)) { Sounds.play(Sounds.Sound.LAUNCH); finish() }
            return
        }
        driveItem?.let { item ->
            val s = DriveCollection.jobFor(item)?.state
            if (s != DriveCollection.State.RUNNING && s != DriveCollection.State.DONE) DriveCollection.passToStick(this, item)
            return renderFicha()
        }
        val cat = catalog ?: return
        val state = ShopDownloads.jobFor(g)?.state
        if (isOwned(g) || state == ShopDownloads.State.RUNNING || state == ShopDownloads.State.QUEUED) return
        // Room for it? What is already downloaded (discs, a .part) doesn't count again.
        val need = ShopDownloads.totalBytes(g) - ShopDownloads.partialBytes(cat, g)
        val free = StickStatus.freeBytes()
        if (need + SPACE_MARGIN > free) return askForSpace(g, need, free)
        ShopDownloads.enqueue(this, cat, g)
    }

    /**
     * Not enough space: the stick's games, biggest first, to take one off (it stays in the Drive collection and
     * can come back from the home screen). Once it is gone the download starts, or this asks again if still short.
     */
    private fun askForSpace(g: ShopGame, need: Long, free: Long) {
        Sounds.play(Sounds.Sound.ERROR)
        io.execute {
            val games = Library(this).scan().values.flatten()
                .map { it to DriveSync.romFiles(it).sumOf { f -> f.length() } }
                .sortedByDescending { it.second }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val size = { n: Long -> Formatter.formatShortFileSize(this, n) }
                val auth = DriveAuth(this)
                val keep = auth.isConfigured && auth.isConnected
                val header = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(20), dp(24), dp(8))
                    addView(text(16f, R.color.text).apply {
                        text = "No cabe ${g.title}"
                        typeface = resources.getFont(R.font.inter_bold)
                    })
                    addView(text(11.5f, R.color.text_2).apply {
                        text = "Necesita ${size(need)} y quedan ${size(free)} libres. " + if (keep)
                            "Elige un juego para quitarlo del stick: sigue en tu colección de Drive y puedes volver a pasarlo cuando quieras."
                        else "Elige un juego para borrarlo del stick."
                        setPadding(0, dp(6), 0, 0)
                        setLineSpacing(0f, 1.25f)
                    })
                }
                if (games.isEmpty()) {
                    android.widget.Toast.makeText(this, "No cabe ${g.title}: necesita ${size(need)} y quedan ${size(free)} libres.", android.widget.Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this, R.style.Dialog_RetroStick)
                    .setCustomTitle(header)
                    .setItems(games.map { (game, bytes) -> "${game.title}   ·   ${game.console.name}   ·   ${size(bytes)}" }.toTypedArray()) { _, which ->
                        val game = games[which].first
                        android.widget.Toast.makeText(this, "Quitando ${game.title} del stick…", android.widget.Toast.LENGTH_SHORT).show()
                        DriveSync.removeGame(this, game, alsoDrive = false) { error ->
                            if (error != null) {
                                Sounds.play(Sounds.Sound.ERROR)
                                android.widget.Toast.makeText(this, "No se pudo quitar ${game.title}: $error", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                DriveCollection.refresh(this, force = true)
                                refreshOwned()
                                download(g)
                                renderFicha()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .showForPad()
            }
        }
    }

    /** YouTube app on the TV; the browser if it is missing. */
    private var trailer: android.webkit.WebView? = null

    /**
     * Trailer inside the ficha, full screen: YouTube's embedded player in a WebView (as the webapp does).
     * The page is given the webapp's address as origin, which the embed needs. ○ closes it.
     */
    private fun playTrailer() {
        val id = fichaIgdb?.trailerId ?: return
        val web = runCatching {
            android.webkit.WebView(this).apply {
                setBackgroundColor(Color.BLACK)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webChromeClient = android.webkit.WebChromeClient()
                isFocusable = false
            }
        }.getOrNull() ?: return openTrailerApp(id)
        val html = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <style>html,body{margin:0;height:100%;background:#000;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
            <body><iframe src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&rel=0&modestbranding=1&playsinline=1&iv_load_policy=3"
            allow="autoplay; encrypted-media; fullscreen"></iframe></body></html>"""
        web.loadDataWithBaseURL(BuildConfig.WEBAPP_URL, html, "text/html", "utf-8", null)
        (fichaPanel as FrameLayout).addView(web, FrameLayout.LayoutParams(-1, -1))
        trailer = web
    }

    private fun closeTrailer() {
        trailer?.let {
            (fichaPanel as FrameLayout).removeView(it)
            it.loadUrl("about:blank")
            it.destroy()
        }
        trailer = null
    }

    /** No WebView on this device: the YouTube app (or the browser). */
    private fun openTrailerApp(id: String) {
        val app = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$id"))
        val web = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=$id"))
        runCatching { startActivity(app) }.recoverCatching { startActivity(web) }
            .onFailure { android.widget.Toast.makeText(this, "No hay ninguna app para ver el tráiler", android.widget.Toast.LENGTH_SHORT).show() }
    }

    private fun openViewer(index: Int) {
        val shots = fichaShots
        if (shots.isEmpty()) return
        viewerIndex = index.coerceIn(0, shots.size - 1)
        viewer.visibility = View.VISIBLE
        val url = shots[viewerIndex]
        viewerImage.setImageDrawable(null)
        viewerImage.tag = url
        covers.loadUrl(url, dp(900)) { if (viewerImage.tag == url) viewerImage.setImageBitmap(it) }
        viewerCount.text = hints("${viewerIndex + 1} / ${shots.size}      ◀ ▶  Cambiar      [O]  Cerrar")
    }

    private fun viewerKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                viewerIndex = -1
                viewer.visibility = View.GONE
                if (mediaDirect >= 0) finish()
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> openViewer((viewerIndex - 1 + fichaShots.size) % fichaShots.size)
            KeyEvent.KEYCODE_DPAD_RIGHT -> openViewer((viewerIndex + 1) % fichaShots.size)
        }
    }

    // ---- Helpers ----

    /** Line icon before the text, tinted like it (null removes it). */
    private fun TextView.icon(res: Int?, sizeDp: Int, color: Int) {
        val d = res?.let { getDrawable(it)!!.mutate().apply { setTint(color); setBounds(0, 0, dp(sizeDp), dp(sizeDp)) } }
        setCompoundDrawablesRelative(d, null, null, null)
        compoundDrawablePadding = dp(6)
    }

    /** A line icon as text (keyboard keys). */
    private fun iconText(res: Int, sizeDp: Int, color: Int): CharSequence {
        val d = getDrawable(res)!!.mutate().apply { setTint(color); setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }
        return android.text.SpannableString(" ").apply { setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, 0) }
    }

    /** Controller hints with the home screen's glyphs: [X] [O] [SQ] [TRI]. */
    private fun hints(text: String): CharSequence {
        val glyphs = mapOf(
            "[X]" to R.drawable.ic_hint_cross, "[O]" to R.drawable.ic_hint_circle,
            "[SQ]" to R.drawable.ic_hint_square, "[TRI]" to R.drawable.ic_hint_triangle,
        )
        val out = android.text.SpannableStringBuilder(text)
        for ((token, res) in glyphs) {
            var at = out.indexOf(token)
            while (at >= 0) {
                val d = getDrawable(res)!!.mutate().apply { setBounds(0, 0, dp(14), dp(14)) }
                out.setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), at, at + token.length, 0)
                at = out.indexOf(token, at + token.length)
            }
        }
        return out
    }

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
        /** Kept free besides the download (RetroArch's saves, the system). */
        private const val SPACE_MARGIN = 150L * 1024 * 1024
        private val CONFIRM_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
        const val EXTRA_DRIVE_FILE = "driveFile"
        const val EXTRA_STICK_FILE = "stickFile"
        const val EXTRA_MEDIA = "media"
        private const val PLAY = "Jugar"
        private const val PASS = "Pasar al stick"
        private const val POLL_MS = 3000L
        private const val FICHA_BAR_DP = 320
        private const val MAX_SHOTS = 6
        private const val DOWNLOAD = "Descargar"
        private const val OWNED = "En el stick"
        private const val RETRY = "Reintentar"
        private const val BACK = "Volver"
        private const val KB_DELETE = "⌫"
        private const val KB_SPACE = "espacio"
        private const val KB_DONE = "buscar"
        private const val COLUMNS = 8
        private const val ROWS = 2
        private const val CARD_DP = 98
        private const val GAP_DP = 12
        private val KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        )
    }
}
