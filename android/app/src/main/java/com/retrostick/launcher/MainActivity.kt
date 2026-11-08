package com.retrostick.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

private const val SETTINGS_COLUMNS = 4
/** Index of the "Sonidos" card in Retro Ajustes (the last one). */
private const val SOUNDS_SETTING = 11
private const val PERMISSION_REQUEST = 1
private const val CONNECT_INPUT = "com.google.android.intent.action.CONNECT_INPUT"
private const val FLAUNCHER_PACKAGE = "me.efesser.flauncher"
private const val SYNC_PERIOD_MS = 15 * 60_000L

/** Fallback card colours when there is no box art (inicio.html .cv gradients). */
private val COVER_GRADIENTS = listOf(
    0xFF0F9B6E to 0xFF022C22, 0xFF16A34A to 0xFF14532D, 0xFFF97316 to 0xFF7C2D12, 0xFFEAB308 to 0xFF713F12,
    0xFFEF4444 to 0xFF7F1D1D, 0xFF3B82F6 to 0xFF1E3A8A, 0xFF8B5CF6 to 0xFF2E1065,
)

private val HANDLED_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK,
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
)

class MainActivity : Activity() {

    private enum class Zone { TABS, CONTENT }

    private class Setting(val icon: String, val title: String, var subtitle: String, val action: () -> Unit)

    private lateinit var library: Library
    private lateinit var covers: CoverLoader
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var games: Map<String, List<Game>> = emptyMap()
    /** 0 until CONSOLES.size = consoles, then Retro Ajustes. */
    private var tab = 0
        set(value) {
            field = value
            if (value < CONSOLES.size) lastConsoleTab = value
        }
    /** Last console tab visited: RetroShop opens on that console. */
    private var lastConsoleTab = 0
    private val selected = IntArray(CONSOLES.size)
    private var settingIndex = 0
    private var zone = Zone.CONTENT
    private var initialTabChosen = false
    private var hasStorage = false
    private var heroKey: String? = null
    private lateinit var splash: View
    private var splashBarDone = false
    private var libraryLoaded = false

    private val tabCount = CONSOLES.size + 1
    private val settingsTab get() = tab == CONSOLES.size
    private val consoleGames get() = if (settingsTab) emptyList() else games[CONSOLES[tab].id].orEmpty()
    /** Per console: selection in the Drive row (below the stick's) and whether that row has the focus. */
    private val driveSel = IntArray(CONSOLES.size)
    private val inDrive = BooleanArray(CONSOLES.size)
    private val driveRow get() = !settingsTab && inDrive[tab] && driveItems.isNotEmpty()
    private val currentGame get() = if (driveRow) null else consoleGames.getOrNull(selected.getOrElse(tab) { 0 })
    /** Games of this tab's console in the Drive collection and not on the stick (row below the stick's). */
    private var driveItems: List<DriveCollection.Item> = emptyList()
    private val currentDrive get() = if (driveRow) driveItems.getOrNull(driveSel[tab]) else null
    private val driveBadges = mutableMapOf<String, TextView>()
    /** Saves and play time of the Drive games (read from /Partidas once per item). */
    private val driveInfo = mutableMapOf<String, Game>()
    private var driveDone = 0
    private val driveListener: () -> Unit = {
        val done = DriveCollection.jobsDone()
        if (done != driveDone) {
            // A game passed to the stick: it moves from the Drive section to the stick's
            driveDone = done
            reload()
        } else {
            val before = driveItems.map { it.key }
            val now = if (settingsTab) emptyList() else DriveCollection.items(this, CONSOLES[tab]).map { it.key }
            if (before != now) { buildDriveRow(); render() } else renderDriveBadges()
        }
    }

    private lateinit var tabs: LinearLayout
    private lateinit var gamePanel: View
    private lateinit var hero: ViewGroup
    private lateinit var heroImage: ImageView
    private lateinit var heroFallback: TextView
    private lateinit var emptyText: TextView
    private lateinit var shelf: View
    private lateinit var shelfTitle: TextView
    private lateinit var shelfScroll: HorizontalScrollView
    private lateinit var coverRow: LinearLayout
    private lateinit var driveTitle: TextView
    private lateinit var shelfClip: android.widget.ScrollView
    private lateinit var driveScroll: HorizontalScrollView
    private lateinit var driveCovers: LinearLayout
    private val driveViews = mutableListOf<View>()
    private lateinit var settingsGrid: GridLayout
    private lateinit var hints: LinearLayout
    private val tabViews = mutableListOf<View>()
    private val coverViews = mutableListOf<View>()
    private val settingViews = mutableListOf<View>()
    private lateinit var settings: List<Setting>

    /** Button glyphs follow the controller in use: set on connect and on every key press. */
    private var padStyle = PadStyle.REMOTE
    /** Debug builds: style forced with the debugPadStyle extra, until a real key is pressed. */
    private var forcedPadStyle: PadStyle? = null

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {
            InputDevice.getDevice(deviceId)?.takeIf(StickStatus::isGamepad)?.let { setPadStyle(StickStatus.padStyle(it)) }
            refreshStatus()
        }
        override fun onInputDeviceRemoved(deviceId: Int) {
            setPadStyle(StickStatus.firstPadStyle())
            refreshStatus()
        }
        override fun onInputDeviceChanged(deviceId: Int) = refreshStatus()
    }
    // Wi-Fi comes up a few seconds after boot: refresh the top bar as soon as the network changes.
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { refreshStatus() } }
        override fun onLost(network: Network) { main.post { refreshStatus() } }
    }
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            main.postDelayed(this, 30_000)
        }
    }
    private val driveAuth by lazy { DriveAuth(this) }
    private val syncListener: () -> Unit = {
        refreshStatus()
        // Games or saves may have changed on the stick
        if (DriveSync.phase != DriveSync.Phase.SYNCING) {
            reload()
            offerUpdate()
            // The phone may have added or sent games to the collection
            DriveCollection.refresh(this)
        }
    }
    /** RetroShop downloads: progress in the top bar; a finished game shows up in its tab right away. */
    private var shopDone = 0
    private val shopListener: () -> Unit = {
        refreshStatus()
        val done = ShopDownloads.jobs.count { it.state == ShopDownloads.State.DONE }
        if (done != shopDone) {
            shopDone = done
            reload()
        }
    }

    private val syncTicker = object : Runnable {
        override fun run() {
            if (hasStorage) DriveSync.sync(this@MainActivity)
            main.postDelayed(this, SYNC_PERIOD_MS)
        }
    }
    @Volatile private var loginCancelled = false

    private val statusJsonTicker = object : Runnable {
        override fun run() {
            writeStatusJson()
            main.postDelayed(this, 60 * 60_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        library = Library(this)
        covers = CoverLoader(this)

        tabs = findViewById(R.id.tabs)
        gamePanel = findViewById(R.id.gamePanel)
        hero = findViewById(R.id.hero)
        heroImage = findViewById(R.id.heroImage)
        heroFallback = findViewById(R.id.heroFallback)
        emptyText = findViewById(R.id.emptyText)
        shelf = findViewById(R.id.shelf)
        shelfTitle = findViewById(R.id.shelfTitle)
        shelfScroll = findViewById(R.id.shelfScroll)
        coverRow = findViewById(R.id.covers)
        driveTitle = findViewById(R.id.driveTitle)
        shelfClip = findViewById(R.id.shelfClip)
        // The root lets children draw outside their bounds (selection glow); this window must clip its rows
        shelfClip.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(0, 0, v.width, v.height) }
        driveScroll = findViewById(R.id.driveScroll)
        driveCovers = findViewById(R.id.driveCovers)
        settingsGrid = findViewById(R.id.settingsGrid)
        hints = findViewById(R.id.hints)
        hero.clipToOutline = true
        splash = findViewById(R.id.splash)

        buildTabs()
        buildSettings()
        // Only on a cold start (boot, reinstall), not when coming back from a game.
        Sounds.init(this)
        if (savedInstanceState == null) startSplash()
    }

    // ---- Startup animation ----

    private fun startSplash() {
        splash.visibility = View.VISIBLE
        Sounds.play(Sounds.Sound.BOOT)
        // Initial state equals the window background (glow + icon), so the hand-over is invisible.
        listOf(R.id.splashRetro, R.id.splashStick).forEach { findViewById<View>(it).alpha = 0f }
        findViewById<View>(R.id.splashFill).apply {
            pivotX = 0f
            scaleX = 0f
        }
        // A cold start takes ~2 s to draw its first frame on this stick: animate only once it is on screen.
        splash.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                splash.viewTreeObserver.removeOnPreDrawListener(this)
                splash.postOnAnimation { animateSplash() }
                return true
            }
        })
    }

    private fun animateSplash() {
        findViewById<View>(R.id.splashGlow)
            .animate().alpha(0.6f).scaleX(1.12f).scaleY(1.12f).setDuration(900).start()
        findViewById<View>(R.id.splashIcon).apply {
            animate().scaleX(1.12f).scaleY(1.12f).setDuration(220).setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(OvershootInterpolator(3f)).start()
                }.start()
        }
        listOf(R.id.splashRetro to -1, R.id.splashStick to 1).forEachIndexed { i, (id, side) ->
            findViewById<View>(id).apply {
                translationX = side * dp(28).toFloat()
                animate().alpha(1f).translationX(0f).setStartDelay(150L + i * 120).setDuration(420)
                    .setInterpolator(DecelerateInterpolator()).start()
            }
        }
        findViewById<View>(R.id.splashFill).apply {
            animate().scaleX(1f).setStartDelay(350).setDuration(1100)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    splashBarDone = true
                    maybeHideSplash()
                }.start()
        }
        // Never block the home screen if storage is slow or missing.
        main.postDelayed({
            libraryLoaded = true
            splashBarDone = true
            maybeHideSplash()
        }, 5000)
    }

    private fun maybeHideSplash() {
        if (splash.visibility != View.VISIBLE || !splashBarDone || !libraryLoaded) return
        splash.animate().alpha(0f).setDuration(350).withEndAction { splash.visibility = View.GONE }.start()
    }

    override fun onResume() {
        super.onResume()
        hasStorage = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!hasStorage) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                PERMISSION_REQUEST,
            )
        }
        padStyle = forcedPadStyle ?: StickStatus.firstPadStyle()
        reload()
        getSystemService(InputManager::class.java).registerInputDeviceListener(inputListener, main)
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        DriveSync.addListener(syncListener)
        ShopDownloads.addListener(shopListener)
        DriveCollection.addListener(driveListener)
        if (hasStorage) DriveCollection.refresh(this)
        main.post(statusTicker)
        main.post(statusJsonTicker)
        // Back from a game or from boot: sync if the last one is older than a minute, then every 15 min.
        if (hasStorage && System.currentTimeMillis() - DriveSync.lastSuccess(this) > 60_000) DriveSync.sync(this)
        // A new launcher in Drive: ask right away (not only after a whole sync).
        io.execute {
            val found = runCatching { Updater.checkNow(this) }.onFailure { android.util.Log.w("RetroStick", "Update check failed", it) }
                .getOrDefault(false)
            if (found) main.post { refreshStatus(); offerUpdate() }
        }
        main.postDelayed(syncTicker, SYNC_PERIOD_MS)
    }

    override fun onPause() {
        super.onPause()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(inputListener)
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        DriveSync.removeListener(syncListener)
        ShopDownloads.removeListener(shopListener)
        DriveCollection.removeListener(driveListener)
        main.removeCallbacks(statusTicker)
        main.removeCallbacks(statusJsonTicker)
        main.removeCallbacks(syncTicker)
        // Never sync while a game may have its save open.
        DriveSync.cancel()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Home pressed while already here
        zone = Zone.CONTENT
        // Debug builds only: preview the glyphs of a controller that is not connected
        // (adb shell am start -n com.retrostick.launcher/.MainActivity --es debugPadStyle XBOX).
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        intent?.getStringExtra("debugPadStyle")?.takeIf { debuggable }?.let { name ->
            forcedPadStyle = PadStyle.values().firstOrNull { it.name == name }
        }
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        hasStorage = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        reload()
    }

    // ---- Data ----

    private fun reload() {
        if (!hasStorage) {
            games = emptyMap()
            buildShelf()
            render()
            libraryLoaded = true
            maybeHideSplash()
            return
        }
        io.execute {
            val scanned = library.scan()
            main.post {
                // Keep the selection on the same game; one just passed from Drive gets selected on the stick's row
                val keepKeys = CONSOLES.indices.map { i -> games[CONSOLES[i].id]?.getOrNull(selected[i])?.key }
                val keepDrive = CONSOLES.indices.map { i -> DriveCollection.items(this, CONSOLES[i]).getOrNull(driveSel[i])?.baseName }
                games = scanned
                CONSOLES.forEachIndexed { i, c ->
                    val list = games[c.id].orEmpty()
                    val drive = DriveCollection.items(this, c)
                    val passed = if (inDrive[i]) list.indexOfFirst { it.baseName == keepDrive[i] } else -1
                    if (passed >= 0) inDrive[i] = false
                    selected[i] = passed.takeIf { it >= 0 } ?: list.indexOfFirst { it.key == keepKeys[i] }.takeIf { it >= 0 }
                        ?: selected[i].coerceIn(0, maxOf(0, list.size - 1))
                    driveSel[i] = drive.indexOfFirst { it.baseName == keepDrive[i] }.takeIf { it >= 0 }
                        ?: driveSel[i].coerceIn(0, maxOf(0, drive.size - 1))
                    if (drive.isEmpty()) inDrive[i] = false
                }
                if (!initialTabChosen) chooseInitialTab()
                updateTabCounts()
                buildShelf()
                render()
                libraryLoaded = true
                maybeHideSplash()
            }
        }
    }

    /** Start on the console of the most recently played game, else the first one with games. */
    private fun chooseInitialTab() {
        initialTabChosen = true
        val recent = games.values.flatten().filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
        tab = when {
            recent != null -> CONSOLES.indexOf(recent.console)
            else -> CONSOLES.indexOfFirst { games[it.id].orEmpty().isNotEmpty() }.coerceAtLeast(0)
        }
        selected[tab] = 0
    }

    private fun writeStatusJson() {
        if (!hasStorage) return
        io.execute { runCatching { StickStatus.writeStatusJson(this) } }
    }

    // ---- Tabs ----

    private fun buildTabs() {
        CONSOLES.forEach { console ->
            val v = layoutInflater.inflate(R.layout.item_tab, tabs, false)
            v.findViewById<ImageView>(R.id.tabLogo).setImageResource(console.logo)
            v.findViewById<TextView>(R.id.tabName).text = console.name
            tabs.addView(v)
            tabViews += v
        }
        val v = layoutInflater.inflate(R.layout.item_tab, tabs, false)
        v.findViewById<ImageView>(R.id.tabLogo).setImageResource(R.drawable.ic_settings)
        v.findViewById<TextView>(R.id.tabName).setText(R.string.settings_tab)
        v.findViewById<TextView>(R.id.tabCount).setText(R.string.settings_tab_sub)
        tabs.addView(v)
        tabViews += v
    }

    private fun updateTabCounts() {
        CONSOLES.forEachIndexed { i, c ->
            val n = games[c.id].orEmpty().size
            tabViews[i].findViewById<TextView>(R.id.tabCount).text = if (n == 1) "1 juego" else "$n juegos"
        }
    }

    private fun renderTabs() {
        tabViews.forEachIndexed { i, v ->
            val sel = i == tab
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                width = dp(if (sel) 140 else 108)
                height = dp(if (sel) 80 else 66)
            }
            v.setBackgroundResource(when {
                sel && zone == Zone.TABS -> R.drawable.bg_tab_sel_focus
                sel -> R.drawable.bg_tab_sel
                else -> R.drawable.bg_tab
            })
            v.alpha = if (sel) 1f else 0.7f
            v.elevation = if (sel) dp(8).toFloat() else 0f

            val logo = v.findViewById<ImageView>(R.id.tabLogo)
            logo.layoutParams = logo.layoutParams.apply { height = dp(if (sel) 22 else 17) }
            logo.maxWidth = dp(if (sel) 118 else 86)
            // Unselected logos are white (filter: brightness(0) invert(1)); the selected one keeps its colours.
            val console = CONSOLES.getOrNull(i)
            when {
                console == null || sel -> {
                    console?.let { logo.setImageResource(it.logo) }
                    if (console?.whiteWhenSelected == true) logo.setColorFilter(Color.WHITE) else logo.clearColorFilter()
                }
                console.logoWhite != null -> {
                    logo.setImageResource(console.logoWhite)
                    logo.clearColorFilter()
                }
                else -> {
                    logo.setImageResource(console.logo)
                    logo.setColorFilter(Color.WHITE)
                }
            }

            v.findViewById<TextView>(R.id.tabName).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (sel) 10.5f else 9f)
                setTextColor(if (sel) Color.WHITE else getColor(R.color.tab_name))
                typeface = resources.getFont(if (sel) R.font.inter_semibold else R.font.inter_regular)
            }
            v.findViewById<TextView>(R.id.tabCount)
                .setTextColor(getColor(if (sel) R.color.tab_count_sel else R.color.tab_count))
        }
    }

    // ---- Shelf ----

    private fun buildShelf() {
        coverRow.removeAllViews()
        coverViews.clear()
        if (settingsTab) return
        val list = consoleGames
        val playing = list.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
        list.forEach { game ->
            val v = layoutInflater.inflate(R.layout.item_cover, coverRow, false)
            val inner = v.findViewById<View>(R.id.coverInner)
            inner.background = fallbackGradient(game, 8)
            inner.clipToOutline = true
            v.findViewById<TextView>(R.id.coverTitle).text = game.title
            v.findViewById<View>(R.id.coverFavorite).visibility = if (game.favorite) View.VISIBLE else View.GONE
            v.findViewById<View>(R.id.coverBadge).visibility = if (game == playing) View.VISIBLE else View.GONE
            val image = v.findViewById<ImageView>(R.id.coverImage)
            image.tag = game.key
            covers.load(game, dp(98)) { bitmap -> if (image.tag == game.key) image.setImageBitmap(bitmap ?: logoCover(this, game.console, dp(98))) }
            coverRow.addView(v)
            coverViews += v
        }
        val add = layoutInflater.inflate(R.layout.item_cover, coverRow, false)
        add.findViewById<View>(R.id.coverInner).setBackgroundResource(R.drawable.bg_add_card)
        add.findViewById<TextView>(R.id.coverTitle).apply {
            setText(R.string.add_games)
            setTextColor(getColor(R.color.text_2))
            typeface = resources.getFont(R.font.inter_semibold)
        }
        coverRow.addView(add)
        // Selectable: last card of the shelf, opens RetroShop on this console
        coverViews += add
        buildDriveRow()
    }

    /** Second row: games in the Drive collection that aren't on the stick (✕ opens their ficha). */
    private fun buildDriveRow() {
        driveCovers.removeAllViews()
        driveViews.clear()
        driveItems = if (settingsTab) emptyList() else DriveCollection.items(this, CONSOLES[tab])
        driveInfo.clear()
        driveBadges.clear()
        val show = if (driveItems.isEmpty()) View.GONE else View.VISIBLE
        driveTitle.visibility = show
        driveScroll.visibility = show
        if (!settingsTab) driveTitle.text = getString(R.string.shelf_title_drive, CONSOLES[tab].name)
        driveItems.forEach { item ->
            val v = layoutInflater.inflate(R.layout.item_cover, driveCovers, false)
            val probe = item.probe()
            val inner = v.findViewById<View>(R.id.coverInner)
            inner.background = fallbackGradient(probe, 8)
            inner.clipToOutline = true
            v.findViewById<TextView>(R.id.coverTitle).text = item.title
            val image = v.findViewById<ImageView>(R.id.coverImage)
            image.tag = item.key
            image.alpha = 0.6f
            covers.load(probe, dp(98)) { bitmap -> if (image.tag == item.key) image.setImageBitmap(bitmap ?: logoCover(this, item.console, dp(98))) }
            driveBadges[item.key] = v.findViewById<TextView>(R.id.coverBadge).apply { visibility = View.VISIBLE }
            driveCovers.addView(v)
            driveViews += v
        }
        renderDriveBadges()
    }

    /** "Drive" on each Drive card, or how far it got while passing to the stick. */
    private fun renderDriveBadges() {
        driveItems.forEach { item ->
            val badge = driveBadges[item.key] ?: return@forEach
            val job = DriveCollection.jobFor(item)
            badge.text = when (job?.state) {
                DriveCollection.State.RUNNING -> "${(job.progress * 100).toInt()} %"
                DriveCollection.State.ERROR -> "Error"
                else -> getString(R.string.drive_badge)
            }
        }
    }

    private fun renderShelf() {
        if (settingsTab) return
        val drive = driveRow
        val sel = selected[tab]
        coverViews.forEachIndexed { i, v ->
            v.background = if (!drive && i == sel && zone == Zone.CONTENT) getDrawable(R.drawable.bg_cover_sel) else null
        }
        driveViews.forEachIndexed { i, v ->
            v.background = if (drive && i == driveSel[tab] && zone == Zone.CONTENT) getDrawable(R.drawable.bg_cover_sel) else null
        }
        // The Drive row slides up into the window when it has the focus
        shelfClip.post { shelfClip.smoothScrollTo(0, if (driveRow) driveTitle.top else 0) }
        if (drive) driveViews.getOrNull(driveSel[tab])?.let { scrollInto(driveScroll, it) }
        else coverViews.getOrNull(sel)?.let { scrollInto(shelfScroll, it) }
    }

    private fun scrollInto(shelfScroll: HorizontalScrollView, v: View) {
        shelfScroll.post {
            val visible = shelfScroll.width - shelfScroll.paddingStart - shelfScroll.paddingEnd
            when {
                v.left < shelfScroll.scrollX -> shelfScroll.smoothScrollTo(v.left, 0)
                v.right > shelfScroll.scrollX + visible -> shelfScroll.smoothScrollTo(v.right - visible, 0)
            }
        }
    }

    // ---- Selected game ----

    private fun renderContent() {
        val drive = currentDrive
        val game = currentGame ?: drive?.let { item -> driveInfo.getOrPut(item.key) { item.probe().also { library.describe(it) } } }
        gamePanel.visibility = if (!settingsTab && game != null) View.VISIBLE else View.GONE
        emptyText.visibility = if (!settingsTab && game == null) View.VISIBLE else View.GONE
        shelf.visibility = if (settingsTab) View.GONE else View.VISIBLE
        settingsGrid.visibility = if (settingsTab) View.VISIBLE else View.GONE
        if (settingsTab) return

        val console = CONSOLES[tab]
        shelfTitle.text = getString(R.string.shelf_title, console.name)
        if (game == null) {
            emptyText.text = when {
                !hasStorage -> getString(R.string.need_storage)
                consoleGames.isEmpty() -> getString(R.string.empty_console, console.name)
                else -> getString(R.string.shop_from_shelf, console.name)
            }
            return
        }
        findViewById<TextView>(R.id.gameTitle).text = game.title
        findViewById<TextView>(R.id.gameMeta).text =
            listOfNotNull(console.name, game.region, if (drive != null) getString(R.string.in_drive_meta) else null).joinToString(" · ")
        findViewById<TextView>(R.id.lastPlayed).text = formatWhen(game.lastPlayed)
        findViewById<TextView>(R.id.timePlayed).text = formatDuration(game.runtimeSeconds)
        findViewById<TextView>(R.id.saveState).apply {
            val backedUp = game.hasProgress && driveAuth.isConnected && DriveSync.isBackedUp(this@MainActivity, game)
            setText(when {
                !game.hasProgress -> R.string.no_save
                backedUp -> R.string.save_in_drive
                driveAuth.isConnected -> R.string.save_pending
                else -> R.string.save_on_stick
            })
            setTextColor(getColor(if (backedUp) R.color.ok else R.color.text))
        }
        findViewById<TextView>(R.id.playText).setText(when {
            drive != null -> R.string.see_ficha
            game.hasProgress -> R.string.continue_game
            else -> R.string.play
        })

        if (heroKey != game.key) {
            heroKey = game.key
            hero.background = fallbackGradient(game, 12)
            heroFallback.text = game.title
            heroImage.setImageDrawable(null)
            heroImage.tag = game.key
            covers.load(game, dp(190)) { bitmap -> if (heroImage.tag == game.key) heroImage.setImageBitmap(bitmap ?: logoCover(this, game.console, dp(190))) }
        }
    }

    // ---- Retro Ajustes ----

    private fun buildSettings() {
        settings = listOf(
            Setting("🎮", getString(R.string.set_pair), getString(R.string.set_pair_sub)) { open(Intent(CONNECT_INPUT)) },
            Setting("🔄", getString(R.string.set_sync), "") {
                if (driveAuth.isConnected) {
                    DriveSync.sync(this)
                    toast(R.string.syncing)
                } else {
                    startDriveLogin()
                }
            },
            Setting("📶", getString(R.string.set_wifi), "") { open(Intent(Settings.ACTION_WIFI_SETTINGS)) },
            Setting("⚙️", getString(R.string.set_android), getString(R.string.set_android_sub)) {
                open(Intent(Settings.ACTION_SETTINGS))
            },
            Setting("💾", getString(R.string.set_space), "") { open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) },
            Setting("🕹️", getString(R.string.set_controllers), "") { open(Intent(CONNECT_INPUT)) },
            Setting("👾", getString(R.string.set_retroarch), getString(R.string.set_retroarch_sub)) {
                launchIntent(RETROARCH_PACKAGE)?.let(::open) ?: toast(R.string.retroarch_missing)
            },
            Setting("📱", getString(R.string.set_other_apps), getString(R.string.set_other_apps_sub)) {
                launchIntent(FLAUNCHER_PACKAGE)?.let(::open) ?: toast(R.string.app_missing)
            },
            Setting("☁️", getString(R.string.set_account), "") {
                if (driveAuth.isConnected) confirmDisconnect() else startDriveLogin()
            },
            Setting("🛒", getString(R.string.set_shop), getString(R.string.set_shop_sub)) {
                // Opens on the console of the tab you came from (the first one from Retro Ajustes).
                val from = CONSOLES.getOrNull(lastConsoleTab)?.id
                open(Intent(this, ShopActivity::class.java).putExtra(ShopActivity.EXTRA_CONSOLE, from))
            },
            Setting("⬆️", getString(R.string.set_update), "") {
                val update = Updater.available
                when {
                    update != null -> installUpdate(update)
                    driveAuth.isConnected -> {
                        toast(R.string.update_checking)
                        io.execute {
                            val found = runCatching { Updater.checkNow(this, force = true) }.getOrDefault(false)
                            main.post {
                                refreshStatus()
                                if (found) Updater.available?.let(::installUpdate)
                                else Toast.makeText(this, getString(R.string.update_current, BuildConfig.VERSION_NAME), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    else -> startDriveLogin()
                }
            },
            Setting("🔊", getString(R.string.set_sounds), Sounds.level.label) {
                val level = Sounds.nextLevel()
                settings[SOUNDS_SETTING].subtitle = level.label
                // Heard at the new volume (nothing when switched off)
                keySound = Sounds.Sound.OPEN
            },
        )
        settings.forEach { s ->
            val v = layoutInflater.inflate(R.layout.item_setting, settingsGrid, false)
            v.findViewById<TextView>(R.id.settingIcon).text = s.icon
            v.findViewById<TextView>(R.id.settingTitle).text = s.title
            settingsGrid.addView(v)
            settingViews += v
        }
    }

    private fun renderSettings() {
        settingViews.forEachIndexed { i, v ->
            val sel = i == settingIndex && zone == Zone.CONTENT && settingsTab
            v.setBackgroundResource(if (sel) R.drawable.bg_tab_sel else R.drawable.bg_tab)
            v.findViewById<TextView>(R.id.settingSub).text = settings[i].subtitle
        }
    }

    // ---- Status (top bar and settings subtitles) ----

    private fun refreshStatus() {
        val pads = StickStatus.controllers()
        findViewById<TextView>(R.id.controllerText).text = when {
            pads.isEmpty() -> getString(R.string.no_controller)
            pads.size == 1 -> "${StickStatus.shortControllerName(pads[0])} · J1"
            else -> "${StickStatus.shortControllerName(pads[0])} · J1 +${pads.size - 1}"
        }
        val connected = driveAuth.isConfigured && driveAuth.isConnected
        val ago = formatAgo(DriveSync.lastSuccess(this))
        val download = ShopDownloads.active
        val (dot, syncText) = when {
            // A RetroShop download keeps going here: show its progress instead of Drive's state.
            download != null -> R.drawable.dot_ok to "Descargando ${download.game.title} · ${(download.progress * 100).toInt()} %" +
                ShopDownloads.jobs.count { it.state == ShopDownloads.State.QUEUED && it !== download }.let { if (it > 0) " · $it en cola" else "" }
            !connected -> R.drawable.dot_off to getString(R.string.drive_not_connected)
            DriveSync.phase == DriveSync.Phase.SYNCING -> R.drawable.dot_ok to getString(R.string.drive_syncing)
            DriveSync.phase == DriveSync.Phase.ERROR -> R.drawable.dot_error to getString(R.string.drive_error)
            else -> R.drawable.dot_ok to getString(R.string.drive_synced, ago)
        }
        findViewById<View>(R.id.syncDot).setBackgroundResource(dot)
        findViewById<TextView>(R.id.syncText).text = syncText
        val net = StickStatus.network(this)
        findViewById<TextView>(R.id.netText).text = net ?: getString(R.string.offline)
        findViewById<View>(R.id.netIcon).alpha = if (net != null) 1f else 0.4f

        settings[1].subtitle = when {
            !driveAuth.isConfigured -> getString(R.string.drive_missing_config)
            !connected -> getString(R.string.drive_connect)
            DriveSync.phase == DriveSync.Phase.SYNCING -> getString(R.string.drive_syncing)
            DriveSync.phase == DriveSync.Phase.ERROR -> getString(R.string.drive_error_retry)
            else -> getString(R.string.drive_last_sync, ago)
        }
        settings[8].subtitle = if (connected) driveAuth.email ?: getString(R.string.drive_connected) else getString(R.string.drive_not_connected)
        settings[SOUNDS_SETTING].subtitle = Sounds.level.label
        settings[10].subtitle = Updater.available?.let { getString(R.string.update_available_sub, it.versionName) }
            ?: getString(R.string.update_current, BuildConfig.VERSION_NAME)
        settings[2].subtitle = net?.let { "Conectado por $it" } ?: getString(R.string.offline)
        settings[5].subtitle = if (pads.isEmpty()) "Ninguno" else pads.joinToString(", ")
        if (hasStorage) {
            io.execute {
                val free = StickStatus.freeBytes()
                val roms = StickStatus.romsBytes()
                main.post {
                    settings[4].subtitle = "${Formatter.formatShortFileSize(this, free)} libres · " +
                        "juegos ${Formatter.formatShortFileSize(this, roms)}"
                    renderSettings()
                }
            }
        }
        renderSettings()
    }

    // ---- Hints ----

    private sealed class Glyph {
        class Icon(val res: Int) : Glyph()
        class Letter(val text: String, val color: Int) : Glyph()
        class Pill(val text: String) : Glyph()
    }

    /** confirm / back / top button (favourite) / left button (details) / shoulders, per controller family. */
    private class Glyphs(val confirm: Glyph, val back: Glyph?, val top: Glyph?, val left: Glyph?, val shoulders: Glyph)

    private fun glyphs(style: PadStyle): Glyphs {
        val plain = getColor(R.color.chip_text)
        return when (style) {
            PadStyle.PLAYSTATION -> Glyphs(
                Glyph.Icon(R.drawable.ic_hint_cross), Glyph.Icon(R.drawable.ic_hint_circle),
                Glyph.Icon(R.drawable.ic_hint_triangle), Glyph.Icon(R.drawable.ic_hint_square), Glyph.Pill("L1 / R1"),
            )
            PadStyle.XBOX -> Glyphs(
                Glyph.Letter("A", getColor(R.color.xbox_a)), Glyph.Letter("B", getColor(R.color.xbox_b)),
                Glyph.Letter("Y", getColor(R.color.xbox_y)), Glyph.Letter("X", getColor(R.color.xbox_x)), Glyph.Pill("LB / RB"),
            )
            // Nintendo: top button is X, left button is Y
            PadStyle.NINTENDO -> Glyphs(
                Glyph.Letter("A", plain), Glyph.Letter("B", plain),
                Glyph.Letter("X", plain), Glyph.Letter("Y", plain), Glyph.Pill("L / R"),
            )
            // The remote only has OK, the arrows and Back
            PadStyle.REMOTE -> Glyphs(Glyph.Pill("OK"), null, null, null, Glyph.Pill("↑"))
        }
    }

    private fun renderHints() {
        hints.removeAllViews()
        val g = glyphs(padStyle)
        when {
            settingsTab -> addHint(g.confirm, R.string.hint_open)
            currentDrive != null -> {
                addHint(g.confirm, R.string.hint_ficha)
                g.back?.let { addHint(it, R.string.hint_back) }
            }
            currentGame == null -> addHint(g.confirm, R.string.hint_open)
            currentGame != null -> {
                addHint(g.confirm, R.string.hint_play)
                g.back?.let { addHint(it, R.string.hint_back) }
                g.top?.let { addHint(it, R.string.hint_favorite) }
                g.left?.let { addHint(it, R.string.hint_details) }
            }
        }
        addHint(g.shoulders, if (padStyle == PadStyle.REMOTE) R.string.hint_consoles else R.string.hint_switch)
    }

    /** Glyph inside the "Continuar partida" button. */
    private fun renderPlayGlyph() {
        val icon = findViewById<View>(R.id.playIcon)
        val letter = findViewById<TextView>(R.id.playLetter)
        when (val confirm = glyphs(padStyle).confirm) {
            is Glyph.Icon -> {
                icon.visibility = View.VISIBLE
                letter.visibility = View.GONE
            }
            is Glyph.Letter, is Glyph.Pill -> {
                icon.visibility = View.GONE
                letter.visibility = View.VISIBLE
                letter.text = if (confirm is Glyph.Letter) confirm.text else (confirm as Glyph.Pill).text
                letter.setTextColor(if (confirm is Glyph.Letter && padStyle == PadStyle.XBOX) confirm.color else Color.WHITE)
                letter.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (confirm is Glyph.Pill) 7f else 10f)
            }
        }
    }

    private fun addHint(glyph: Glyph, label: Int) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        when (glyph) {
            is Glyph.Icon -> row.addView(
                ImageView(this).apply { setImageResource(glyph.res) }, LinearLayout.LayoutParams(dp(17), dp(17)),
            )
            is Glyph.Letter -> row.addView(TextView(this).apply {
                text = glyph.text
                gravity = android.view.Gravity.CENTER
                includeFontPadding = false
                setTextColor(glyph.color)
                setBackgroundResource(R.drawable.bg_hint_circle)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
                typeface = resources.getFont(R.font.inter_bold)
            }, LinearLayout.LayoutParams(dp(17), dp(17)))
            is Glyph.Pill -> row.addView(TextView(this).apply {
                text = glyph.text
                setTextColor(getColor(R.color.chip_text))
                setBackgroundResource(R.drawable.bg_hint_pill)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
                typeface = resources.getFont(R.font.inter_semibold)
                setPadding(dp(5), dp(1), dp(5), dp(1))
            }, LinearLayout.LayoutParams(-2, -2))
        }
        row.addView(TextView(this).apply {
            setText(label)
            setTextColor(getColor(R.color.chip_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4.5f) })
        hints.addView(row, LinearLayout.LayoutParams(-2, -2).apply { if (hints.childCount > 0) marginStart = dp(17) })
    }

    // ---- Input ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = StickStatus.standardKey(event.device, event.keyCode)
        if (code !in HANDLED_KEYS) return super.dispatchKeyEvent(event)
        val shoulder = code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
        if (event.action == KeyEvent.ACTION_DOWN && splash.visibility != View.VISIBLE && !(shoulder && event.repeatCount > 0)) {
            val style = StickStatus.padStyle(event.device)
            forcedPadStyle = null
            setPadStyle(style)
            onKey(semanticKey(code, style))
        }
        return true
    }

    /**
     * Android reports face buttons by position (BUTTON_A = bottom, BUTTON_B = right). Nintendo confirms with
     * the right button (labelled A) and goes back with the bottom one (B), so those two are swapped.
     * ⚠️ To verify with the Pro Controller paired: assumes Android 9 sends positional codes for it.
     */
    private fun semanticKey(code: Int, style: PadStyle) = when {
        style != PadStyle.NINTENDO -> code
        code == KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
        code == KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
        else -> code
    }

    private fun setPadStyle(style: PadStyle) {
        if (style == padStyle) return
        padStyle = style
        renderHints()
        renderPlayGlyph()
    }

    /** Sound picked by the action itself (open, launch…); otherwise one follows from what the key changed. */
    private var keySound: Sounds.Sound? = null

    private fun navState() =
        "$tab/$zone/$settingIndex/${selected.joinToString()}/${inDrive.joinToString()}/${driveSel.joinToString()}"

    private fun onKey(code: Int) {
        val before = navState()
        keySound = null
        handleKey(code)
        val changed = navState() != before
        val sound = keySound ?: when (code) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> Sounds.Sound.TAB.takeIf { changed }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> Sounds.Sound.BACK.takeIf { changed }
            else -> Sounds.Sound.MOVE.takeIf { changed }
        }
        sound?.let(Sounds::play)
    }

    private fun handleKey(code: Int) {
        val tabsZone = zone == Zone.TABS
        when (code) {
            KeyEvent.KEYCODE_BUTTON_L1 -> switchTab(-1)
            KeyEvent.KEYCODE_BUTTON_R1 -> switchTab(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                tabsZone -> switchTab(-1)
                settingsTab -> if (settingIndex % SETTINGS_COLUMNS > 0) settingIndex--
                else -> moveGame(-1)
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                tabsZone -> switchTab(1)
                settingsTab -> if (settingIndex % SETTINGS_COLUMNS < SETTINGS_COLUMNS - 1 && settingIndex + 1 < settings.size) settingIndex++
                else -> moveGame(1)
            }
            KeyEvent.KEYCODE_DPAD_UP -> when {
                tabsZone -> Unit
                settingsTab && settingIndex >= SETTINGS_COLUMNS -> settingIndex -= SETTINGS_COLUMNS
                driveRow -> inDrive[tab] = false
                else -> zone = Zone.TABS
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                tabsZone -> zone = Zone.CONTENT
                settingsTab && settingIndex + SETTINGS_COLUMNS < settings.size -> settingIndex += SETTINGS_COLUMNS
                !settingsTab && driveItems.isNotEmpty() -> inDrive[tab] = true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> when {
                tabsZone -> zone = Zone.CONTENT
                settingsTab -> { keySound = Sounds.Sound.OPEN; settings[settingIndex].action() }
                else -> play()
            }
            // The home screen never closes: Back only leaves the tab row.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> zone = Zone.CONTENT
            KeyEvent.KEYCODE_BUTTON_Y -> toggleFavorite()
            KeyEvent.KEYCODE_BUTTON_X -> showDetails()
        }
        render()
    }

    private fun switchTab(delta: Int) {
        tab = (tab + delta + tabCount) % tabCount
        buildShelf()
    }

    private fun moveGame(delta: Int) {
        if (driveRow) driveSel[tab] = (driveSel[tab] + delta).coerceIn(0, driveItems.size - 1)
        // Games, then the "Añade juegos desde RetroShop" card (index == number of games)
        else selected[tab] = (selected[tab] + delta).coerceIn(0, consoleGames.size)
    }

    // ---- Google Drive sign-in (code typed on the phone) ----

    private fun startDriveLogin() {
        if (!driveAuth.isConfigured) {
            toast(R.string.drive_missing_config)
            return
        }
        loginCancelled = false
        val info = TextView(this).apply {
            setText(R.string.drive_login_info)
            setTextColor(getColor(R.color.text_2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val url = TextView(this).apply {
            text = "google.com/device"
            setTextColor(getColor(R.color.brand_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = resources.getFont(R.font.inter_bold)
        }
        val code = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            letterSpacing = 0.12f
            typeface = resources.getFont(R.font.inter_extrabold)
        }
        val waiting = TextView(this).apply {
            setText(R.string.drive_login_waiting)
            setTextColor(getColor(R.color.text_3))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            listOf(info, url, code, waiting).forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }) }
        }
        val dialog = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(R.string.drive_login_title)
            .setView(box)
            .setNegativeButton(R.string.cancel) { _, _ -> loginCancelled = true }
            .setOnCancelListener { loginCancelled = true }
            .showForPad()

        Thread {
            try {
                val device = driveAuth.requestDeviceCode()
                main.post {
                    code.text = device.userCode
                    url.text = device.verificationUrl.removePrefix("https://").removePrefix("www.")
                }
                val outcome = driveAuth.waitForAuthorization(device) { loginCancelled }
                main.post {
                    dialog.dismiss()
                    when (outcome) {
                        DriveAuth.Outcome.CONNECTED -> {
                            toast(R.string.drive_login_ok)
                            DriveSync.sync(this)
                        }
                        DriveAuth.Outcome.DENIED -> toast(R.string.drive_login_denied)
                        DriveAuth.Outcome.EXPIRED -> toast(R.string.drive_login_expired)
                        DriveAuth.Outcome.CANCELLED -> Unit
                    }
                    refreshStatus()
                }
            } catch (e: Exception) {
                main.post {
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.drive_login_error, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ---- Launcher updates (Updater.kt) ----

    private var updateDialog: AlertDialog? = null

    /** After a sync: offers a newer launcher once per version (until "Ahora no" or a restart). */
    private fun offerUpdate() {
        val update = Updater.available ?: return
        if (update.versionCode == Updater.dismissedVersion || updateDialog?.isShowing == true || isFinishing) return
        updateDialog = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(R.string.update_title)
            .setMessage(getString(R.string.update_message, update.versionName, BuildConfig.VERSION_NAME))
            .setPositiveButton(R.string.update_install) { _, _ -> installUpdate(update) }
            .setNegativeButton(R.string.update_later) { _, _ -> Updater.dismissedVersion = update.versionCode }
            .setOnCancelListener { Updater.dismissedVersion = update.versionCode }
            .showForPad()
    }

    private fun installUpdate(update: Updater.Update) {
        // Android 8+: the user allows RetroStick to install apps once (Ajustes > Seguridad > Fuentes desconocidas).
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show()
            // Some TV builds lack the per-app screen: fall back to Android's security settings.
            val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            open(if (perApp.resolveActivity(packageManager) != null) perApp else Intent(Settings.ACTION_SECURITY_SETTINGS))
            return
        }
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { Updater.download(this, update) }
            main.post {
                result.onSuccess { open(Updater.installIntent(this)) }
                    .onFailure { Toast.makeText(this, getString(R.string.update_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun confirmDisconnect() {
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(R.string.drive_disconnect_title)
            .setMessage(getString(R.string.drive_disconnect_message, driveAuth.email ?: ""))
            .setPositiveButton(R.string.drive_disconnect) { _, _ ->
                driveAuth.disconnect()
                DriveSync.forget(this)
                refreshStatus()
                render()
            }
            .setNegativeButton(R.string.cancel, null)
            .showForPad()
    }

    private fun formatAgo(millis: Long): String {
        if (millis <= 0) return getString(R.string.never).lowercase()
        val minutes = (System.currentTimeMillis() - millis) / 60_000
        return when {
            minutes < 1 -> "ahora mismo"
            minutes < 60 -> "hace $minutes min"
            minutes < 24 * 60 -> "hace ${minutes / 60} h"
            else -> formatWhen(millis)
        }
    }

    private fun play() {
        currentDrive?.let { return openDriveGame(it) }
        val game = currentGame ?: return openShop()
        DriveSync.cancel()
        keySound = Sounds.Sound.LAUNCH
        if (!launchInRetroArch(this, game)) {
            keySound = Sounds.Sound.ERROR
            toast(R.string.retroarch_missing)
        }
    }

    /** The ficha of a game in the Drive collection, with "Pasar al stick" (ShopActivity in Drive mode). */
    private fun openDriveGame(item: DriveCollection.Item) {
        keySound = Sounds.Sound.OPEN
        open(Intent(this, ShopActivity::class.java)
            .putExtra(ShopActivity.EXTRA_CONSOLE, item.console.id)
            .putExtra(ShopActivity.EXTRA_DRIVE_FILE, item.name))
    }

    /** RetroShop on the console of the current tab (the "Añade juegos" card). */
    private fun openShop() {
        if (settingsTab) return
        keySound = Sounds.Sound.OPEN
        open(Intent(this, ShopActivity::class.java).putExtra(ShopActivity.EXTRA_CONSOLE, CONSOLES[tab].id))
    }

    private fun toggleFavorite() {
        val game = currentGame ?: return
        keySound = Sounds.Sound.OPEN
        library.setFavorite(game, !game.favorite)
        toast(if (game.favorite) R.string.favorite_added else R.string.favorite_removed)
        reload()
    }

    private fun showDetails() {
        val game = currentGame ?: return
        keySound = Sounds.Sound.OPEN
        val date = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES"))
        val text = buildString {
            appendLine("Archivo: ${game.file.name}")
            appendLine("Tamaño: ${Formatter.formatShortFileSize(this@MainActivity, game.file.length())}")
            appendLine("Consola: ${game.console.name}")
            appendLine("Veces jugado: ${game.playCount}")
            appendLine("Tiempo jugado: ${formatDuration(game.runtimeSeconds)}")
            appendLine()
            if (game.saveFiles.isEmpty()) appendLine("Sin partidas guardadas en el stick.")
            else {
                appendLine("Partidas en el stick:")
                game.saveFiles.sortedByDescending { it.lastModified() }.forEach {
                    appendLine("• ${it.name.removePrefix(game.baseName)} — ${date.format(it.lastModified())}")
                }
            }
        }
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(game.title)
            .setMessage(text.trim())
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar…") { _, _ -> confirmRemove(game) }
            .showForPad()
    }

    /** Off the stick only (kept in the Drive collection) or also from Drive (to its bin). Saves always stay. */
    private fun confirmRemove(game: Game) {
        val auth = DriveAuth(this)
        val withDrive = auth.isConfigured && auth.isConnected
        val options = if (withDrive) arrayOf(
            "Quitar solo del stick (se queda en tu colección de Drive)",
            "Borrar también de Drive (va a la papelera de Drive)",
        ) else arrayOf("Borrar del stick")
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Borrar ${game.title}")
            .setItems(options) { _, which -> removeGame(game, alsoDrive = withDrive && which == 1) }
            .setNegativeButton("Cancelar", null)
            .showForPad()
    }

    private fun removeGame(game: Game, alsoDrive: Boolean) {
        Toast.makeText(this, "Borrando ${game.title}…", Toast.LENGTH_SHORT).show()
        DriveSync.removeGame(this, game, alsoDrive) { error ->
            Sounds.play(if (error != null) Sounds.Sound.ERROR else Sounds.Sound.BACK)
            val msg = when {
                error != null -> "No se pudo borrar ${game.title}: $error"
                alsoDrive -> "${game.title} borrado del stick y de Drive. Las partidas guardadas se conservan."
                else -> "${game.title} quitado del stick. Sigue en tu colección de Drive."
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            reload()
            // "Quitar solo del stick": it shows up in the Drive section
            DriveCollection.refresh(this, force = true)
        }
    }

    private fun render() {
        renderTabs()
        renderContent()
        renderShelf()
        renderSettings()
        renderHints()
        renderPlayGlyph()
    }

    // ---- Helpers ----

    private fun launchIntent(pkg: String) =
        packageManager.getLaunchIntentForPackage(pkg) ?: packageManager.getLeanbackLaunchIntentForPackage(pkg)

    private fun open(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast(R.string.app_missing)
        }
    }

    private fun toast(text: Int) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun fallbackGradient(game: Game, radiusDp: Int): GradientDrawable {
        val (start, end) = COVER_GRADIENTS[Math.floorMod(game.title.hashCode(), COVER_GRADIENTS.size)]
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start.toInt(), end.toInt())).apply {
            cornerRadius = dp(radiusDp).toFloat()
        }
    }

    private fun formatWhen(millis: Long): String {
        if (millis <= 0) return getString(R.string.never)
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val time = SimpleDateFormat("HH:mm", Locale.US).format(millis)
        val sameDay = { a: Calendar, b: Calendar ->
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        }
        if (sameDay(then, today)) return "Hoy, $time"
        if (sameDay(then, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Ayer, $time"
        return SimpleDateFormat("d MMM", Locale("es", "ES")).format(millis).replace(".", "") + ", $time"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "< 1 min"
        }
    }

    private fun dp(value: Number) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
