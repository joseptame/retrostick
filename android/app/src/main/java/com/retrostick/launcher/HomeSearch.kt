package com.retrostick.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Search on the home screen (L2): your games on the stick and in the Drive collection, every console, with the
 * same on-screen keyboard as RetroShop. Results above, keyboard below: ▲ from the keyboard's top row goes into
 * the results, ✕ on a result jumps to that game. MainActivity gives the candidates and handles the choice.
 */
class HomeSearch(private val ctx: Context, private val covers: CoverLoader) {
    class Hit(val title: String, val console: Console, val game: Game?, val drive: DriveCollection.Item?, val probe: Game)

    var candidates: () -> List<Hit> = { emptyList() }
    var onPick: (Hit) -> Unit = {}
    /** What is searched, shown under the query ("Juegos de NES en el stick y en tu Drive") */
    var scope = ""

    private val kbRows = listOf(
        "1234567890".map { it.toString() },
        "qwertyuiop".map { it.toString() },
        "asdfghjkl".map { it.toString() } + DELETE,
        "zxcvbnm".map { it.toString() } + SPACE,
    )
    private var query = ""
    private var hits = listOf<Hit>()
    private var kbRow = 1
    private var kbCol = 0
    /** -1 = keyboard; otherwise the result with the focus. */
    private var hitFocus = -1

    private val queryText = text(20f, R.color.text).apply { typeface = ctx.resources.getFont(R.font.inter_bold) }
    private val countText = text(10.5f, R.color.text_3)
    private val results = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scopeText = text(9.5f, R.color.text_3)
    private val keys = mutableListOf<List<TextView>>()
    val view: View
    val isOpen get() = view.visibility == View.VISIBLE

    init {
        val rows = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        kbRows.forEach { labels ->
            val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
            keys += labels.map { label ->
                text(14f, R.color.text).apply {
                    text = if (label == DELETE) "⌫" else if (label == SPACE) "espacio" else label.uppercase()
                    gravity = Gravity.CENTER
                    typeface = ctx.resources.getFont(R.font.inter_semibold)
                }.also { row.addView(it, LinearLayout.LayoutParams(dp(if (label == SPACE) 130 else 42), dp(36)).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }) }
            }
            rows.addView(row)
        }
        val head = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(queryText, LinearLayout.LayoutParams(0, -2, 1f))
            addView(countText)
        }
        view = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(56), dp(34), dp(56), dp(18))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#FF0D0B1F"), Color.parseColor("#FF0D0B1F")))
            isClickable = true
            visibility = View.GONE
            addView(head)
            addView(scopeText.apply { setPadding(0, dp(4), 0, dp(10)) })
            addView(results, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(rows, LinearLayout.LayoutParams(-1, -2))
        }
    }

    fun open() {
        scopeText.text = "$scope.      ✕ Escribir / Abrir      □ Borrar      ○ Cerrar"
        query = ""
        kbRow = 1; kbCol = 0; hitFocus = -1
        view.visibility = View.VISIBLE
        refresh()
    }

    fun close() { view.visibility = View.GONE }

    /** Keys while open. */
    fun onKey(code: Int): Sounds.Sound? {
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_L2 -> { close(); return Sounds.Sound.BACK }
            KeyEvent.KEYCODE_BUTTON_X -> { type(DELETE); return Sounds.Sound.MOVE }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (hitFocus < 0) kbCol = (kbCol - 1).coerceAtLeast(0) else return null
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (hitFocus < 0) kbCol = (kbCol + 1).coerceAtMost(kbRows[kbRow].size - 1) else return null
            KeyEvent.KEYCODE_DPAD_UP -> when {
                hitFocus > 0 -> hitFocus--
                hitFocus == 0 -> return null
                kbRow > 0 -> { kbRow--; kbCol = kbCol.coerceAtMost(kbRows[kbRow].size - 1) }
                hits.isNotEmpty() -> hitFocus = hits.size - 1
                else -> return null
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> when {
                hitFocus in 0 until hits.size - 1 -> hitFocus++
                hitFocus == hits.size - 1 && hitFocus >= 0 -> hitFocus = -1
                kbRow < kbRows.size - 1 -> { kbRow++; kbCol = kbCol.coerceAtMost(kbRows[kbRow].size - 1) }
                else -> return null
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (hitFocus >= 0) {
                    val hit = hits.getOrNull(hitFocus) ?: return null
                    close()
                    onPick(hit)
                    return Sounds.Sound.OPEN
                }
                type(kbRows[kbRow][kbCol])
                return Sounds.Sound.MOVE
            }
            else -> return null
        }
        render()
        return Sounds.Sound.MOVE
    }

    private fun type(label: String) {
        query = when (label) {
            DELETE -> query.dropLast(1)
            SPACE -> if (query.isEmpty() || query.endsWith(" ")) query else "$query "
            else -> query + label
        }
        refresh()
    }

    private fun refresh() {
        val words = query.split(Regex("""\s+""")).map(::looseKey).filter { it.isNotEmpty() }
        hits = if (words.isEmpty()) emptyList() else candidates().filter { h ->
            val hay = looseKey(h.title)
            words.all { it in hay }
        }.take(MAX)
        hitFocus = hitFocus.coerceAtMost(hits.size - 1)
        results.removeAllViews()
        hits.forEach { h ->
            val row = LinearLayout(ctx).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(4), dp(10), dp(4))
                val thumb = CoverView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = round(Color.parseColor("#14FFFFFF"), 5) }
                covers.load(h.probe, dp(34)) { b -> thumb.setImageBitmap(b ?: logoCover(ctx, h.console, dp(34))) }
                if (h.drive != null) thumb.alpha = 0.65f
                addView(thumb, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(text(12.5f, R.color.text).apply {
                    text = h.title; typeface = ctx.resources.getFont(R.font.inter_semibold); maxLines = 1; ellipsize = TextUtils.TruncateAt.END; tag = "t"
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
                addView(text(10f, R.color.text_3).apply { text = if (h.drive != null) "En tu Drive" else "En el stick"; tag = "s" })
            }
            results.addView(row, LinearLayout.LayoutParams(-1, dp(42)).apply { bottomMargin = dp(3) })
        }
        render()
    }

    private fun render() {
        queryText.text = "🔍  $query▌"
        countText.text = when {
            query.isBlank() -> "Escribe el nombre de un juego"
            hits.isEmpty() -> "Ningún juego coincide"
            hits.size == MAX -> "Los $MAX primeros"
            else -> if (hits.size == 1) "1 juego" else "${hits.size} juegos"
        }
        for (i in 0 until results.childCount) {
            val r = results.getChildAt(i)
            val sel = i == hitFocus
            r.background = if (sel) round(Color.WHITE, 9) else null
            r.findViewWithTag<TextView>("t")?.setTextColor(if (sel) Color.parseColor("#17132F") else ctx.getColor(R.color.text))
            r.findViewWithTag<TextView>("s")?.setTextColor(if (sel) Color.parseColor("#5B5585") else ctx.getColor(R.color.text_3))
        }
        keys.forEachIndexed { r, row ->
            row.forEachIndexed { c, v ->
                val focus = hitFocus < 0 && r == kbRow && c == kbCol
                v.background = round(if (focus) Color.WHITE else Color.parseColor("#0FFFFFFF"), 9).apply {
                    setStroke(dp(1), if (focus) Color.WHITE else Color.parseColor("#14FFFFFF"))
                }
                v.setTextColor(if (focus) ctx.getColor(R.color.button_text) else ctx.getColor(R.color.text))
            }
        }
    }

    private fun text(sp: Float, color: Int) = TextView(ctx).apply {
        setTextColor(ctx.getColor(color)); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = ctx.resources.getFont(R.font.inter_regular)
    }
    private fun round(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    companion object {
        private const val DELETE = "⌫"
        private const val SPACE = "␣"
        private const val MAX = 6
    }
}
