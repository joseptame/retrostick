package com.retrostick.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

/**
 * Retro Ajustes → Inicio, full screen: on the left how the games look (Carátulas / Lista) and whether RetroStick is
 * the stick's home screen; on the right the tabs, one tile each in their order: [X] shows or hides one, [TRI] picks it
 * up to move it with the D-pad ([X] / [TRI] drops it, [O] puts it back). Everything is saved at once (HomeTabs and
 * the "home" preferences); the home screen applies it when it comes back.
 * Design: https://claude.ai/artifact/EhFhiHHwwGfyq7AXRmJ6Kp (A · Mosaico de consolas). Sizes in dp for 960×540 dp.
 */
class HomeSetupActivity : Activity() {
    private val homePrefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }

    /** «Seguir jugando», then every console in the chosen order */
    private var order = mutableListOf<Console>()
    private var hidden = mutableSetOf<String>()
    private var recentOn = true
    private var counts: Map<String, Int> = emptyMap()

    /** Focus: left column (0 Carátulas, 1 Lista, 2 pantalla de inicio) or the tiles (0 = Seguir jugando) */
    private var inGrid = true
    private var left = 0
    private var cell = 1
    /** A console being moved: where it started (to put it back), or -1 */
    private var movingFrom = -1

    private lateinit var grid: LinearLayout
    private lateinit var tabsHeader: TextView
    private lateinit var viewCards: List<LinearLayout>
    private lateinit var homeRow: LinearLayout
    private lateinit var homeSub: TextView
    private lateinit var homeSwitch: FrameLayout
    private lateinit var hints: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        order = HomeTabs.ordered(this).toMutableList()
        hidden = HomeTabs.hidden(this).toMutableSet()
        recentOn = HomeTabs.recentTab(this)
        build()
        render()
        Thread {
            val scanned = runCatching { Library(this).scan() }.getOrNull().orEmpty()
            runOnUiThread { if (!isDestroyed) { counts = scanned.mapValues { it.value.size }; render() } }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's home-app window: not chosen, so RetroStick stops being offered as home again
        if (choosingHome) { choosingHome = false; HomeApp.settle(this) }
        if (::homeRow.isInitialized) render()
    }

    private var choosingHome = false

    private val tiles get() = listOf<Console?>(null) + order

    // ---- Layout ----

    private fun build() {
        val crumbs = text(11f, R.color.text_2).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("Retro"); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                val s = length; append("Stick")
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), s, length, 0)
                append("   /   Retro Ajustes   /   Inicio")
            }
        }

        viewCards = listOf(viewCard("Carátulas", covers = true), viewCard("Lista", covers = false))
        homeSub = text(9f, R.color.text_2).apply { setLineSpacing(0f, 1.25f) }
        homeSwitch = FrameLayout(this).apply { addView(View(this@HomeSetupActivity), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER_VERTICAL)) }
        homeRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(11), dp(12), dp(11))
            addView(LinearLayout(this@HomeSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(12f, R.color.text).apply { text = "Usar RetroStick como pantalla de inicio"; typeface = font(R.font.inter_bold) })
                addView(homeSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(11) })
            addView(homeSwitch, LinearLayout.LayoutParams(dp(38), dp(20)))
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(26f, R.color.text).apply { text = "Inicio"; typeface = font(R.font.inter_extrabold) })
            addView(text(10.5f, R.color.text_2).apply {
                text = "Qué consolas salen como pestañas y en qué orden, cómo se ven tus juegos y con qué arranca el stick. Los cambios se guardan solos."
                setLineSpacing(0f, 1.3f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            addView(section("Vista de los juegos"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(17) })
            addView(LinearLayout(this@HomeSetupActivity).apply {
                viewCards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) marginEnd = dp(9) }) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
            addView(section("Arranque"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(17) })
            addView(homeRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }

        tabsHeader = section("")
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(this@HomeSetupActivity).apply {
                gravity = Gravity.BOTTOM
                addView(tabsHeader, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(8.5f, R.color.text_3).apply { text = "En el orden en que salen · las apagadas no salen" })
            })
            addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
        }

        hints = text(9.5f, R.color.chip_text)
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(Color.parseColor("#8C0A081A"))
            addView(hints)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@HomeSetupActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), 0)
                addView(crumbs)
                addView(LinearLayout(this@HomeSetupActivity).apply {
                    addView(leftCol, LinearLayout.LayoutParams(dp(300), -2).apply { marginEnd = dp(28) })
                    addView(rightCol, LinearLayout.LayoutParams(0, -2, 1f))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
            addView(View(this@HomeSetupActivity).apply { setBackgroundColor(Color.parseColor("#12FFFFFF")) },
                FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        })
    }

    /** A «Carátulas» / «Lista» card: a little drawing of the home screen in that view, and its name. */
    private fun viewCard(name: String, covers: Boolean) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        val preview = LinearLayout(this@HomeSetupActivity).apply {
            background = round(Color.parseColor("#1D1850"), 5)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            if (covers) {
                gravity = Gravity.BOTTOM
                listOf("#E2483D", "#3C7BE0", "#F0B429", "#41B06E").forEach { c ->
                    addView(View(this@HomeSetupActivity).apply { background = round(Color.parseColor(c), 3) },
                        LinearLayout.LayoutParams(dp(26), dp(37)).apply { marginEnd = dp(5) })
                }
            } else {
                orientation = LinearLayout.VERTICAL
                listOf(0.8f to "#38FFFFFF", 0.7f to "#1FFFFFFF", 0.76f to "#1FFFFFFF").forEach { (w, c) ->
                    addView(LinearLayout(this@HomeSetupActivity).apply {
                        addView(View(this@HomeSetupActivity).apply { background = round(Color.parseColor(c), 2) }, LinearLayout.LayoutParams(0, -1, w))
                        addView(View(this@HomeSetupActivity), LinearLayout.LayoutParams(0, -1, 1 - w))
                    }, LinearLayout.LayoutParams(-1, dp(9)).apply { bottomMargin = dp(5) })
                }
            }
        }
        addView(preview, LinearLayout.LayoutParams(-1, dp(56)))
        addView(LinearLayout(this@HomeSetupActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(12f, R.color.text).apply { text = name; typeface = font(R.font.inter_bold); tag = "name" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(this@HomeSetupActivity).apply { tag = "radio" }, LinearLayout.LayoutParams(dp(14), dp(14)))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(7) })
    }

    private fun tile(i: Int, c: Console?): View {
        val shown = if (c == null) recentOn else c.id !in hidden
        val focused = inGrid && cell == i
        val moving = movingFrom >= 0 && focused
        val dark = moving
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        // Top: the console's logo (Seguir jugando: its icon) and whether it shows
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START }
        if (c == null) {
            logo.setImageResource(R.drawable.ic_resume)
            logo.setColorFilter(getColor(R.color.brand_accent))
        } else when {
            dark -> { logo.setImageResource(c.logo); if (c.whiteWhenSelected) logo.setColorFilter(Color.parseColor("#1B1446")) }
            c.logoWhite != null -> logo.setImageResource(c.logoWhite)
            c.colourOnCard -> logo.setImageResource(c.logo)
            else -> { logo.setImageResource(c.logo); logo.setColorFilter(Color.WHITE) }
        }
        if (!shown && !dark) logo.alpha = 0.55f
        top.addView(logo, LinearLayout.LayoutParams(0, dp(16), 1f).apply { marginEnd = dp(6) })
        if (moving) {
            top.addView(text(7.5f, R.color.text).apply {
                text = "◀  Moviendo  ▶"; typeface = font(R.font.inter_bold); setTextColor(Color.WHITE)
                setPadding(dp(6), dp(2), dp(6), dp(2)); background = round(Color.parseColor("#1B1446"), 7)
            })
        } else {
            top.addView(ImageView(this).apply {
                if (shown) {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.brand_accent)) }
                    setImageDrawable(getDrawable(R.drawable.ic_shop_check)?.mutate()?.apply { setTint(getColor(R.color.button_text)) })
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                } else background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#4DFFFFFF")) }
            }, LinearLayout.LayoutParams(dp(15), dp(15)))
        }
        v.addView(top, LinearLayout.LayoutParams(-1, -2))
        v.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        val name = text(12f, R.color.text).apply {
            text = c?.name ?: "Seguir jugando"; typeface = font(R.font.inter_bold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            if (dark) setTextColor(getColor(R.color.button_text)) else if (!shown) setTextColor(getColor(R.color.text_2))
        }
        val n = c?.let { counts[it.id] }
        val sub = text(8.5f, if (shown) R.color.text_2 else R.color.text_3).apply {
            maxLines = 1
            text = when {
                moving -> "Pestaña ${visiblePosition(c!!)}"
                c == null -> if (shown) "Tus últimos juegos" else "Oculta"
                n == null -> if (shown) "" else "Oculta"
                else -> (if (shown) "" else "Oculta · ") + (if (n == 1) "1 juego" else "$n juegos")
            }
            if (dark) setTextColor(Color.parseColor("#4A4372"))
        }
        v.addView(name)
        v.addView(sub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        v.background = when {
            dark -> round(Color.WHITE, 9)
            focused -> round(Color.parseColor("#1FFFFFFF"), 9).apply { setStroke(dp(2), Color.WHITE) }
            c == null && shown -> round(Color.parseColor("#299D8CFF"), 9).apply { setStroke(1, Color.parseColor("#599D8CFF")) }
            shown -> round(Color.parseColor("#0FFFFFFF"), 9).apply { setStroke(1, Color.parseColor("#1AFFFFFF")) }
            else -> round(Color.parseColor("#05FFFFFF"), 9).apply { setStroke(1, Color.parseColor("#24FFFFFF"), dp(4).toFloat(), dp(3).toFloat()) }
        }
        if (moving) { v.rotation = -1.5f; v.translationY = -dp(4).toFloat() }
        v.elevation = if (focused) dp(if (moving) 12 else 6).toFloat() else 0f
        return v
    }

    /** Where [c] would be among the tabs that show: «Pestaña 3 de 9». */
    private fun visiblePosition(c: Console): String {
        val shownTabs = listOfNotNull(RECENT.takeIf { recentOn }) + order.filter { it.id !in hidden || it == c }
        return "${shownTabs.indexOf(c) + 1} de ${shownTabs.size}"
    }

    // ---- State ----

    private fun render() {
        val shownCount = order.count { it.id !in hidden } + (if (recentOn) 1 else 0)
        tabsHeader.text = "PESTAÑAS · $shownCount DE ${order.size + 1}"
        grid.removeAllViews()
        tiles.chunked(COLUMNS).forEachIndexed { r, row ->
            grid.addView(LinearLayout(this).apply {
                row.forEachIndexed { k, c ->
                    val i = r * COLUMNS + k
                    addView(tile(i, c), LinearLayout.LayoutParams(0, -1, 1f).apply { if (k < COLUMNS - 1) marginEnd = dp(8) })
                }
                repeat(COLUMNS - row.size) { k -> addView(View(this@HomeSetupActivity), LinearLayout.LayoutParams(0, -1, 1f).apply { if (row.size + k < COLUMNS - 1) marginEnd = dp(8) }) }
            }, LinearLayout.LayoutParams(-1, dp(72)).apply { if (r > 0) topMargin = dp(8) })
        }

        val list = homePrefs.getBoolean("list", false)
        viewCards.forEachIndexed { i, card ->
            val chosen = (i == 1) == list
            val focused = !inGrid && left == i
            card.background = round(Color.parseColor(if (focused || chosen) "#1AFFFFFF" else "#0DFFFFFF"), 9).apply {
                if (focused) setStroke(dp(2), Color.WHITE) else setStroke(1, Color.parseColor(if (chosen) "#669D8CFF" else "#14FFFFFF"))
            }
            card.elevation = if (focused) dp(6).toFloat() else 0f
            card.findViewWithTag<TextView>("name").setTextColor(getColor(if (chosen) R.color.text else R.color.text_2))
            card.findViewWithTag<ImageView>("radio").apply {
                if (chosen) {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(getColor(R.color.brand_accent)) }
                    setImageDrawable(getDrawable(R.drawable.ic_shop_check)?.mutate()?.apply { setTint(getColor(R.color.button_text)) })
                    setPadding(dp(2), dp(2), dp(2), dp(2))
                } else {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(dp(1), Color.parseColor("#4DFFFFFF")) }
                    setImageDrawable(null)
                }
            }
        }

        val home = isHome()
        homeSub.text = "El stick arranca aquí y Home durante un juego te devuelve a él. Ahora: " + (if (home) "RetroStick." else "${otherLauncherName()}.")
        val focusedHome = !inGrid && left == 2
        homeRow.background = round(Color.parseColor(if (focusedHome) "#1AFFFFFF" else "#0DFFFFFF"), 9).apply {
            if (focusedHome) setStroke(dp(2), Color.WHITE) else setStroke(1, Color.parseColor("#14FFFFFF"))
        }
        homeRow.elevation = if (focusedHome) dp(6).toFloat() else 0f
        homeSwitch.background = round(if (home) getColor(R.color.brand_accent) else Color.parseColor("#29FFFFFF"), 10)
        homeSwitch.getChildAt(0).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (home) Color.WHITE else getColor(R.color.text_2)) }
            layoutParams = (layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or if (home) Gravity.END else Gravity.START
                marginStart = dp(2); marginEnd = dp(2)
            }
        }

        val template = when {
            movingFrom >= 0 -> "◀ ▶ ▲ ▼  Llevar      [X]  Soltar      [O]  Cancelar"
            inGrid && cell > 0 -> "[X]  Mostrar / ocultar      [TRI]  Cambiar de sitio      ◀ ▶ ▲ ▼  Moverse      [O]  Volver"
            inGrid -> "[X]  Mostrar / ocultar      ◀ ▶ ▲ ▼  Moverse      [O]  Volver"
            else -> "[X]  Elegir      ◀ ▶ ▲ ▼  Moverse      [O]  Volver"
        }
        hints.text = PadHints.build(this, template, hintsStyle, hints.currentTextColor, (hints.textSize * 1.25f).toInt())
    }

    // ---- Keys ----

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        hintsStyle = StickStatus.padStyle(event.device)
        val key = StickStatus.standardKey(event.device, event.keyCode)
        if (movingFrom >= 0) {
            when (key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveTo(cell - 1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveTo(cell + 1)
                KeyEvent.KEYCODE_DPAD_UP -> moveTo(cell - COLUMNS)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveTo(cell + COLUMNS)
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_Y -> drop()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> cancelMove()
                else -> return super.dispatchKeyEvent(event)
            }
            return true
        }
        when (key) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (inGrid) {
                if (cell % COLUMNS > 0) focusCell(cell - 1) else { inGrid = false; left = if (cell / COLUMNS == 0) 1 else 2; moved() }
            } else if (left == 1) { left = 0; moved() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (inGrid) {
                if (cell % COLUMNS < COLUMNS - 1 && cell + 1 < tiles.size) focusCell(cell + 1)
            } else if (left == 0) { left = 1; moved() } else { inGrid = true; cell = if (left == 1) 0 else COLUMNS; moved() }
            KeyEvent.KEYCODE_DPAD_UP -> if (inGrid) { if (cell >= COLUMNS) focusCell(cell - COLUMNS) } else if (left == 2) { left = 0; moved() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (inGrid) { if (cell + COLUMNS < tiles.size) focusCell(cell + COLUMNS) else if (cell / COLUMNS < (tiles.size - 1) / COLUMNS) focusCell(tiles.size - 1) }
                else if (left < 2) { left = 2; moved() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> select()
            KeyEvent.KEYCODE_BUTTON_Y -> if (inGrid && cell > 0) { movingFrom = cell; Sounds.play(Sounds.Sound.OPEN); render() }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun moved() { Sounds.play(Sounds.Sound.MOVE); render() }

    private fun focusCell(i: Int) { cell = i; moved() }

    private fun select() {
        if (!inGrid) when (left) {
            0, 1 -> {
                homePrefs.edit().putBoolean("list", left == 1).apply()
                Sounds.play(Sounds.Sound.OPEN); render()
            }
            else -> toggleHome()
        } else {
            val c = tiles[cell]
            if (c == null) {
                recentOn = !recentOn
                HomeTabs.setRecentTab(this, recentOn)
            } else {
                val showing = c.id !in hidden
                if (showing && order.count { it.id !in hidden } == 1) {
                    Toast.makeText(this, "Deja al menos una consola", Toast.LENGTH_SHORT).show()
                    Sounds.play(Sounds.Sound.ERROR); return
                }
                if (showing) hidden += c.id else hidden -= c.id
                HomeTabs.setHidden(this, hidden)
            }
            Sounds.play(Sounds.Sound.OPEN); render()
        }
    }

    /** The console being moved goes to tile [to] (never before «Seguir jugando»). */
    private fun moveTo(to: Int) {
        if (to < 1 || to >= tiles.size || to == cell) return
        val c = order.removeAt(cell - 1)
        order.add(to - 1, c)
        cell = to
        Sounds.play(Sounds.Sound.MOVE); render()
    }

    private fun drop() {
        movingFrom = -1
        HomeTabs.setOrder(this, order)
        Sounds.play(Sounds.Sound.DONE); render()
    }

    private fun cancelMove() {
        val c = order.removeAt(cell - 1)
        order.add(movingFrom - 1, c)
        cell = movingFrom
        movingFrom = -1
        Sounds.play(Sounds.Sound.BACK); render()
    }

    // ---- Home screen ----

    private fun isHome() = HomeApp.isHome(this)

    private fun otherLauncherName(): String {
        val info = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
            ?: return "otro launcher"
        if (info.packageName == "android") return "Android te pregunta"
        return runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(info.packageName, 0)).toString() }.getOrDefault("otro launcher")
    }

    /**
     * To RetroStick: offered as home (HomeApp) and Android's own window to choose it (Android 10+), or its home-app
     * screen, or its «which launcher?» window. Back to the other launcher: no longer offered, Android goes back to it.
     */
    private fun toggleHome() {
        Sounds.play(Sounds.Sound.OPEN)
        val toRetroStick = !isHome()
        BootReceiver.setEnabled(this, toRetroStick)
        if (!toRetroStick) {
            HomeApp.setOffered(this, false)
            Toast.makeText(this, "La pantalla de inicio vuelve a ser ${otherLauncherName()}", Toast.LENGTH_SHORT).show()
            render()
            return
        }
        HomeApp.setOffered(this, true)
        choosingHome = true
        val role = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            getSystemService(android.app.role.RoleManager::class.java)?.takeIf { it.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) }
                ?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME) else null
        val ok = runCatching { if (role != null) startActivityForResult(role, 0) else startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }.isSuccess ||
            runCatching { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)) }.isSuccess
        if (!ok) { choosingHome = false; HomeApp.settle(this); Toast.makeText(this, R.string.app_missing, Toast.LENGTH_SHORT).show() }
    }

    // ---- Helpers ----

    private var hintsStyle = StickStatus.firstPadStyle()

    private fun section(name: String) = text(8.5f, R.color.text_3).apply { text = name.uppercase(); letterSpacing = 0.14f; typeface = font(R.font.inter_semibold) }
    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font(R.font.inter_regular)
    }
    private fun font(id: Int) = resources.getFont(id)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val COLUMNS = 3
    }
}
