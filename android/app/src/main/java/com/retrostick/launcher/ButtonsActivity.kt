package com.retrostick.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Retro Ajustes → Botones (design: https://claude.ai/artifact/4yvir7DWrMsidGBjgwQkfP): what each button of the pad
 * does in each console, and changing it. Saved as a RetroArch content-directory remap,
 * RetroArch/config/remaps/<core>/<console>.rmp (games in /ROMs/<console>): RetroStick's core remap with the user's
 * buttons on top, so Game Boy and Game Boy Color (both Gambatte) can differ. «Restablecer» deletes it, and the core
 * remap from stick/retroarch/remaps applies again. Players 1 and 2 get the same buttons.
 * Create / − stays out: it is RetroArch's shortcut button (Create + L1 saves…).
 */
class ButtonsActivity : Activity() {

    /**
     * A button of the pad, by its place (south = ✕ / A): [key] is its name in a RetroArch remap
     * (input_playerN_<key>) and [id] what it sends unless remapped (RetroPad button, or 20-23 = right stick X+ X- Y+ Y-).
     */
    private class Pad(val key: String, val id: Int, val ps: String, val nintendo: String, val xbox: String)

    private val buttons = listOf(
        Pad("btn_b", 0, "✕", "A", "A"), Pad("btn_a", 8, "○", "B", "B"), Pad("btn_y", 1, "□", "Y", "X"), Pad("btn_x", 9, "△", "X", "Y"),
        Pad("btn_l", 10, "L1", "L", "LB"), Pad("btn_r", 11, "R1", "R", "RB"), Pad("btn_l2", 12, "L2", "ZL", "LT"), Pad("btn_r2", 13, "R2", "ZR", "RT"),
        Pad("btn_start", 3, "Options", "+", "Menu"),
    )
    /** Nintendo 64: the right stick's four directions are the C buttons, remappable like any button. */
    private val rightStick = listOf(
        Pad("stk_r_y-", 23, "Stick dcho. ↑", "Stick dcho. ↑", "Stick dcho. ↑"), Pad("stk_r_y+", 22, "Stick dcho. ↓", "Stick dcho. ↓", "Stick dcho. ↓"),
        Pad("stk_r_x-", 21, "Stick dcho. ←", "Stick dcho. ←", "Stick dcho. ←"), Pad("stk_r_x+", 20, "Stick dcho. →", "Stick dcho. →", "Stick dcho. →"),
    )
    private val pads get() = if (console.id == "n64") buttons + rightStick else buttons

    /** Each console's buttons as RetroPad ids (how its core reads them). */
    private val consoleButtons: Map<String, List<Pair<Int, String>>> = mapOf(
        "nes" to listOf(8 to "A", 0 to "B", 3 to "Start", 2 to "Select"),
        "snes" to listOf(8 to "A", 0 to "B", 9 to "X", 1 to "Y", 10 to "L", 11 to "R", 3 to "Start", 2 to "Select"),
        "gb" to listOf(8 to "A", 0 to "B", 3 to "Start", 2 to "Select"),
        "gbc" to listOf(8 to "A", 0 to "B", 3 to "Start", 2 to "Select"),
        "gba" to listOf(8 to "A", 0 to "B", 10 to "L", 11 to "R", 3 to "Start", 2 to "Select"),
        "md" to listOf(1 to "A", 0 to "B", 8 to "C", 10 to "X", 9 to "Y", 11 to "Z", 3 to "Start", 2 to "Mode"),
        "sms" to listOf(0 to "1", 8 to "2", 3 to "Pause"),
        // C buttons: the core reads them from the right stick, so a button sends a right-stick direction
        // (RetroArch remap ids 20-23: right stick X+, X-, Y+, Y-)
        "n64" to listOf(0 to "A", 1 to "B", 12 to "Z", 10 to "L", 11 to "R", 3 to "Start",
            23 to "C-Arriba", 22 to "C-Abajo", 21 to "C-Izquierda", 20 to "C-Derecha"),
        // FinalBurn Neo's Neo Geo pad: A B at the bottom, C D at the top
        "neogeo" to listOf(0 to "A", 8 to "B", 1 to "C", 9 to "D", 3 to "Start", 2 to "Select"),
        "psx" to listOf(0 to "✕", 8 to "○", 1 to "□", 9 to "△", 10 to "L1", 11 to "R1", 12 to "L2", 13 to "R2", 3 to "Start", 2 to "Select"),
    )

    /** RetroArch's config folder of each core (its name in the core info). */
    private val coreDirs = mapOf(
        "fceumm" to "FCEUmm", "snes9x2010" to "Snes9x 2010", "gambatte" to "Gambatte", "mgba" to "mGBA",
        "genesis_plus_gx" to "Genesis Plus GX", "pcsx_rearmed" to "PCSX-ReARMed", "mupen64plus_next_gles2" to "Mupen64Plus-Next", "fbneo" to "FinalBurn Neo",
    )

    private var consoleIndex = 0
    private var selected = 0
    private var style = PadStyle.PLAYSTATION
    /** RetroPad id each pad button sends in the shown console (-1 = nothing). */
    private var mapping = IntArray(0)
    private var custom = false

    private lateinit var consoleRow: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var padView: PadDrawing
    private lateinit var note: TextView
    private lateinit var hints: TextView
    private lateinit var title: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = StickStatus.firstPadStyle()
        intent.getStringExtra("console")?.let { id -> CONSOLES.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { consoleIndex = it } }
        val kicker = text(10f, R.color.brand_accent).apply { text = "RETRO AJUSTES"; letterSpacing = 0.18f; typeface = font(R.font.inter_semibold) }
        title = text(22f, R.color.text).apply { text = "Botones de cada consola"; typeface = font(R.font.inter_extrabold) }
        consoleRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(Color.parseColor("#0DFFFFFF"), 14); setPadding(dp(6), dp(6), dp(6), dp(6)) }
        padView = PadDrawing(this)
        note = text(10.5f, R.color.text_2)
        hints = text(10.5f, R.color.chip_text)
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@ButtonsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(40), dp(26), dp(40), dp(40))
                addView(kicker)
                addView(title, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
                addView(consoleRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
                addView(LinearLayout(this@ButtonsActivity).apply {
                    addView(LinearLayout(this@ButtonsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(padView, LinearLayout.LayoutParams(dp(330), dp(210)))
                        addView(note, LinearLayout.LayoutParams(dp(330), -2).apply { topMargin = dp(10) })
                    }, LinearLayout.LayoutParams(-2, -2))
                    addView(rows, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(30) })
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
            }, FrameLayout.LayoutParams(-1, -1))
            addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40); bottomMargin = dp(16) })
        })
        load()
    }

    private val console get() = CONSOLES[consoleIndex]
    private fun coreDir(c: Console) = coreDirs[c.core.removeSuffix("_libretro_android.so")] ?: c.core
    private fun remapDir(c: Console) = File("$SDCARD/RetroArch/config/remaps/${coreDir(c)}")
    private fun customFile(c: Console) = File(remapDir(c), "${c.id}.rmp")
    private fun coreFile(c: Console) = File(remapDir(c), "${coreDir(c)}.rmp")

    /** key → value of a RetroArch .rmp / .cfg (comments and blank lines skipped). */
    private fun readCfg(f: File): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        if (!f.isFile) return out
        f.readLines().forEach { line ->
            val m = Regex("""^\s*([A-Za-z0-9_]+)\s*=\s*"?([^"]*)"?\s*$""").find(line) ?: return@forEach
            out[m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    /** What each pad button does now: the console's own remap, else RetroStick's core remap, else itself. */
    private fun load() {
        custom = customFile(console).isFile
        val cfg = readCfg(if (custom) customFile(console) else coreFile(console))
        mapping = IntArray(pads.size) { i -> cfg["input_player1_${pads[i].key}"]?.toIntOrNull() ?: pads[i].id }
        selected = selected.coerceIn(0, pads.size - 1)
        render()
    }

    private fun label(p: Pad) = when (style) {
        PadStyle.NINTENDO -> p.nintendo
        PadStyle.XBOX -> p.xbox
        else -> p.ps
    }

    private fun buttonName(id: Int) = when (id) {
        -1 -> "Nada"
        else -> consoleButtons[console.id]?.firstOrNull { it.first == id }?.second ?: "Nada"
    }

    private fun render() {
        consoleRow.removeAllViews()
        CONSOLES.forEachIndexed { i, c ->
            val on = i == consoleIndex
            consoleRow.addView(text(10.5f, R.color.text).apply {
                text = c.name; maxLines = 1; typeface = font(if (on) R.font.inter_bold else R.font.inter_semibold)
                setTextColor(if (on) Color.WHITE else getColor(R.color.text_2))
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = GradientDrawable().apply {
                    cornerRadius = dp(9).toFloat(); setColor(Color.parseColor(if (on) "#5B3FD6" else "#14FFFFFF"))
                    if (on) setStroke(dp(2), Color.WHITE)
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        rows.removeAllViews()
        pads.forEachIndexed { i, p ->
            val sel = i == selected
            rows.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(5), dp(14), dp(5))
                background = if (sel) GradientDrawable().apply { setColor(Color.parseColor("#5B3FD6")); cornerRadius = dp(9).toFloat(); setStroke(dp(2), Color.WHITE) } else null
                addView(text(12.5f, R.color.text).apply { text = label(p); typeface = font(R.font.inter_bold) }, LinearLayout.LayoutParams(dp(120), -2))
                addView(text(11f, R.color.text_2).apply { text = "→" }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(text(12.5f, R.color.text).apply {
                    text = buttonName(mapping[i]) + "  ·  ${console.name}"
                    typeface = font(R.font.inter_semibold); gravity = Gravity.END
                    if (mapping[i] == -1) setTextColor(getColor(R.color.text_2))
                }, LinearLayout.LayoutParams(-2, -2))
            }, LinearLayout.LayoutParams(-1, dp(if (pads.size > 9) 24 else 29)))
        }
        val extra = when (console.id) {
            "n64" -> " Las direcciones del stick derecho son los botones C: cámbialas como cualquier botón."
            "psx" -> " Los sticks funcionan en los juegos analógicos."
            else -> " El stick izquierdo mueve como la cruceta."
        }
        note.text = (if (custom) "Botones cambiados por ti. «Restablecer» vuelve a los de RetroStick." else "Botones de RetroStick para ${console.name}.") + extra
        padView.highlight = selected
        padView.labels = pads.map(::label)
        padView.nintendo = style == PadStyle.NINTENDO
        padView.invalidate()
        setHints(if (custom) "▲ ▼ Botón      [X]  Cambiar      [L1R1]  Consola      [TRI]  Restablecer      [O]  Volver"
            else "▲ ▼ Botón      [X]  Cambiar      [L1R1]  Consola      [O]  Volver")
    }

    /** ✕ on a row: what that pad button does in this console (or nothing). */
    private fun choose() {
        val p = pads[selected]
        val options = consoleButtons[console.id].orEmpty()
        val names = options.map { it.second } + "Nada"
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("${label(p)} en ${console.name}")
            .setItems(names.toTypedArray()) { _, which ->
                save(selected, options.getOrNull(which)?.first ?: -1)
            }
            .setNegativeButton("Cancelar", null)
            .showForPad()
    }

    /** RetroStick's core remap, with every pad button as shown here (both players). */
    private fun save(index: Int, id: Int) {
        mapping[index] = id
        val c = console
        val cfg = readCfg(coreFile(c))
        pads.forEachIndexed { i, p ->
            for (player in 1..2) cfg["input_player${player}_${p.key}"] = mapping[i].toString()
        }
        val ok = runCatching {
            remapDir(c).mkdirs()
            customFile(c).writeText("# ${c.name}: buttons chosen in RetroStick (Retro Ajustes → Botones). Delete to go back to ${coreDir(c)}.rmp\n" +
                cfg.entries.joinToString("\n", postfix = "\n") { "${it.key} = \"${it.value}\"" })
        }.isSuccess
        if (!ok) Toast.makeText(this, "No se pudo guardar (¿permiso de almacenamiento?)", Toast.LENGTH_LONG).show()
        Sounds.play(if (ok) Sounds.Sound.OPEN else Sounds.Sound.ERROR)
        load()
    }

    private fun reset() {
        if (!custom) return
        AlertDialog.Builder(this, R.style.Dialog_RetroStick)
            .setTitle("Restablecer ${console.name}")
            .setMessage("Vuelven los botones de RetroStick para ${console.name}.")
            .setPositiveButton("Restablecer") { _, _ -> customFile(console).delete(); Sounds.play(Sounds.Sound.BACK); load() }
            .setNegativeButton("Cancelar", null)
            .showForPad()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        val s = StickStatus.padStyle(event.device)
        if (s != style && s != PadStyle.REMOTE) { style = s; render() }
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (selected < pads.size - 1) { selected++; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_DPAD_UP -> if (selected > 0) { selected--; Sounds.play(Sounds.Sound.MOVE); render() }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_DPAD_RIGHT -> if (event.repeatCount == 0) { consoleIndex = (consoleIndex + 1) % CONSOLES.size; Sounds.play(Sounds.Sound.TAB); load() }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_DPAD_LEFT -> if (event.repeatCount == 0) { consoleIndex = (consoleIndex - 1 + CONSOLES.size) % CONSOLES.size; Sounds.play(Sounds.Sound.TAB); load() }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { Sounds.play(Sounds.Sound.OPEN); choose() }
            KeyEvent.KEYCODE_BUTTON_Y -> if (event.repeatCount == 0) reset()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun setHints(template: String) {
        hints.text = PadHints.build(this, template, style, hints.currentTextColor, (hints.textSize * 1.25f).toInt())
    }

    private fun text(sp: Float, color: Int) = TextView(this).apply {
        setTextColor(getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = font(R.font.inter_regular)
    }
    private fun font(id: Int) = resources.getFont(id)
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    /**
     * A plain pad (not a replica of any): body, D-pad, two sticks, the four face buttons, shoulders and triggers and
     * Options. The pad button of the selected row is lit. Drawn in a 330×210 box.
     */
    private class PadDrawing(context: Context) : View(context) {
        var highlight = 0
        var labels = listOf<String>()
        /** Nintendo pads: A on the right and B at the bottom (the rows keep their roles: A confirms like ✕). */
        var nintendo = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val lit = Color.parseColor("#7B5CFF")

        override fun onDraw(c: Canvas) {
            val s = minOf(width / 330f, height / 210f)
            c.save(); c.scale(s, s)
            fun fill(on: Boolean, base: String) { p.style = Paint.Style.FILL; p.color = if (on) lit else Color.parseColor(base) }
            // Triggers (L2 / R2 = rows 6 / 7) and shoulders (L1 / R1 = rows 4 / 5)
            fill(highlight == 6, "#3A3A46"); c.drawRoundRect(RectF(52f, 6f, 112f, 26f), 8f, 8f, p)
            fill(highlight == 7, "#3A3A46"); c.drawRoundRect(RectF(218f, 6f, 278f, 26f), 8f, 8f, p)
            fill(highlight == 4, "#4A4A58"); c.drawRoundRect(RectF(46f, 24f, 120f, 40f), 7f, 7f, p)
            fill(highlight == 5, "#4A4A58"); c.drawRoundRect(RectF(210f, 24f, 284f, 40f), 7f, 7f, p)
            // Body
            p.color = Color.parseColor("#2B2B33")
            val body = android.graphics.Path().apply {
                moveTo(70f, 36f); quadTo(165f, 22f, 260f, 36f); quadTo(318f, 46f, 324f, 120f); quadTo(330f, 200f, 282f, 204f)
                quadTo(252f, 206f, 232f, 168f); lineTo(212f, 140f); lineTo(118f, 140f); lineTo(98f, 168f)
                quadTo(78f, 206f, 48f, 204f); quadTo(0f, 200f, 6f, 120f); quadTo(12f, 46f, 70f, 36f); close()
            }
            c.drawPath(body, p)
            // D-pad and sticks
            p.color = Color.parseColor("#3A3A44")
            c.drawRoundRect(RectF(64f, 72f, 82f, 122f), 4f, 4f, p); c.drawRoundRect(RectF(48f, 88f, 98f, 106f), 4f, 4f, p)
            p.color = Color.parseColor("#1B1B20"); c.drawCircle(122f, 140f, 22f, p); c.drawCircle(208f, 140f, 22f, p)
            p.color = Color.parseColor("#3A3A44"); c.drawCircle(122f, 140f, 15f, p)
            // The right stick lights for its four directions (Nintendo 64 rows 9-12)
            fill(highlight in 9..12, "#3A3A44"); c.drawCircle(208f, 140f, 15f, p)
            // Face buttons where they physically are. Rows: 0 = confirm (✕ / A), 1 = back (○ / B), 2 = □ / Y, 3 = △ / X.
            // PlayStation and Xbox: confirm at the bottom, back on the right; Nintendo: A on the right, B at the bottom.
            val south = 258f to 112f; val east = 282f to 88f; val west = 234f to 88f; val north = 258f to 64f
            val places = if (nintendo) listOf(east, south, west, north) else listOf(south, east, west, north)
            val face = places.mapIndexed { i, (x, y) -> Triple(i, x, y) }
            p.textAlign = Paint.Align.CENTER; p.textSize = 13f; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            face.forEach { (i, x, y) ->
                fill(highlight == i, "#3A3A44"); c.drawCircle(x, y, 13f, p)
                p.color = Color.WHITE; c.drawText(labels.getOrNull(i).orEmpty(), x, y + 4.5f, p)
            }
            // Options (row 8)
            fill(highlight == 8, "#3A3A44"); c.drawRoundRect(RectF(196f, 66f, 216f, 80f), 6f, 6f, p)
            // Labels of the shoulders and triggers
            p.textSize = 11f; p.color = Color.WHITE
            listOf(4 to 83f, 5 to 247f).forEach { (i, x) -> c.drawText(labels.getOrNull(i).orEmpty(), x, 36f, p) }
            p.color = Color.parseColor("#CFC9F5")
            listOf(6 to 82f, 7 to 248f).forEach { (i, x) -> c.drawText(labels.getOrNull(i).orEmpty(), x, 20f, p) }
            c.restore()
        }
    }
}
