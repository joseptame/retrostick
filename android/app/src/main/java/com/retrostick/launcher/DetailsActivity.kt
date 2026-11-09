package com.retrostick.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.text.format.Formatter
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * A game's Detalles (□ on the home screen), full screen: cover and data on the left; on the right "Jugar" with
 * three cards (where it was left, the quick save — each with the picture RetroArch keeps with it — and from the
 * title screen), the progress, the saves on the stick and the file; at the bottom Lo he terminado, Manual and
 * Borrar. Design: https://claude.ai/artifact/Bp5iCD2BuG7t5YMDFScu2J. Sizes in dp for the 960×540 dp screen.
 */
class DetailsActivity : Activity() {
    private lateinit var game: Game
    private lateinit var covers: CoverLoader
    private val es = Locale("es", "ES")

    private class Card(val view: View, val enabled: Boolean, val resume: Resume)
    private class Action(val view: TextView, val run: () -> Unit)

    private var cards = listOf<Card>()
    private var actions = listOf<Action>()
    /** 0 = the Jugar cards, 1 = the bottom actions */
    private var row = 0
    private var col = 0
    private lateinit var finishedAction: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val console = CONSOLES.firstOrNull { it.id == intent.getStringExtra(EXTRA_CONSOLE) } ?: return finish()
        game = Game(console, File(intent.getStringExtra(EXTRA_FILE) ?: return finish()))
        Library(this).describe(game)
        covers = CoverLoader(this)
        build()
        col = cards.indexOfFirst { it.enabled }.coerceAtLeast(0)
        render()
    }

    // ---- Layout ----

    private fun build() {
        val info = Fichas.cached(this, game.title, game.console.id)?.info
        val ra = RetroAchievements.cached(game)?.takeIf { RetroAchievements.supports(game.console) && RetroAchievements.account(this) != null }

        val crumbs = text(11f, R.color.text_2).apply {
            text = android.text.SpannableStringBuilder().apply {
                append("Retro"); setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                val s = length; append("Stick")
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), s, length, 0)
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), s, length, 0)
                append("   /   ${game.console.name}   /   Detalles")
            }
        }

        // Left: cover, title, chips
        val cover = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#1AFFFFFF"), 11); elevation = dp(8).toFloat() }
        covers.load(game, dp(235)) { b -> cover.setImageBitmap(b ?: logoCover(this, game.console, dp(235))) }
        val title = text(22f, R.color.text).apply { typeface = font(R.font.inter_extrabold); text = game.title; maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.05f) }
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val first = LinearLayout(this@DetailsActivity)
            first.addView(chip(game.console.name, "#2E9D8CFF", R.color.brand_accent))
            game.region?.let { first.addView(chip(it, "#12FFFFFF", R.color.text)) }
            val langs = Languages.of(game.file.name)
            if (langs.contains("Español")) first.addView(chip("Español", "#244ADE80", R.color.ok))
            addView(first)
            val facts = listOfNotNull(info?.year?.toString(), info?.genres?.take(2)?.joinToString(", ")?.takeIf { it.isNotEmpty() })
            if (facts.isNotEmpty()) addView(text(10.5f, R.color.text_2).apply { text = facts.joinToString("  ·  ") }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(cover, LinearLayout.LayoutParams(dp(235), dp(235)))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
            addView(chips, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // Right: Jugar cards
        val auto = lastState(game)
        val quick = quickState(game)
        val cardRow = LinearLayout(this)
        cards = listOf(
            card("Donde lo dejaste", auto?.let { "Se guardó al cerrar · ${formatWhen(it.lastModified())}" } ?: "Aún no hay: se guarda al cerrar el juego",
                auto, auto != null, Resume.LAST, play = true),
            card("Guardado rápido", quick?.let { "${PadHints.quickSave(StickStatus.firstPadStyle())} · ${formatWhen(it.lastModified())}" } ?: "Aún no hay · ${PadHints.quickSave(StickStatus.firstPadStyle())} en partida",
                quick, quick != null, Resume.QUICK),
            card("Desde el título", if (game.hasProgress) "Tu partida del juego se mantiene" else "Empezar a jugar", null, true, Resume.TITLE, titleScreen = true),
        )
        cards.forEachIndexed { i, c -> cardRow.addView(c.view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i < 2) marginEnd = dp(11) }) }

        // Progress tiles
        val tiles = LinearLayout(this)
        val tileList = mutableListOf(
            tile("Tiempo jugado", formatDuration(game.runtimeSeconds)),
            tile("Veces jugado", game.playCount.toString()),
            tile("Última partida", if (game.lastPlayed > 0) formatWhen(game.lastPlayed) else "Nunca"),
        )
        tileList += when {
            ra != null && !ra.supported -> tile("Logros", "No válida", hint = null)
            ra != null -> tile("Logros", "${ra.earned} / ${ra.total}", hint = "R2 ver", progress = if (ra.total > 0) ra.earned * 100 / ra.total else 0)
            game.finished != null -> tile("Estado", "Terminado")
            else -> tile("Estado", if (game.hasProgress) "Empezado" else "Sin empezar")
        }
        tileList.forEachIndexed { i, v -> tiles.addView(v, LinearLayout.LayoutParams(0, -1, 1f).apply { if (i < tileList.size - 1) marginEnd = dp(9) }) }

        // Saves on the stick
        val backedUp = game.hasProgress && DriveAuth(this).isConnected && DriveSync.isBackedUp(this, game)
        val saves = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val srm = game.saveFiles.firstOrNull { it.name == "${game.baseName}.srm" }
        listOfNotNull(
            srm?.let { Triple("Partida del juego", "guardado del cartucho", it) },
            auto?.let { Triple("Donde lo dejaste", "al cerrar", it) },
            quick?.let { Triple("Guardado rápido", PadHints.quickSave(StickStatus.firstPadStyle()), it) },
        ).forEach { (name, what, f) ->
            saves.addView(saveRow(name, what, formatWhen(f.lastModified()), backedUp), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
        if (saves.childCount == 0) saves.addView(text(10.5f, R.color.text_2).apply { text = "Sin partidas guardadas en el stick." })

        val fileLine = text(8.5f, R.color.text_3).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            text = listOfNotNull(
                game.file.name,
                Formatter.formatShortFileSize(this@DetailsActivity, game.file.length()),
                ra?.let { if (it.supported) "Versión válida para logros" else "Versión no válida para logros" },
            ).joinToString("   ·   ")
        }

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("Jugar"))
            addView(cardRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(section("Tu progreso"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(tiles, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(section("Partidas en el stick"), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(16) })
            addView(saves, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(fileLine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
        val body = LinearLayout(this).apply {
            addView(left, LinearLayout.LayoutParams(dp(235), -1).apply { marginEnd = dp(32) })
            addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        }

        // Bottom bar: actions and hints
        finishedAction = actionView(finishedLabel(), R.drawable.ic_trophy)
        val actionList = mutableListOf(Action(finishedAction) { toggleFinished() })
        if (Manuals.find(this, game.console, game.baseName) != null) actionList += Action(actionView("Manual", null)) { openManual() }
        actionList += Action(actionView("Borrar…", R.drawable.ic_trash, danger = true)) { confirmRemove() }
        actions = actionList
        val actionRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; actions.forEach { addView(it.view, LinearLayout.LayoutParams(-2, dp(32)).apply { marginEnd = dp(7) }) } }
        val hints = text(9.5f, R.color.chip_text)
        setHints(hints, listOfNotNull("[X]  Elegir", "◀ ▶ ▲ ▼ Moverse", "[R2]  Logros".takeIf { ra != null && ra.supported }, "[O]  Volver").joinToString("      "))
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(40), 0, dp(40), 0)
            setBackgroundColor(Color.parseColor("#8C0A081A"))
            addView(actionRow, LinearLayout.LayoutParams(0, -2, 1f))
            addView(hints)
        }

        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@DetailsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), 0)
                addView(crumbs)
                addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })
            }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
            addView(View(this@DetailsActivity).apply { setBackgroundColor(Color.parseColor("#12FFFFFF")) },
                FrameLayout.LayoutParams(-1, 1, Gravity.BOTTOM).apply { bottomMargin = dp(48) })
            addView(bar, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        })
    }

    private fun card(name: String, sub: String, state: File?, enabled: Boolean, resume: Resume, play: Boolean = false, titleScreen: Boolean = false): Card {
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.parseColor("#1D1850")) }
        val thumb = state?.let { File(it.path + ".png") }?.takeIf { it.isFile }
        when {
            thumb != null -> image.setImageBitmap(BitmapFactory.decodeFile(thumb.path))
            // From the title screen = a fresh start: the game's cover, at full strength
            titleScreen -> covers.load(game, dp(200)) { b -> image.setImageBitmap(b ?: logoCover(this, game.console, dp(200))) }
            else -> Unit
        }
        // A save without its picture (made before they were kept) or no save: a dark card that says so
        val empty = if (thumb == null && !titleScreen) text(9.5f, R.color.text_3).apply {
            text = if (state != null) "Sin captura\nse hará la próxima vez que se guarde" else "Sin guardado"
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
        } else null
        if (!enabled) image.alpha = 0.35f
        val badge = state?.let {
            text(8f, R.color.text).apply {
                text = formatWhen(it.lastModified()); typeface = font(R.font.inter_bold)
                setPadding(dp(5), dp(2), dp(5), dp(2)); background = round(Color.parseColor("#BF0D0B1F"), 4)
            }
        }
        val top = FrameLayout(this).apply {
            addView(image, FrameLayout.LayoutParams(-1, -1))
            empty?.let { addView(it, FrameLayout.LayoutParams(-1, -1)) }
            badge?.let { addView(it, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(7); rightMargin = dp(7) }) }
        }
        val label = text(12.5f, if (enabled) R.color.text else R.color.text_2).apply {
            text = (if (play && enabled) "▶  " else "") + name; typeface = font(R.font.inter_bold); maxLines = 1
        }
        val subText = text(9f, R.color.text_2).apply { text = sub; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipToOutline = true
            addView(top, LinearLayout.LayoutParams(-1, dp(100)))
            addView(LinearLayout(this@DetailsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(10))
                addView(label)
                addView(subText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            })
        }
        return Card(view, enabled, resume)
    }

    private fun tile(label: String, value: String, hint: String? = null, progress: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(11), dp(9), dp(11), dp(10))
        background = round(Color.parseColor("#0DFFFFFF"), 8)
        addView(LinearLayout(this@DetailsActivity).apply {
            addView(text(8f, R.color.text_3).apply { text = label }, LinearLayout.LayoutParams(0, -2, 1f))
            hint?.let { addView(text(7.5f, R.color.text_3).apply { text = it }) }
        })
        addView(text(15f, R.color.text).apply { text = value; typeface = font(R.font.inter_bold); maxLines = 1 }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        progress?.let { p ->
            val bar = FrameLayout(this@DetailsActivity).apply { background = round(Color.parseColor("#1FFFFFFF"), 2) }
            bar.addView(View(this@DetailsActivity).apply { background = round(getColor(R.color.brand_accent), 2) }, FrameLayout.LayoutParams(0, -1))
            bar.post { bar.getChildAt(0).layoutParams = (bar.getChildAt(0).layoutParams as FrameLayout.LayoutParams).apply { width = maxOf(dp(3), bar.width * p / 100) }; bar.requestLayout() }
            addView(bar, LinearLayout.LayoutParams(-1, dp(3)).apply { topMargin = dp(5) })
        }
    }

    private fun saveRow(name: String, what: String, date: String, backedUp: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = round(Color.parseColor("#0AFFFFFF"), 7)
        addView(text(10.5f, R.color.text).apply {
            text = android.text.SpannableStringBuilder("$name  ").apply {
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, 0)
                val s = length; append("($what)")
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.text_3)), s, length, 0)
            }
            maxLines = 1
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(9.5f, R.color.text_2).apply { text = date }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(14) })
        addView(text(9.5f, if (backedUp) R.color.ok else R.color.text_2).apply {
            text = if (backedUp) "✓ Copia en Drive" else if (DriveAuth(this@DetailsActivity).isConnected) "Pendiente de subir" else "En el stick"
            typeface = font(R.font.inter_semibold); gravity = Gravity.END
        }, LinearLayout.LayoutParams(dp(100), -2))
    }

    private fun section(name: String) = text(8.5f, R.color.text_3).apply { text = name.uppercase(es); letterSpacing = 0.14f; typeface = font(R.font.inter_semibold) }

    private fun chip(label: String, bg: String, color: Int) = text(9f, color).apply {
        text = label; typeface = font(R.font.inter_semibold)
        setPadding(dp(7), dp(3), dp(7), dp(3)); background = round(Color.parseColor(bg), 5)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(5) }
    }

    private fun actionView(label: String, icon: Int?, danger: Boolean = false) = text(10f, if (danger) R.color.danger_text else R.color.text).apply {
        text = label; typeface = font(R.font.inter_semibold); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), 0, dp(12), 0)
        tag = danger
        icon?.let { res ->
            setCompoundDrawablesRelative(getDrawable(res)!!.mutate().apply { setBounds(0, 0, dp(13), dp(13)) }, null, null, null)
            compoundDrawablePadding = dp(6)
        }
    }

    private fun finishedLabel() = if (game.finished == null) "Lo he terminado" else "Quitar de terminados"

    // ---- Focus ----

    private fun render() {
        cards.forEachIndexed { i, c ->
            val sel = row == 0 && i == col
            c.view.background = round(Color.parseColor(if (sel) "#1AFFFFFF" else if (c.enabled) "#0FFFFFFF" else "#08FFFFFF"), 10).apply {
                if (sel) setStroke(dp(2), Color.WHITE) else setStroke(1, Color.parseColor("#14FFFFFF"))
            }
            c.view.elevation = if (sel) dp(6).toFloat() else 0f
        }
        actions.forEachIndexed { i, a ->
            val sel = row == 1 && i == col
            val danger = a.view.tag == true
            a.view.background = round(if (sel) Color.WHITE else Color.parseColor(if (danger) "#24F87171" else "#12FFFFFF"), 7)
            val color = if (sel) getColor(R.color.button_text) else getColor(if (danger) R.color.danger_text else R.color.text)
            a.view.setTextColor(color)
            a.view.compoundDrawablesRelative[0]?.setTint(color)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        followPad(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_LEFT -> move(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> move(1)
            KeyEvent.KEYCODE_DPAD_UP -> if (row == 1) { row = 0; col = cards.indexOfFirst { it.enabled }.coerceAtLeast(0); Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (row == 0) { row = 1; col = 0; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> select()
            KeyEvent.KEYCODE_BUTTON_R2 -> openAchievements()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    /** Left / right within the row; disabled cards are skipped. */
    private fun move(d: Int) {
        if (row == 0) {
            var i = col + d
            while (i in cards.indices && !cards[i].enabled) i += d
            if (i in cards.indices) { col = i; Sounds.play(Sounds.Sound.MOVE); render() }
        } else {
            val i = col + d
            if (i in actions.indices) { col = i; Sounds.play(Sounds.Sound.MOVE); render() }
        }
    }

    private fun select() {
        if (row == 1) { actions.getOrNull(col)?.run?.invoke(); return }
        val c = cards.getOrNull(col)?.takeIf { it.enabled } ?: return
        DriveSync.cancel()
        if (launchInRetroArch(this, game, c.resume)) { Sounds.play(Sounds.Sound.LAUNCH); finish() }
        else { Sounds.play(Sounds.Sound.ERROR); Toast.makeText(this, R.string.retroarch_missing, Toast.LENGTH_SHORT).show() }
    }

    // ---- Actions ----

    private fun openAchievements() {
        if (!RetroAchievements.supports(game.console) || RetroAchievements.account(this) == null) return
        Sounds.play(Sounds.Sound.OPEN)
        startActivity(Intent(this, AchievementsActivity::class.java)
            .putExtra(AchievementsActivity.EXTRA_CONSOLE, game.console.id)
            .putExtra(AchievementsActivity.EXTRA_FILE, game.file.path))
    }

    private fun openManual() {
        val name = Manuals.find(this, game.console, game.baseName) ?: return
        Sounds.play(Sounds.Sound.OPEN)
        startActivity(Intent(this, ManualActivity::class.java)
            .putExtra(ManualActivity.EXTRA_CONSOLE, game.console.id)
            .putExtra(ManualActivity.EXTRA_FILE, name)
            .putExtra(ManualActivity.EXTRA_TITLE, game.title))
    }

    private fun toggleFinished() {
        val done = game.finished == null
        Thread {
            val r = runCatching { Finished.set(game, done) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { entry ->
                    game.finished = entry
                    Sounds.play(if (done) Sounds.Sound.DONE else Sounds.Sound.BACK)
                    Toast.makeText(this, if (done) "${game.title}: ¡terminado! (${formatDuration(game.runtimeSeconds)})" else "${game.title} ya no está en terminados", Toast.LENGTH_LONG).show()
                    finishedAction.text = finishedLabel()
                    render()
                }.onFailure { Toast.makeText(this, "No se pudo guardar: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    /** Off the stick only (kept in the Drive collection) or also from Drive (to its bin). Saves always stay. */
    private fun confirmRemove() {
        val auth = DriveAuth(this)
        val withDrive = auth.isConfigured && auth.isConnected
        val options = if (withDrive) arrayOf(
            "Quitar solo del stick (se queda en tu colección de Drive)",
            "Borrar también de Drive (va a la papelera de Drive)",
        ) else arrayOf("Borrar del stick")
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Borrar ${game.title}")
            .setItems(options) { _, which -> remove(alsoDrive = withDrive && which == 1) }
            .setNegativeButton("Cancelar", null)
            .showForPad()
    }

    private fun remove(alsoDrive: Boolean) {
        Toast.makeText(this, "Borrando ${game.title}…", Toast.LENGTH_SHORT).show()
        val app = applicationContext
        DriveSync.removeGame(this, game, alsoDrive) { error ->
            Sounds.play(if (error != null) Sounds.Sound.ERROR else Sounds.Sound.BACK)
            val msg = when {
                error != null -> "No se pudo borrar ${game.title}: $error"
                alsoDrive -> "${game.title} borrado del stick y de Drive. Las partidas guardadas se conservan."
                else -> "${game.title} quitado del stick. Sigue en tu colección de Drive."
            }
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
            if (error == null) {
                Fichas.forget(app, game)
                DriveCollection.refresh(app, force = true)
                finish()
            }
        }
    }

    // ---- Helpers ----

    private fun formatWhen(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val today = Calendar.getInstance()
        val time = SimpleDateFormat("HH:mm", Locale.US).format(millis)
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        if (sameDay(then, today)) return "Hoy, $time"
        if (sameDay(then, (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) })) return "Ayer, $time"
        return SimpleDateFormat("d MMM", es).format(millis).replace(".", "") + ", $time"
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when { h > 0 -> "$h h $m min"; m > 0 -> "$m min"; else -> "< 1 min" }
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
    }
}
