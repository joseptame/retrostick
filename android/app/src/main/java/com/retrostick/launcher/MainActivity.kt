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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val SETTINGS_COLUMNS = 5
/** Index of the "Sonidos" card in Retro Ajustes (the last one). */
private const val SOUNDS_SETTING = 11
/** Index of the "Inicio" card (after Sonidos): view, «Seguir jugando» and which consoles show as tabs. */
private const val VIEW_SETTING = 12
/** Index of the "Pantallas de inicio" card (the consoles' boot screens, from the user's BIOS). */
private const val BOOT_SETTING = 13
/** Index of the "Mis logros" card (RetroAchievements account and profile). */
private const val RA_SETTING = 14
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
    // Options = list / covers view, Create = manual, L2 = search
    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_L2,
    // R2 = the game's achievements (RetroAchievements)
    KeyEvent.KEYCODE_BUTTON_R2,
)

class MainActivity : Activity() {

    /** GEAR: the «Retro Ajustes» button in the top bar (▲ from the tabs). */
    private enum class Zone { GEAR, TABS, CONTENT }

    private class Setting(val icon: String, val title: String, var subtitle: String, val action: () -> Unit)

    private lateinit var library: Library
    /** List view of the home screen (Options or Retro Ajustes switch it with the covers view) */
    private lateinit var homeList: HomeList
    private lateinit var homeSearch: HomeSearch
    private lateinit var manualPill: TextView
    // Covers view: the selected game's screenshot behind everything, its description, trailer and screenshots
    private lateinit var coversBackdrop: ImageView
    private lateinit var coversShade: View
    private lateinit var gameDesc: TextView
    private lateinit var coverLoading: LoadingBars
    private lateinit var mediaCol: LinearLayout
    private lateinit var mediaBig: ImageView
    private val mediaThumbs = mutableListOf<ImageView>()
    /** Covers view: -1 = the shelf has the focus; otherwise the trailer / screenshot with the focus (▲ from the shelf) */
    private var mediaFocus = -1
    private var mediaUrls = listOf<String>()
    private var mediaTrailer = false
    private var fichaPending: String? = null
    private val homePrefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private var listMode: Boolean
        get() = homePrefs.getBoolean("list", false)
        set(value) = homePrefs.edit().putBoolean("list", value).apply()
    private lateinit var covers: CoverLoader
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var games: Map<String, List<Game>> = emptyMap()
    /**
     * The tabs of the home screen: «Seguir jugando» (unless hidden) and the consoles shown, as chosen in
     * Retro Ajustes → Inicio. Retro Ajustes isn't a tab any more: it opens with Options / + or the top bar's button.
     */
    private var homeTabs: List<Console> = emptyList()
    /** 0 until homeTabs.size = the tabs; homeTabs.size = Retro Ajustes (not in the row). */
    private var tab = 0
        set(value) {
            field = value
            homeTabs.getOrNull(value)?.takeIf { it != RECENT }?.let { lastConsole = it }
        }
    /** Last console visited: RetroShop opens on it (also from «Seguir jugando» and Retro Ajustes). */
    private var lastConsole = CONSOLES[0]
    /** The tab Retro Ajustes was opened from: B / Options go back to it. */
    private var tabBeforeSettings = 0
    private val selected = IntArray(CONSOLES.size + 1)
    private var settingIndex = 0
    private var zone = Zone.CONTENT
    private var initialTabChosen = false
    private var hasStorage = false
    private var heroKey: String? = null
    private lateinit var splash: View
    private var splashBarDone = false
    private var libraryLoaded = false

    private val settingsTab get() = tab == homeTabs.size
    private val tabConsole get() = homeTabs.getOrElse(tab) { lastConsole }
    private val recentTab get() = !settingsTab && homeTabs.getOrNull(tab) == RECENT
    private val consoleGames get() = if (settingsTab) emptyList() else games[tabConsole.id].orEmpty()
    /** Per tab: selection in the Drive row (below the stick's) and whether that row has the focus. */
    private val driveSel = IntArray(CONSOLES.size + 1)
    private val inDrive = BooleanArray(CONSOLES.size + 1)
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
            val now = if (settingsTab) emptyList() else driveFor(tabConsole).map { it.key }
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
            // A second pad: RetroArch gives it the next player port by itself (connection order)
            if (InputDevice.getDevice(deviceId)?.let(StickStatus::isGamepad) == true && StickStatus.controllers().size == 2) {
                Toast.makeText(this@MainActivity, "Mando 2 conectado: en los juegos será el jugador 2", Toast.LENGTH_LONG).show()
            }
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
            // The stick's copy: offered once on a new stick, then once a week
            if (DriveSync.phase != DriveSync.Phase.ERROR) checkBackups()
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

    /** A stick that picks its games: requests from the webapp, every minute while the home screen is up. */
    private val requestsTicker = object : Runnable {
        override fun run() {
            if (!DriveSync.autoRoms(this@MainActivity)) io.execute {
                val any = runCatching { DriveSync.hasRequests(this@MainActivity) }.getOrDefault(false)
                if (any) main.post { if (DriveSync.phase != DriveSync.Phase.SYNCING) DriveSync.sync(this@MainActivity) }
            }
            main.postDelayed(this, 60_000L)
        }
    }

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

        val root = splash.parent as FrameLayout
        homeList = HomeList(this, covers).also { hl ->
            root.addView(hl.backdrop, 0, FrameLayout.LayoutParams(-1, -1))
            root.addView(hl.shade, 1, FrameLayout.LayoutParams(-1, -1))
            root.addView(hl.view, root.indexOfChild(splash), FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(150); bottomMargin = dp(40) })
        }
        coversBackdrop = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.85f; visibility = View.GONE }
        root.addView(coversBackdrop, 0, FrameLayout.LayoutParams(dp(750), -1, Gravity.END))
        coversShade = FrameLayout(this).apply {
            visibility = View.GONE
            // Dark on the left (text), the screenshot showing on the right; dark at the top (tabs) and bottom (shelf)
            addView(View(this@MainActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xFF0D0B1F.toInt(), 0xFF0D0B1F.toInt(), 0xE00D0B1F.toInt(), 0x990D0B1F.toInt(), 0x5C0D0B1F.toInt(), 0x2E0D0B1F))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@MainActivity).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xEB0D0B1F.toInt(), 0x800D0B1F.toInt(), 0x100D0B1F, 0x000D0B1F, 0x000D0B1F, 0x200D0B1F, 0x900D0B1F.toInt(), 0xD00D0B1F.toInt(), 0xF50D0B1F.toInt(), 0xFF0D0B1F.toInt()))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(coversShade, 1, FrameLayout.LayoutParams(-1, -1))
        gameDesc = findViewById(R.id.gameDesc)
        coverLoading = LoadingBars(this, lines = 3)
        (gameDesc.parent as LinearLayout).let { col -> col.addView(coverLoading, col.indexOfChild(gameDesc) + 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
        mediaBig = mediaImage(10)
        mediaCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            visibility = View.GONE
            addView(TextView(this@MainActivity).apply {
                text = "TRÁILER Y CAPTURAS"; letterSpacing = 0.15f
                setTextColor(getColor(R.color.text_2)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
            })
            addView(mediaBig, LinearLayout.LayoutParams(dp(240), dp(135)).apply { topMargin = dp(5) })
            addView(LinearLayout(this@MainActivity).apply {
                clipChildren = false
                repeat(3) { i -> addView(mediaImage(7).also { mediaThumbs += it }, LinearLayout.LayoutParams(dp(76), dp(43)).apply { if (i < 2) marginEnd = dp(6) }) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }
        (gamePanel as LinearLayout).apply { clipChildren = false }.addView(mediaCol, LinearLayout.LayoutParams(dp(240), -2).apply { marginStart = dp(20) })
        Fichas.addListener(fichaListener)
        RetroAchievements.addListener(fichaListener)
        homeSearch = HomeSearch(this, covers).also { hs ->
            root.addView(hs.view, root.indexOfChild(splash), FrameLayout.LayoutParams(-1, -1))
            hs.candidates = { searchCandidates() }
            hs.onPick = { pickSearch(it) }
        }
        // Covers view: "Manual" next to "Jugar" (shown when the game has one)
        val play = findViewById<View>(R.id.playText).parent as View
        val col = play.parent as LinearLayout
        val at = col.indexOfChild(play)
        val playLp = play.layoutParams
        col.removeView(play)
        manualPill = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = resources.getFont(R.font.inter_bold)
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(18), 0)
            background = GradientDrawable().apply { setColor(Color.parseColor("#1FFFFFFF")); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.parseColor("#33FFFFFF")) }
            visibility = View.GONE
        }
        col.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(play, LinearLayout.LayoutParams(-2, -2))
            addView(manualPill, LinearLayout.LayoutParams(-2, dp(38)).apply { marginStart = dp(10) })
        }, at, playLp)
        ShopApi.token = ShopPairing.token(this)
        Manuals.addListener { render() }
        Manuals.load(this)

        homeTabs = computeHomeTabs()
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
        // A new stick: the welcome assistant installs RetroArch, the cores and the configuration first
        if (Setup.needed(this)) startActivity(Intent(this, SetupActivity::class.java))
        // Then Google Drive: without it there are no games, so nothing else until it's connected
        else main.post(::requireDrive)
        // Just updated: what's new, once (not on a new stick, nor before Drive is connected)
        main.post {
            if (!isFinishing && !Setup.needed(this) && driveAuth.isConnected && News.pending(this).isNotEmpty())
                startActivity(Intent(this, NewsActivity::class.java))
        }
        hasStorage =checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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
        main.post(requestsTicker)
        // Back from a game or from boot: sync if the last one is older than a minute, then every 15 min.
        if (hasStorage && System.currentTimeMillis() - DriveSync.lastSuccess(this) > 60_000) DriveSync.sync(this)
        // A new launcher in Drive: ask right away (not only after a whole sync).
        io.execute {
            val found = runCatching { Updater.checkNow(this) }.onFailure { android.util.Log.w("RetroStick", "Update check failed", it) }
                .getOrDefault(false)
            if (found) main.post { refreshStatus(); offerUpdate() }
            // Sticks set up by the assistant: newer emulator cores (asked) and configuration (applied by itself)
            val configUpdated = runCatching { Setup.checkUpdates(this) }
                .onFailure { android.util.Log.w("RetroStick", "Setup update check failed", it) }.getOrDefault(false)
            main.post {
                if (configUpdated) Toast.makeText(this, "Configuración de los emuladores actualizada", Toast.LENGTH_LONG).show()
                if (!found) offerCoresUpdate()
            }
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
        main.removeCallbacks(requestsTicker)
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
                val keepKeys = homeTabs.indices.map { i -> games[homeTabs[i].id]?.getOrNull(selected[i])?.key }
                val keepDrive = homeTabs.indices.map { i -> driveFor(homeTabs[i]).getOrNull(driveSel[i])?.baseName }
                // «Seguir jugando»: the last games played, every console together
                // (minus the ones taken out of the list, until they're played again)
                val removed = recentRemoved()
                games = scanned + (RECENT.id to scanned.values.flatten()
                    .filter { it.lastPlayed > 0 && it.lastPlayed > (removed[it.key] ?: 0L) }
                    .sortedByDescending { it.lastPlayed }.take(RECENT_COUNT))
                homeTabs.forEachIndexed { i, c ->
                    val list = games[c.id].orEmpty()
                    val drive = driveFor(c)
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
                // Every game's ficha (and its screenshots) on the stick, in the background
                Fichas.prefetch(this, scanned.values.flatten(), covers)
                libraryLoaded = true
                maybeHideSplash()
            }
        }
    }

    /**
     * Start on «Seguir jugando» (its first game is the last one played); without that tab, on the console of the
     * last game played with that game selected; else the first console with games.
     */
    private fun chooseInitialTab() {
        initialTabChosen = true
        val recent = games[RECENT.id].orEmpty().firstOrNull()
        val recentIndex = homeTabs.indexOf(RECENT)
        val consoleIndex = recent?.let { homeTabs.indexOf(it.console) } ?: -1
        tab = when {
            recentIndex >= 0 && recent != null -> recentIndex
            consoleIndex >= 0 -> consoleIndex
            else -> homeTabs.indexOfFirst { it != RECENT && games[it.id].orEmpty().isNotEmpty() }.coerceAtLeast(0)
        }
        selected[tab] = if (recent != null && tab == consoleIndex) games[recent.console.id].orEmpty().indexOf(recent).coerceAtLeast(0) else 0
    }

    /** The tabs as chosen in Retro Ajustes → Inicio: «Seguir jugando» first, then the consoles not hidden (at least one). */
    private fun computeHomeTabs(): List<Console> {
        val hidden = homePrefs.getStringSet("hiddenConsoles", emptySet()).orEmpty()
        val consoles = CONSOLES.filter { it.id !in hidden }.ifEmpty { CONSOLES }
        return listOfNotNull(RECENT.takeIf { homePrefs.getBoolean("recentTab", true) }) + consoles
    }

    /** Applies a new choice of tabs: the row is rebuilt and every tab starts on its first game. */
    private fun applyHomeTabs() {
        val inSettings = settingsTab
        val before = homeTabs.getOrNull(if (inSettings) tabBeforeSettings else tab)
        homeTabs = computeHomeTabs()
        selected.fill(0); driveSel.fill(0); inDrive.fill(false)
        // Back to the tab you were on; if it's gone, the console of the last game played (as on start)
        val last = games[RECENT.id].orEmpty().firstOrNull()
        tabBeforeSettings = homeTabs.indexOf(before).takeIf { it >= 0 }
            ?: last?.let { homeTabs.indexOf(it.console) }?.takeIf { it >= 0 }
            ?: 0
        if (last != null && homeTabs.getOrNull(tabBeforeSettings) == last.console)
            selected[tabBeforeSettings] = games[last.console.id].orEmpty().indexOf(last).coerceAtLeast(0)
        tab = if (inSettings) homeTabs.size else tabBeforeSettings
        buildTabs()
        updateTabCounts()
        buildShelf()
    }

    /** Games taken out of «Seguir jugando» (key → their last session then): they come back when played again. */
    private fun recentRemoved(): Map<String, Long> =
        homePrefs.getStringSet("recentRemoved", emptySet()).orEmpty().mapNotNull { e ->
            val cut = e.lastIndexOf('|')
            if (cut < 0) null else e.substring(0, cut) to (e.substring(cut + 1).toLongOrNull() ?: 0L)
        }.toMap()

    /** «Quitar de la lista» in «Seguir jugando»: nothing is deleted, the game just leaves this list until played again. */
    private fun removeFromRecent() {
        val game = currentGame ?: return
        keySound = Sounds.Sound.BACK
        val kept = recentRemoved().filterKeys { it != game.key }.map { "${it.key}|${it.value}" }.toSet()
        homePrefs.edit().putStringSet("recentRemoved", kept + "${game.key}|${game.lastPlayed}").apply()
        Toast.makeText(this, "${game.title} ya no sale en Seguir jugando (vuelve si lo juegas)", Toast.LENGTH_SHORT).show()
        reload()
    }

    /** The Drive row of a tab: the console's games in the Drive collection («Seguir jugando» has none). */
    private fun driveFor(console: Console) = if (console == RECENT) emptyList() else DriveCollection.items(this, console)

    private fun openSettings() {
        if (settingsTab) return
        tabBeforeSettings = tab
        mediaFocus = -1
        tab = homeTabs.size
        zone = Zone.CONTENT
        keySound = Sounds.Sound.OPEN
        buildShelf()
    }

    private fun closeSettings() {
        tab = tabBeforeSettings.coerceIn(0, maxOf(0, homeTabs.size - 1))
        zone = Zone.CONTENT
        buildShelf()
    }

    /** Retro Ajustes → Inicio: the view (covers / list), «Seguir jugando» and which consoles show as tabs. */
    private fun showHomeSetup() {
        // The tabs, then (last) whether RetroStick opens by itself when the stick starts (BootReceiver)
        val labels = arrayOf("Seguir jugando") + CONSOLES.map { it.name }.toTypedArray() + "Abrir RetroStick al encender el stick"
        val hidden = homePrefs.getStringSet("hiddenConsoles", emptySet()).orEmpty()
        val checked = booleanArrayOf(homePrefs.getBoolean("recentTab", true)) + CONSOLES.map { it.id !in hidden }.toBooleanArray() +
            BootReceiver.enabled(this)
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Inicio · pestañas y arranque")
            .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
            .setPositiveButton("Guardar") { _, _ ->
                BootReceiver.setEnabled(this, checked.last())
                if (checked.drop(1).dropLast(1).none { it }) {
                    Toast.makeText(this, "Deja al menos una consola", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                homePrefs.edit()
                    .putBoolean("recentTab", checked[0])
                    .putStringSet("hiddenConsoles", CONSOLES.filterIndexed { i, _ -> !checked[i + 1] }.map { it.id }.toSet())
                    .apply()
                applyHomeTabs()
                settings[VIEW_SETTING].subtitle = homeLabel()
                render()
            }
            .setNeutralButton(if (listMode) "Ver en carátulas" else "Ver en lista") { _, _ ->
                toggleView()
                settings[VIEW_SETTING].subtitle = homeLabel()
                render()
            }
            .setNegativeButton("Cerrar", null)
            .showForPad()
    }

    /** "Carátulas · 10 pestañas" */
    private fun homeLabel(): String {
        val n = homeTabs.size
        return (if (listMode) "Lista" else "Carátulas") + " · " + (if (n == 1) "1 pestaña" else "$n pestañas")
    }

    private fun writeStatusJson() {
        if (!hasStorage) return
        io.execute { runCatching { StickStatus.writeStatusJson(this) } }
    }

    // ---- Tabs ----

    private fun buildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        homeTabs.forEach { console ->
            val v = layoutInflater.inflate(R.layout.item_tab, tabs, false)
            v.findViewById<ImageView>(R.id.tabLogo).setImageResource(console.logo)
            v.findViewById<TextView>(R.id.tabName).text = console.name
            tabs.addView(v)
            tabViews += v
        }
    }

    private fun updateTabCounts() {
        homeTabs.forEachIndexed { i, c ->
            val n = games[c.id].orEmpty().size
            tabViews[i].findViewById<TextView>(R.id.tabCount).text = if (n == 1) "1 juego" else "$n juegos"
        }
    }

    private fun renderTabs() {
        tabViews.forEachIndexed { i, v ->
            val sel = i == tab
            v.layoutParams = (v.layoutParams as LinearLayout.LayoutParams).apply {
                // Up to eleven tabs («Seguir jugando» + ten consoles) in the 880 dp row
                width = dp(if (sel) 112 else 69)
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
            logo.maxWidth = dp(if (sel) 92 else 50)
            // Unselected logos are white (filter: brightness(0) invert(1)); the selected one keeps its colours.
            val console = homeTabs.getOrNull(i)
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

    /** In the top bar (Zone.GEAR): 0 = «Retro Ajustes», 1 = the controllers. */
    private var gearIndex = 0

    /**
     * The top bar's buttons: «Retro Ajustes» (lit while it is open) and the controllers; the one with the focus
     * (▲ from the tabs, ◀ ▶ between them) is outlined.
     */
    private fun renderGear() {
        val chip = findViewById<TextView>(R.id.settingsChip)
        val focus = zone == Zone.GEAR && gearIndex == 0
        chip.background = GradientDrawable().apply {
            cornerRadius = dp(9).toFloat()
            setColor(Color.parseColor(if (settingsTab || focus) "#7B5CFF" else "#14FFFFFF"))
            if (focus) setStroke(dp(2), Color.WHITE)
        }
        chip.alpha = if (settingsTab || focus) 1f else 0.85f
        val pads = findViewById<View>(R.id.padsChip)
        val padsFocus = zone == Zone.GEAR && gearIndex == 1
        pads.background = if (padsFocus) GradientDrawable().apply {
            cornerRadius = dp(9).toFloat(); setColor(Color.parseColor("#7B5CFF")); setStroke(dp(2), Color.WHITE)
        } else null
    }

    /** The controllers window: each connected pad with its player number and battery (when the stick can read it). */
    private fun showPads() {
        keySound = Sounds.Sound.OPEN
        val pads = StickStatus.gamepads()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(6), dp(22), dp(4)) }
        fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            typeface = resources.getFont(if (bold) R.font.inter_bold else R.font.inter_regular)
        }
        if (pads.isEmpty()) {
            col.addView(label(13f, getColor(R.color.text_2)).apply { text = "No hay ningún mando conectado. Enciende uno o empareja uno nuevo." })
        }
        var unknown = false
        pads.forEachIndexed { i, d ->
            val battery = StickStatus.batteryLevel(d)
            if (battery == null) unknown = true
            val style = StickStatus.padStyle(d)
            val row = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(16), dp(10))
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.parseColor("#14FFFFFF")) }
            }
            // Player number in a coloured disc, like the light bar of the pad
            val colors = listOf("#3A8EE0", "#E53E3E", "#2FB45A", "#E5B53A", "#9D8CFF", "#E2575C", "#5B6BFF", "#F2B632")
            row.addView(label(15f, Color.WHITE, bold = true).apply {
                text = "J${i + 1}"; gravity = android.view.Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor(colors[i % colors.size])) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(14f, Color.WHITE, bold = true).apply { text = StickStatus.shortControllerName(d.name) })
                addView(label(10.5f, getColor(R.color.text_2)).apply {
                    text = listOf(
                        when (style) { PadStyle.PLAYSTATION -> "PlayStation"; PadStyle.NINTENDO -> "Nintendo"; PadStyle.XBOX -> "Xbox / genérico"; else -> "Mando" },
                        "Jugador ${i + 1} en los juegos",
                    ).joinToString("  ·  ")
                })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
            // Battery: a small bar with the percentage, or a dash when unknown
            row.addView(LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.END
                if (battery != null) {
                    val tint = Color.parseColor(when { battery <= 15 -> "#E53E3E"; battery <= 35 -> "#E5B53A"; else -> "#4ADE80" })
                    addView(android.widget.FrameLayout(this@MainActivity).apply {
                        background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setStroke(dp(2), Color.WHITE) }
                        setPadding(dp(3), dp(3), dp(3), dp(3))
                        addView(View(this@MainActivity).apply { background = GradientDrawable().apply { cornerRadius = dp(1).toFloat(); setColor(tint) } },
                            android.widget.FrameLayout.LayoutParams(dp(34 * battery / 100).coerceAtLeast(dp(2)), -1))
                    }, LinearLayout.LayoutParams(dp(40), dp(18)))
                    addView(label(13f, Color.WHITE, bold = true).apply { text = "$battery %" }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                } else {
                    addView(label(11f, getColor(R.color.text_2)).apply { text = "Batería —" })
                }
            }, LinearLayout.LayoutParams(-2, -2))
            col.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (unknown) col.addView(label(10.5f, getColor(R.color.text_2)).apply {
            text = "«—»: este stick no puede leer la batería de esos mandos (su Android 9 no trae cómo). La luz del propio mando avisa cuando queda poca."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(if (pads.size == 1) "Mando conectado" else if (pads.isEmpty()) "Mandos" else "${pads.size} mandos conectados")
            .setView(col)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Emparejar otro") { _, _ -> open(Intent(CONNECT_INPUT)) }
            .setNegativeButton("Botones") { _, _ -> open(Intent(this, ButtonsActivity::class.java).putExtra("console", lastConsole.id)) }
            .showForPad()
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
            v.findViewById<TextView>(R.id.coverFavorite).apply {
                text = withIcons(listOfNotNull("★".takeIf { game.favorite }, TROPHY.takeIf { game.finished != null }).joinToString(" "))
                visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            }
            v.findViewById<View>(R.id.coverBadge).visibility = if (game == playing) View.VISIBLE else View.GONE
            val image = v.findViewById<ImageView>(R.id.coverImage)
            image.tag = game.key
            covers.load(game, dp(98)) { bitmap -> if (image.tag == game.key) image.setImageBitmap(bitmap ?: logoCover(this, game.console, dp(98))) }
            coverRow.addView(v)
            coverViews += v
        }
        if (recentTab) { buildDriveRow(); return }
        // «Añadir juegos» of this console: its logo, its cartridge or disc and RetroShop's count (ShopCard)
        val add = layoutInflater.inflate(R.layout.item_cover, coverRow, false)
        val console = tabConsole
        add.findViewById<FrameLayout>(R.id.coverInner).apply {
            background = ShopCard.background(console, dp(8).toFloat())
            clipToOutline = true
            findViewById<View>(R.id.coverTitle).visibility = View.GONE
            findViewById<View>(R.id.coverImage).visibility = View.GONE
            addView(ShopCard.shelfContent(this@MainActivity, console), 0, FrameLayout.LayoutParams(-1, -1))
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
        driveItems = if (settingsTab) emptyList() else driveFor(tabConsole)
        driveInfo.clear()
        driveBadges.clear()
        val show = if (driveItems.isEmpty()) View.GONE else View.VISIBLE
        driveTitle.visibility = show
        driveScroll.visibility = show
        if (!settingsTab) driveTitle.text = getString(R.string.shelf_title_drive, tabConsole.name)
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
            v.background = if (!drive && i == sel && zone == Zone.CONTENT && mediaFocus < 0) getDrawable(R.drawable.bg_cover_sel) else null
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

        val console = tabConsole
        shelfTitle.text = if (recentTab) getString(R.string.shelf_title_recent) else getString(R.string.shelf_title, console.name)
        if (game == null) {
            emptyText.text = when {
                !hasStorage -> getString(R.string.need_storage)
                recentTab -> getString(R.string.empty_recent)
                consoleGames.isEmpty() -> getString(R.string.empty_console, console.name)
                else -> getString(R.string.shop_from_shelf, console.name)
            }
            return
        }
        findViewById<TextView>(R.id.gameTitle).text = game.title
        // The ficha kept on the stick (Fichas): rating, year, genres, players, description, trailer, screenshots
        val entry = Fichas.cached(this, game.title, game.console.id)
        val info = entry?.info
        val known = entry != null || Fichas.failed(game.title, game.console.id)
        if (entry == null || entry.stale) requestFicha(game.title, game.console.id)
        findViewById<TextView>(R.id.gameMeta).apply { text = withIcons(listOfNotNull(
                info?.rating?.let { "★ " + String.format("%.1f", it).replace('.', ',') },
                info?.year?.toString(),
                info?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() },
                info?.players?.takeIf { it >= 2 }?.let { "$PLAYERS Hasta $it" },
                game.console.name.takeIf { info == null || recentTab }, game.region,
                if (drive != null) getString(R.string.in_drive_meta) else null,
                game.finished?.let { "$TROPHY Terminado" },
            ).joinToString("  ·  ")) }
        gameDesc.text = when {
            info?.description != null -> info.description
            !known -> ""
            ShopApi.token == null -> "Vincula RetroShop (Retro Ajustes) para ver la descripción, el tráiler y las capturas."
            else -> ""
        }
        gameDesc.visibility = if (gameDesc.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        coverLoading.show(!known && !listMode)
        bindCoverMedia(game.key, info)
        findViewById<TextView>(R.id.lastPlayed).text = formatWhen(game.lastPlayed)
        findViewById<TextView>(R.id.timePlayed).text = formatDuration(game.runtimeSeconds)
        val achievements = achievementsText(game, onStick = drive == null)
        findViewById<View>(R.id.achievementsColumn).visibility = if (achievements != null) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.achievements).text = achievements
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
            else -> R.string.play
        })

        if (heroKey != game.key) {
            heroKey = game.key
            hero.background = fallbackGradient(game, 12)
            heroFallback.text = game.title
            heroImage.setImageDrawable(null)
            heroImage.tag = game.key
            covers.load(game, dp(200)) { bitmap ->
                if (heroImage.tag != game.key) return@load
                val b = bitmap ?: logoCover(this, game.console, dp(200))
                heroImage.setImageBitmap(b)
                sizeHero(b.width, b.height)
            }
        }
    }

    /** The big cover keeps the box's shape (landscape SNES / GBA boxes, portrait Mega Drive), within 150×200 dp. */
    private fun sizeHero(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val aspect = w.toFloat() / h
        var hw = dp(150)
        var hh = (hw / aspect).toInt()
        if (hh > dp(200)) { hh = dp(200); hw = (hh * aspect).toInt() }
        val lp = hero.layoutParams
        if (lp.width != hw || lp.height != hh) { lp.width = hw; lp.height = hh; hero.layoutParams = lp }
    }

    /** A ficha not kept yet: asked for a moment after the selection settles. */
    private fun requestFicha(title: String, console: String) {
        val k = "$console|$title"
        if (fichaPending == k) return
        fichaPending = k
        main.postDelayed({
            if (fichaPending == k) { fichaPending = null; Fichas.request(this, title, console) }
        }, 250)
    }

    private val fichaListener: () -> Unit = { if (!isDestroyed) render() }

    private fun mediaImage(radius: Int) = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = GradientDrawable().apply { setColor(Color.parseColor("#14FFFFFF")); cornerRadius = dp(radius).toFloat() }
    }

    /** Trailer (big, with the play badge) and three screenshots on the right; the first screenshot as the backdrop. */
    private fun bindCoverMedia(key: String, info: IgdbInfo?) {
        val urls = info?.let { Fichas.mediaUrls(it, 4) }.orEmpty()
        if (urls != mediaUrls) { mediaUrls = urls; mediaFocus = -1 }
        mediaTrailer = info?.trailerId != null
        mediaCol.visibility = if (urls.isEmpty()) View.GONE else View.VISIBLE
        (listOf(mediaBig) + mediaThumbs).forEachIndexed { i, v ->
            val url = urls.getOrNull(i)
            v.visibility = if (url == null) View.INVISIBLE else View.VISIBLE
            if (url != null && v.tag != url) {
                v.tag = url
                v.setImageDrawable(null)
                covers.loadUrl(url, if (i == 0) dp(240) else dp(76)) { b -> if (v.tag == url) v.setImageBitmap(b) }
            }
            val focus = i == mediaFocus && zone == Zone.CONTENT
            val play = if (i == 0 && mediaTrailer) getDrawable(R.drawable.ic_shop_play_badge) else null
            val border = if (focus) getDrawable(R.drawable.fg_hero_border) else null
            v.foreground = listOfNotNull(play, border).let { if (it.size > 1) android.graphics.drawable.LayerDrawable(it.toTypedArray()) else it.firstOrNull() }
            v.scaleX = if (focus) 1.06f else 1f
            v.scaleY = v.scaleX
        }
        val shot = info?.screenshots?.firstOrNull()
        if (shot == null) { coversBackdrop.tag = null; coversBackdrop.setImageDrawable(null) }
        else if (coversBackdrop.tag != shot) {
            coversBackdrop.tag = shot
            coversBackdrop.setImageDrawable(null)
            covers.loadUrl(shot, dp(480)) { b -> if (coversBackdrop.tag == shot) { coversBackdrop.setImageBitmap(b); renderBackdrop() } }
        }
    }

    /** The screenshot behind the covers view: only there, with a game selected. */
    private fun renderBackdrop() {
        val on = !listMode && !settingsTab && gamePanel.visibility == View.VISIBLE
        coversShade.visibility = if (on) View.VISIBLE else View.GONE
        coversBackdrop.visibility = if (on && coversBackdrop.drawable != null) View.VISIBLE else View.GONE
    }

    /** Covers view: ▲ from the shelf goes up to the trailer / screenshots; ◀ ▶ through them, ✕ opens, ▼ / ○ back. */
    private fun handleCoverMediaKey(code: Int): Boolean {
        val n = mediaUrls.size
        if (mediaFocus >= 0) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> mediaFocus = (mediaFocus - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> mediaFocus = (mediaFocus + 1).coerceAtMost(n - 1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> mediaFocus = -1
                KeyEvent.KEYCODE_DPAD_UP -> { mediaFocus = -1; zone = Zone.TABS }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> openMedia(mediaFocus)
                else -> return false
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP && !driveRow && n > 0 && currentGame != null) { mediaFocus = 0; return true }
        return false
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
            Setting("💾", getString(R.string.set_space), "") { open(Intent(this, StorageActivity::class.java)) },
            // What each button does in each console (ButtonsActivity); opens on the console of the tab you came from
            Setting("🕹️", getString(R.string.set_controllers), "") {
                open(Intent(this, ButtonsActivity::class.java).putExtra("console", lastConsole.id))
            },
            Setting("👾", getString(R.string.set_retroarch), getString(R.string.set_retroarch_sub)) {
                launchIntent(RETROARCH_PACKAGE)?.let(::open) ?: toast(R.string.retroarch_missing)
            },
            Setting("📱", getString(R.string.set_other_apps), getString(R.string.set_other_apps_sub)) {
                launchIntent(FLAUNCHER_PACKAGE)?.let(::open) ?: toast(R.string.app_missing)
            },
            Setting("☁️", getString(R.string.set_account), "") {
                if (driveAuth.isConnected) showDriveMenu() else startDriveLogin()
            },
            Setting("🛒", getString(R.string.set_shop), getString(R.string.set_shop_sub)) {
                // Opens on the console of the tab you came from (the first one from Retro Ajustes).
                val from = lastConsole.id
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
            Setting("🏠", getString(R.string.set_view), homeLabel()) { showHomeSetup() },
            Setting("✨", getString(R.string.set_boot), bootLabel()) { showBootScreens() },
            Setting("🏆", getString(R.string.set_trophies), trophiesLabel()) {
                // Signed in: straight to Mis logros (signing out is in there); else the login screen (keyboard or QR)
                open(Intent(this, if (RetroAchievements.account(this) != null) ProfileActivity::class.java else RaLoginActivity::class.java))
            },
            // What the last updates brought (News.kt), the screen shown once after each update
            Setting("🆕", "Novedades", "Versión ${BuildConfig.VERSION_NAME}") {
                open(Intent(this, NewsActivity::class.java).putExtra(NewsActivity.EXTRA_ALL, true))
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
            pads.size == 1 -> "${StickStatus.shortControllerName(pads[0])} · J1" +
                (StickStatus.gamepads().firstOrNull()?.let(StickStatus::batteryLevel)?.let { " · $it %" } ?: "")
            else -> "${pads.size} mandos · " + (1..pads.size).joinToString(" ") { "J$it" }
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
        settings[VIEW_SETTING].subtitle = homeLabel()
        settings[BOOT_SETTING].subtitle = bootLabel()
        settings[RA_SETTING].subtitle = trophiesLabel()
        settings[10].subtitle = Updater.available?.let { getString(R.string.update_available_sub, it.versionName) }
            ?: getString(R.string.update_current, BuildConfig.VERSION_NAME)
        settings[2].subtitle = net?.let { "Conectado por $it" } ?: getString(R.string.offline)
        settings[5].subtitle = if (pads.isEmpty()) "Qué hace cada botón" else pads.joinToString(", ") { StickStatus.shortControllerName(it) } + " · por consola"
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
            settingsTab -> {
                addHint(g.confirm, R.string.hint_open)
                g.back?.let { addHint(it, R.string.hint_back) }
            }
            zone == Zone.GEAR -> addHint(g.confirm, R.string.hint_open)
            !listMode && mediaFocus >= 0 -> {
                addHint(g.confirm, R.string.hint_open)
                g.back?.let { addHint(it, R.string.hint_back) }
            }
            currentDrive != null -> {
                addHint(g.confirm, R.string.hint_ficha)
                g.back?.let { addHint(it, R.string.hint_back) }
            }
            currentGame == null -> addHint(g.confirm, R.string.hint_open)
            currentGame != null -> {
                addHint(g.confirm, R.string.hint_play)
                g.back?.let { addHint(it, R.string.hint_back) }
                g.top?.let { addHint(it, if (recentTab) R.string.hint_remove_recent else R.string.hint_favorite) }
                g.left?.let { addHint(it, R.string.hint_details) }
            }
        }
        if (!settingsTab) addHint(g.shoulders, if (padStyle == PadStyle.REMOTE) R.string.hint_consoles else R.string.hint_switch)
        if (!settingsTab && padStyle != PadStyle.REMOTE) {
            // The button that opens the manual, drawn (the word "Create" read like "crear")
            if (manualPill.visibility == View.VISIBLE || (listMode && homeList.view.visibility == View.VISIBLE && currentManual() != null)) {
                addHint(Glyph.Pill(manualGlyph()), R.string.hint_manual)
                // The Share button's symbol inside its box, left of the word
                if (manualGlyph() == "Share") ((hints.getChildAt(hints.childCount - 1) as? LinearLayout)?.getChildAt(0) as? TextView)?.apply {
                    setCompoundDrawablesRelative(getDrawable(R.drawable.ic_share)!!.mutate().apply { setTint(getColor(R.color.chip_text)); setBounds(0, 0, dp(10), dp(10)) }, null, null, null)
                    compoundDrawablePadding = dp(2)
                }
            }
            if (currentGame?.let { RetroAchievements.supports(it.console) } == true && RetroAchievements.account(this) != null)
                addHint(Glyph.Pill(when (padStyle) { PadStyle.XBOX -> "RT"; PadStyle.NINTENDO -> "ZR"; else -> "R2" }), R.string.hint_achievements)
            addHint(Glyph.Pill(when (padStyle) { PadStyle.XBOX -> "LT"; PadStyle.NINTENDO -> "ZL"; else -> "L2" }), R.string.hint_search)
            addHint(Glyph.Pill(when (padStyle) { PadStyle.XBOX -> "Menu"; PadStyle.NINTENDO -> "+"; else -> "Options" }), R.string.hint_settings)
        }
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
            onKey(code)
        }
        return true
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
        "$tab/$zone/$settingIndex/${selected.joinToString()}/${inDrive.joinToString()}/${driveSel.joinToString()}/$listMode/${homeList.mediaFocus}/$mediaFocus"

    private fun onKey(code: Int) {
        if (homeSearch.isOpen) {
            homeSearch.onKey(code)?.let(Sounds::play)
            return
        }
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
        when (code) {
            // Options / + opens Retro Ajustes, and closes it again
            KeyEvent.KEYCODE_BUTTON_START -> { if (settingsTab) closeSettings() else openSettings(); render(); return }
            // Search in the selected console only
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (settingsTab) return
                homeSearch.scope = if (recentTab) "Todos tus juegos del stick y de tu Drive" else "Juegos de ${tabConsole.name} en el stick y en tu Drive"
                homeSearch.view.bringToFront()
                homeSearch.open(); keySound = Sounds.Sound.OPEN; return
            }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { openManual(); render(); return }
            KeyEvent.KEYCODE_BUTTON_R2 -> { openAchievements(); return }
        }
        if (listMode && !settingsTab && zone == Zone.CONTENT && handleListKey(code)) { render(); return }
        if (!listMode && !settingsTab && zone == Zone.CONTENT && handleCoverMediaKey(code)) { render(); return }
        if (zone == Zone.GEAR) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> zone = Zone.TABS
                KeyEvent.KEYCODE_DPAD_RIGHT -> gearIndex = 1
                KeyEvent.KEYCODE_DPAD_LEFT -> gearIndex = 0
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                    if (gearIndex == 1) showPads() else openSettings()
                KeyEvent.KEYCODE_BUTTON_L1 -> { zone = Zone.TABS; switchTab(-1) }
                KeyEvent.KEYCODE_BUTTON_R1 -> { zone = Zone.TABS; switchTab(1) }
            }
            render()
            return
        }
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
                tabsZone -> { zone = Zone.GEAR; gearIndex = 0 }
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
            // The home screen never closes: Back leaves Retro Ajustes or the tab row.
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (settingsTab) closeSettings() else zone = Zone.CONTENT
            // In «Seguir jugando» the favourite button takes the game out of the list instead
            KeyEvent.KEYCODE_BUTTON_Y -> if (recentTab) removeFromRecent() else toggleFavorite()
            KeyEvent.KEYCODE_BUTTON_X -> showDetails()
        }
        render()
    }

    /** L1 / R1 and ◀ ▶ on the row go through the tabs (from Retro Ajustes, starting at the tab it was opened from). */
    private fun switchTab(delta: Int) {
        mediaFocus = -1
        val n = homeTabs.size
        val from = if (settingsTab) tabBeforeSettings else tab
        tab = ((from + delta) % n + n) % n
        buildShelf()
    }

    private fun moveGame(delta: Int) {
        mediaFocus = -1
        if (driveRow) driveSel[tab] = (driveSel[tab] + delta).coerceIn(0, driveItems.size - 1)
        // Games, then the "Añade juegos desde RetroShop" card (index == number of games; «Seguir jugando» has none)
        else selected[tab] = (selected[tab] + delta).coerceIn(0, if (recentTab) maxOf(0, consoleGames.size - 1) else consoleGames.size)
    }

    // ---- Google Drive sign-in (code typed on the phone) ----

    /** The Drive sign-in on screen (only one at a time). */
    private var driveLoginDialog: AlertDialog? = null

    /**
     * A stick without Drive has no games: once the assistant is done, the sign-in takes the whole screen and can't be
     * closed until Drive is connected (it comes back by itself if the code expires or something fails).
     */
    private fun requireDrive() {
        if (isFinishing || isDestroyed || !driveAuth.isConfigured || driveAuth.isConnected) return
        if (driveLoginDialog?.isShowing == true || Setup.needed(this)) return
        startDriveLogin(required = true)
    }

    private fun startDriveLogin(required: Boolean = false) {
        if (!driveAuth.isConfigured) {
            toast(R.string.drive_missing_config)
            return
        }
        if (driveLoginDialog?.isShowing == true) return
        loginCancelled = false
        val intro = TextView(this).apply {
            text = "Tus juegos, BIOS y partidas se guardan en tu propio Google Drive. Para empezar, vincúlalo:"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            visibility = if (required) View.VISIBLE else View.GONE
        }
        val info = TextView(this).apply {
            text = "1. En el móvil, escanea el QR o abre"
            setTextColor(getColor(R.color.text_2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val url = TextView(this).apply {
            text = "google.com/device"
            setTextColor(getColor(R.color.brand_accent))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = resources.getFont(R.font.inter_bold)
        }
        val step2 = TextView(this).apply {
            text = "2. Escribe este código y elige tu cuenta de Google:"
            setTextColor(getColor(R.color.text_2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        val code = TextView(this).apply {
            text = "…"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            letterSpacing = 0.12f
            typeface = resources.getFont(R.font.inter_extrabold)
        }
        val waiting = TextView(this).apply {
            text = "Esperando a que lo confirmes en el móvil…"
            setTextColor(getColor(R.color.text_3))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            listOf(intro, info, url, step2, code, waiting).forEach { addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) }) }
        }
        // google.com/device never changes: its QR is a drawable made once (res/drawable/qr_google_device.xml)
        val qr = android.widget.ImageView(this).apply { setImageResource(R.drawable.qr_google_device) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
            addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
            addView(qr, LinearLayout.LayoutParams(dp(150), dp(150)).apply { marginStart = dp(24) })
        }
        val builder = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(R.string.drive_login_title)
            .setView(box)
        if (!required) {
            builder.setNegativeButton(R.string.cancel) { _, _ -> loginCancelled = true }
                .setOnCancelListener { loginCancelled = true }
        }
        val dialog = builder.showForPad()
        if (required) {
            // Nothing closes it: Back, B and Escape are swallowed (padKeys' B would cancel() it even when not cancelable)
            dialog.setCancelable(false)
            dialog.setOnKeyListener { _, keyCode, _ ->
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B || keyCode == KeyEvent.KEYCODE_ESCAPE
            }
        }
        driveLoginDialog = dialog

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
                    // Still without Drive: a new code
                    if (required && outcome != DriveAuth.Outcome.CONNECTED) main.postDelayed(::requireDrive, 2500)
                }
            } catch (e: Exception) {
                main.post {
                    dialog.dismiss()
                    Toast.makeText(this, getString(R.string.drive_login_error, e.message), Toast.LENGTH_LONG).show()
                    // No internet, for instance: tried again in a while
                    if (required) main.postDelayed(::requireDrive, 10_000)
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

    /** Newer «RetroStick Núcleos» in the release (Setup.checkUpdates): offered like a launcher update. */
    private fun offerCoresUpdate() {
        val cores = Setup.coresUpdate ?: return
        if (cores.versionCode == Setup.dismissedCores || updateDialog?.isShowing == true || isFinishing) return
        updateDialog = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Emuladores nuevos")
            .setMessage("Hay una versión nueva de RetroStick Núcleos, los emuladores de cada consola (${cores.version}). " +
                "Tus juegos y partidas no cambian.")
            .setPositiveButton(R.string.update_install) { _, _ -> installCores(cores) }
            .setNegativeButton(R.string.update_later) { _, _ -> Setup.dismissedCores = cores.versionCode }
            .setOnCancelListener { Setup.dismissedCores = cores.versionCode }
            .showForPad()
    }

    private fun installCores(cores: Setup.Asset) {
        if (!canInstallApps()) return
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { Setup.download(cores, java.io.File(cacheDir, "update/cores.apk")) {} }
            main.post {
                result.onSuccess { open(Updater.installIntent(this, "cores.apk")) }
                    .onFailure { Toast.makeText(this, getString(R.string.update_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Android 8+: the user allows RetroStick to install apps once (Ajustes > Seguridad > Fuentes desconocidas). */
    private fun canInstallApps(): Boolean {
        if (packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(this, R.string.update_allow, Toast.LENGTH_LONG).show()
        // Some TV builds lack the per-app screen: fall back to Android's security settings.
        val perApp = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
        open(if (perApp.resolveActivity(packageManager) != null) perApp else Intent(Settings.ACTION_SECURITY_SETTINGS))
        return false
    }

    private fun installUpdate(update: Updater.Update) {
        if (!canInstallApps()) return
        Toast.makeText(this, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        io.execute {
            val result = runCatching { Updater.download(this, update) }
            main.post {
                result.onSuccess { open(Updater.installIntent(this)) }
                    .onFailure { Toast.makeText(this, getString(R.string.update_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Cuenta de Drive, connected: the stick's copy (Backup), how games reach it, or disconnecting. */
    private fun showDriveMenu() {
        val games = "Juegos de «Jugando ahora»: " + if (DriveSync.autoRoms(this)) "se descargan solos" else "los eliges tú"
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(driveAuth.email ?: getString(R.string.set_account))
            .setItems(arrayOf("Nombre de este stick: ${StickStatus.stickName(this)}", "Copia del stick…", games, "Desconectar Drive")) { _, which ->
                when (which) {
                    0 -> showStickName()
                    1 -> showBackup()
                    2 -> confirmAutoRoms()
                    else -> confirmDisconnect()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .showForPad()
    }

    /** How the webapp's Stick tab calls this stick, when there are several on the account (StickStatus.stickName). */
    private fun showStickName() {
        val custom = StickStatus.customName(this)
        val field = android.widget.EditText(this).apply {
            setText(custom.orEmpty())
            hint = StickStatus.androidName(this@MainActivity)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
            setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.text_3))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        val save = { name: String? ->
            StickStatus.setCustomName(this, name)
            Toast.makeText(this, "Este stick se llama «${StickStatus.stickName(this)}»", Toast.LENGTH_SHORT).show()
            io.execute { runCatching { StickStatus.writeStatusJson(this) } }
            DriveSync.sync(this)
        }
        val dialog = AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Nombre de este stick")
            .setMessage("Así aparece en la pestaña Stick de la webapp, para distinguirlo de tus otros sticks.")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ -> save(field.text.toString()) }
            .apply { if (custom != null) setNeutralButton("Quitar") { _, _ -> save(null) } }
            .setNegativeButton(R.string.cancel, null)
            .showForPad()
        field.setOnEditorActionListener { _, _, _ -> save(field.text.toString()); dialog.dismiss(); true }
        field.requestFocus()
        field.setSelection(field.text.length)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    /** DriveSync.autoRoms: download everything in JugandoAhora (one stick) or pick (another stick on the account). */
    private fun confirmAutoRoms() {
        val auto = DriveSync.autoRoms(this)
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Juegos de «Jugando ahora»")
            .setMessage(if (auto)
                "Ahora este stick descarga todo lo que hay en «Jugando ahora» de tu Drive.\n\n" +
                    "Si lo usas junto a otro stick con la misma cuenta, puedes elegir tú qué juegos bajar: los demás " +
                    "aparecen en cada consola marcados «Drive». Los que ya tienes se quedan."
            else
                "Este stick solo tiene los juegos que eliges (los demás de tu Drive aparecen marcados «Drive»).\n\n" +
                    "Si lo cambias, descargará todo lo que hay en «Jugando ahora», como el stick principal.")
            .setPositiveButton(if (auto) "Elegir yo" else "Descargar todo") { _, _ ->
                DriveSync.setAutoRoms(this, !auto)
                DriveCollection.refresh(this, force = true)
                DriveSync.sync(this)
            }
            .setNegativeButton(R.string.cancel, null)
            .showForPad()
    }

    /** Which stick made a copy, for the list: "este stick", its name, or nothing for copies before 1.25.8. */
    private fun backupStick(copy: Backup.Copy) = when {
        copy.stick == null -> ""
        copy.stick == StickStatus.stickId(this) -> "este stick  ·  "
        else -> (copy.stickName ?: "otro stick") + "  ·  "
    }

    private val backupDate get() = SimpleDateFormat("d MMM yyyy, HH:mm", Locale("es", "ES"))

    /** Copia del stick: when the last one was made, what it keeps, make one now or bring one back. */
    private fun showBackup() {
        val last = Backup.lastAt(this)
        val text = (if (last > 0) "Última copia: ${backupDate.format(Date(last))}." else "Aún no hay ninguna copia de este stick.") +
            "\n\nGuarda en tu Drive (Retro/Copias) todo lo que no son juegos ni partidas: las pestañas y la vista del inicio, " +
            "favoritos, sonidos, botones de cada consola, ajustes de RetroArch y de cada núcleo, y los mandos. " +
            "Se hace sola una vez a la semana y se guardan las 4 últimas.\n\n" +
            "Las partidas, los juegos terminados, la cuenta de RetroAchievements y las BIOS ya van a Drive aparte."
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Copia del stick")
            .setMessage(text)
            .setPositiveButton("Hacer copia ahora") { _, _ ->
                toast(R.string.syncing)
                DriveSync.runExclusive {
                    val r = runCatching { Backup.makeNow(this) }
                    main.post {
                        Toast.makeText(this, r.exceptionOrNull()?.let { "No se pudo hacer la copia: ${it.message}" } ?: "Copia del stick guardada en Drive", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNeutralButton("Restaurar…") { _, _ -> chooseBackup() }
            .setNegativeButton("Cerrar", null)
            .showForPad()
    }

    /** The copies in Drive, newest first: one brings everything back. */
    private fun chooseBackup() {
        io.execute {
            val copies = runCatching { Backup.list(this) }.getOrElse { emptyList() }
            main.post {
                if (copies.isEmpty()) { Toast.makeText(this, "No hay copias del stick en Drive", Toast.LENGTH_LONG).show(); return@post }
                AlertDialog.Builder(this, R.style.Dialog_RetroStick)
                    .setTitle("Restaurar una copia")
                    .setItems(copies.map { backupDate.format(Date(it.at)) + "  ·  " + backupStick(it) + Formatter.formatShortFileSize(this, it.size) }.toTypedArray()) { _, which ->
                        confirmRestore(copies[which])
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .showForPad()
            }
        }
    }

    private fun confirmRestore(copy: Backup.Copy, newStick: Boolean = false) {
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(if (newStick) "¿Restaurar la configuración de tu RetroStick?" else "Restaurar la copia")
            .setMessage("Copia del ${backupDate.format(Date(copy.at))}${copy.stickName?.let { " (de $it)" } ?: ""}. Vuelven las pestañas, favoritos, sonidos, botones y ajustes de " +
                "RetroArch que tenía el stick entonces. Los juegos y las partidas no se tocan.")
            .setPositiveButton("Restaurar") { _, _ ->
                DriveSync.runExclusive {
                    val r = runCatching { Backup.restore(this, copy) }
                    main.post {
                        Toast.makeText(this, r.exceptionOrNull()?.let { "No se pudo restaurar: ${it.message}" } ?: "Configuración restaurada", Toast.LENGTH_LONG).show()
                        if (r.isSuccess) recreate()
                    }
                }
            }
            .setNegativeButton(if (newStick) "Ahora no" else getString(R.string.cancel)) { _, _ -> if (newStick) Backup.answered(this) }
            .showForPad()
    }

    /** Once, after the first sync of a new or reset stick: a copy in Drive is offered; then the weekly copies start. */
    private var backupChecked = false
    private fun checkBackups() {
        if (backupChecked || !driveAuth.isConnected) return
        backupChecked = true
        io.execute {
            val offer = Backup.shouldOffer(this)
            main.post {
                if (offer != null && !isDestroyed) confirmRestore(offer, newStick = true)
                else Backup.maybeWeekly(this)
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

    /** The game from its title screen; from Detalles, where it was left or the quick save. */
    private fun play(resume: Resume = Resume.TITLE) {
        currentDrive?.let { return openDriveGame(it) }
        val game = currentGame ?: return openShop()
        if (launching) return
        launching = true
        keySound = Sounds.Sound.LAUNCH
        // Its saves from Drive first (played on another stick a moment ago)
        DriveSync.launchWithSaves(this, game, resume) { ok ->
            launching = false
            if (!ok) {
                Sounds.play(Sounds.Sound.ERROR)
                toast(R.string.retroarch_missing)
            }
        }
    }

    /** A game is being opened (its saves first): a second press waits. */
    private var launching = false

    /** The ficha of a game in the Drive collection, with "Pasar al stick" (ShopActivity in Drive mode). */
    private fun openDriveGame(item: DriveCollection.Item) {
        keySound = Sounds.Sound.OPEN
        open(Intent(this, ShopActivity::class.java)
            .putExtra(ShopActivity.EXTRA_CONSOLE, item.console.id)
            .putExtra(ShopActivity.EXTRA_DRIVE_FILE, item.name))
    }

    /** RetroShop on the console of the current tab (the "Añade juegos" card). */
    private fun openShop() {
        if (settingsTab || recentTab) return
        keySound = Sounds.Sound.OPEN
        open(Intent(this, ShopActivity::class.java).putExtra(ShopActivity.EXTRA_CONSOLE, tabConsole.id))
    }

    private fun toggleFavorite() {
        val game = currentGame ?: return
        keySound = Sounds.Sound.OPEN
        library.setFavorite(game, !game.favorite)
        toast(if (game.favorite) R.string.favorite_added else R.string.favorite_removed)
        reload()
    }

    /** Detalles, full screen (DetailsActivity): continue, progress, saves, finished, manual, delete. */
    private fun showDetails() {
        val game = currentGame ?: return
        keySound = Sounds.Sound.OPEN
        open(Intent(this, DetailsActivity::class.java)
            .putExtra(DetailsActivity.EXTRA_CONSOLE, game.console.id)
            .putExtra(DetailsActivity.EXTRA_FILE, game.file.path))
    }

    private fun render() {
        renderGear()
        renderTabs()
        renderContent()
        renderShelf()
        renderSettings()
        renderList()
        renderBackdrop()
        renderHints()
        renderPlayGlyph()
    }

    // ---- List view, search, manuals ----

    private fun trophiesLabel() = RetroAchievements.account(this)?.let { "RetroAchievements · ${it.user}" } ?: getString(R.string.trophies_off)

    // ---- RetroAchievements account (Mis logros card) ----

    /** "Activadas · 3 de 6 BIOS" */
    private fun bootLabel(): String {
        val have = Bios.states().count { it.second == Bios.State.OK }
        return (if (Bios.enabled(this)) "Activadas" else "Desactivadas") + " · $have de ${Bios.ALL.size} BIOS"
    }

    /** Which BIOS are on the stick (each console's boot screen needs its own) and the on/off switch. */
    private fun showBootScreens() {
        val on = Bios.enabled(this)
        val text = buildString {
            appendLine("Antes de cada juego se ve la pantalla de inicio de su consola (el logo de Nintendo en Game Boy, el de Sony en PlayStation…). Cada una necesita la BIOS de tu propia consola: súbela desde la app del móvil, en Stick → Pantallas de inicio.")
            appendLine()
            Bios.states().forEach { (e, s) ->
                appendLine(when (s) {
                    Bios.State.OK -> "✓  ${e.console}"
                    Bios.State.UNKNOWN -> "✗  ${e.console}: versión no reconocida, no se usa (sube otra copia)"
                    Bios.State.MISSING -> "—  ${e.console}: falta"
                })
            }
            appendLine()
            append("NES y Super Nintendo no tenían pantalla de inicio.")
        }
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle(getString(R.string.set_boot))
            .setMessage(text)
            .setPositiveButton(if (on) "Desactivar" else "Activar") { _, _ ->
                Bios.setEnabled(this, !on)
                settings[BOOT_SETTING].subtitle = bootLabel()
                render()
            }
            .setNegativeButton("Cerrar", null)
            .showForPad()
    }

    private fun toggleView() {
        listMode = !listMode
        mediaFocus = -1
        if (::homeList.isInitialized) homeList.leaveMedia()
        if (settings.size > VIEW_SETTING) settings[VIEW_SETTING].subtitle = homeLabel()
        keySound = Sounds.Sound.TAB
    }

    private fun listEntries(): List<HomeList.Entry> {
        val games = consoleGames
        val playing = games.filter { it.lastPlayed > 0 }.maxByOrNull { it.lastPlayed }
        val out = mutableListOf<HomeList.Entry>()
        games.forEach { out += HomeList.Entry.Stick(it, it == playing) }
        if (driveItems.isNotEmpty()) {
            out += HomeList.Entry.Header("En tu Drive · no están en el stick")
            driveItems.forEach { out += HomeList.Entry.Drive(it, it.probe()) }
        }
        if (!recentTab) out += HomeList.Entry.Add(tabConsole)
        return out
    }

    private fun listIndex(entries: List<HomeList.Entry>): Int {
        val n = consoleGames.size
        return when {
            driveRow -> (n + 1 + driveSel[tab]).coerceAtMost(entries.size - 1)
            selected[tab] < n -> selected[tab]
            else -> entries.size - 1
        }
    }

    private fun setListIndex(entries: List<HomeList.Entry>, i: Int) {
        when (val e = entries[i]) {
            is HomeList.Entry.Stick -> { inDrive[tab] = false; selected[tab] = i }
            is HomeList.Entry.Drive -> { inDrive[tab] = true; driveSel[tab] = driveItems.indexOf(e.item).coerceAtLeast(0) }
            is HomeList.Entry.Add -> { inDrive[tab] = false; selected[tab] = consoleGames.size }
            else -> Unit
        }
    }

    /** ▲ ▼ through the list (headers skipped), ▶ into the trailer / screenshots, ✕ on one opens it. */
    private fun handleListKey(code: Int): Boolean {
        val entries = listEntries()
        val idx = listIndex(entries)
        val media = homeList.mediaFocus >= 0
        fun step(d: Int): Int? {
            var i = idx + d
            while (i in entries.indices && entries[i] is HomeList.Entry.Header) i += d
            return i.takeIf { it in entries.indices }
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (!media) step(-1)?.let { setListIndex(entries, it) } ?: run { zone = Zone.TABS }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (!media) step(1)?.let { setListIndex(entries, it) }; return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { homeList.moveMedia(1); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (media) homeList.moveMedia(-1); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A ->
                if (media) { openMedia(homeList.mediaFocus); return true }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> if (media) { homeList.leaveMedia(); return true }
        }
        return false
    }

    /** The trailer / a screenshot of the selected game, full screen (RetroShop's ficha opens straight on it). */
    private fun openMedia(index: Int) {
        val intent = Intent(this, ShopActivity::class.java)
            .putExtra(ShopActivity.EXTRA_CONSOLE, (currentGame?.console ?: currentDrive?.console ?: tabConsole).id)
            .putExtra(ShopActivity.EXTRA_MEDIA, index)
        val game = currentGame
        val drive = currentDrive
        when {
            game != null -> intent.putExtra(ShopActivity.EXTRA_STICK_FILE, game.file.name)
            drive != null -> intent.putExtra(ShopActivity.EXTRA_DRIVE_FILE, drive.name)
            else -> return
        }
        keySound = Sounds.Sound.OPEN
        open(intent)
    }

    private fun renderList() {
        val active = listMode && !settingsTab && hasStorage
        homeList.show(active)
        shelfClip.visibility = if (active) View.GONE else View.VISIBLE
        // The covers view's "Manual" button
        val g = currentGame ?: currentDrive?.let { item -> driveInfo.getOrPut(item.key) { item.probe().also { library.describe(it) } } }
        val manual = g?.let { Manuals.find(this, it.console, it.baseName) }
        manualPill.visibility = if (manual != null && !active) View.VISIBLE else View.GONE
        manualPill.text = PillSpan.text(manualGlyph(), "Manual", getColor(R.color.text), resources.displayMetrics.density,
            if (manualGlyph() == "Share") getDrawable(R.drawable.ic_share) else null)
        if (!active) return
        gamePanel.visibility = View.GONE
        emptyText.visibility = View.GONE
        val entries = listEntries()
        val idx = listIndex(entries)
        val console = tabConsole
        val sheet = when (val e = entries[idx]) {
            is HomeList.Entry.Stick -> sheetFor(e.game, inDrive = false)
            is HomeList.Entry.Drive -> sheetFor(driveInfo.getOrPut(e.item.key) { e.probe.also { library.describe(it) } }, inDrive = true)
            else -> null
        }
        val n = consoleGames.size
        val title = if (recentTab) getString(R.string.shelf_title_recent) else "${console.name} · en el stick · $n ${if (n == 1) "juego" else "juegos"}"
        homeList.bind(title, entries, idx, zone == Zone.CONTENT, sheet)
    }

    private fun sheetFor(game: Game, inDrive: Boolean): HomeList.Sheet {
        val backedUp = game.hasProgress && driveAuth.isConnected && DriveSync.isBackedUp(this, game)
        val save = getString(when {
            !game.hasProgress -> R.string.no_save
            backedUp -> R.string.save_in_drive
            driveAuth.isConnected -> R.string.save_pending
            else -> R.string.save_on_stick
        })
        val date = SimpleDateFormat("d MMM yyyy", Locale("es", "ES"))
        return HomeList.Sheet(
            title = game.title, consoleId = game.console.id, consoleName = game.console.name, coverGame = game,
            last = formatWhen(game.lastPlayed), time = formatDuration(game.runtimeSeconds), save = save, saveOk = backedUp,
            achievements = achievementsText(game, onStick = !inDrive),
            playLabel = if (inDrive) "Ver ficha y pasar al stick" else getString(R.string.play),
            languages = Languages.of(game.file.name),
            finished = game.finished?.let { "Terminado el ${date.format(it.at)} · ${formatDuration(it.seconds)}" },
            manual = Manuals.find(this, game.console, game.baseName) != null, manualGlyph = manualGlyph(), inDrive = inDrive,
            playCross = padStyle == PadStyle.PLAYSTATION,
            playLetter = when (padStyle) { PadStyle.XBOX, PadStyle.NINTENDO -> "A"; PadStyle.REMOTE -> "OK"; else -> "" },
        )
    }

    /**
     * "Logros" column of the ficha (RetroAchievements, only games on the stick: RetroArch earns them):
     * "earned/total", "…" while asking, "—" when it has none; null = no column (no account, or in Drive).
     */
    private fun achievementsText(game: Game, onStick: Boolean): String? {
        if (!onStick || !RetroAchievements.supports(game.console) || RetroAchievements.account(this) == null) return null
        RetroAchievements.request(this, game)
        val p = RetroAchievements.cached(game)
        return when {
            p != null && !p.supported -> "No válida"
            p != null -> "${p.earned}/${p.total}"
            RetroAchievements.known(game) -> "—"
            else -> "…"
        }
    }

    /** The button that opens the manual, as this controller labels it. */
    private fun manualGlyph() = when (padStyle) {
        PadStyle.XBOX -> "View"
        PadStyle.NINTENDO -> "−"
        PadStyle.REMOTE -> ""
        // The DualSense's Create button, the old "Share" one: "Create" read like "crear"
        else -> "Share"
    }

    private fun currentManual(): String? {
        val game = currentGame ?: currentDrive?.probe() ?: return null
        return Manuals.find(this, game.console, game.baseName)
    }

    /** The selected game's achievements (only games on the stick: RetroArch earns them). */
    private fun openAchievements() {
        val game = currentGame
        if (settingsTab || game == null || !RetroAchievements.supports(game.console)) return
        if (RetroAchievements.account(this) == null) {
            keySound = Sounds.Sound.ERROR
            Toast.makeText(this, "RetroAchievements no está activado", Toast.LENGTH_SHORT).show()
            return
        }
        Sounds.play(Sounds.Sound.OPEN)
        open(Intent(this, AchievementsActivity::class.java)
            .putExtra(AchievementsActivity.EXTRA_CONSOLE, game.console.id)
            .putExtra(AchievementsActivity.EXTRA_FILE, game.file.path))
    }

    private fun openManual() {
        val game = currentGame ?: currentDrive?.probe() ?: return
        val name = Manuals.find(this, game.console, game.baseName)
        if (name == null) {
            keySound = Sounds.Sound.ERROR
            Toast.makeText(this, "No hay manual de ${game.title}", Toast.LENGTH_SHORT).show()
            return
        }
        keySound = Sounds.Sound.OPEN
        open(Intent(this, ManualActivity::class.java)
            .putExtra(ManualActivity.EXTRA_CONSOLE, game.console.id)
            .putExtra(ManualActivity.EXTRA_FILE, name)
            .putExtra(ManualActivity.EXTRA_TITLE, game.title))
    }

    private fun searchCandidates(): List<HomeSearch.Hit> {
        val out = mutableListOf<HomeSearch.Hit>()
        // «Seguir jugando» searches every console shown; a console tab, only its own
        homeTabs.filter { !settingsTab && it != RECENT && (recentTab || it == tabConsole) }.forEach { c ->
            games[c.id].orEmpty().forEach { g -> out += HomeSearch.Hit(g.title, c, g, null, g) }
            DriveCollection.items(this, c).forEach { d -> out += HomeSearch.Hit(d.title, c, null, d, d.probe()) }
        }
        return out
    }

    /** A search result: its tab, selected (on the stick or in the Drive row / section). */
    private fun pickSearch(hit: HomeSearch.Hit) {
        val i = homeTabs.indexOf(hit.console)
        if (i < 0) return
        tab = i
        zone = Zone.CONTENT
        buildShelf()
        if (hit.game != null) {
            inDrive[i] = false
            selected[i] = games[hit.console.id].orEmpty().indexOfFirst { it.key == hit.game.key }.coerceAtLeast(0)
        } else if (hit.drive != null) {
            inDrive[i] = true
            driveSel[i] = driveItems.indexOfFirst { it.key == hit.drive.key }.coerceAtLeast(0)
        }
        render()
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
