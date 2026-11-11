package com.retrostick.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The last card of each shelf (design H, https://claude.ai/artifact/Prw1S3DkJZcPvHGwBwya5g): «Añadir juegos» with the
 * console's logo, its cartridge or disc drawn ([ConsoleMediaView]), a «+» in its colour and how many games RetroShop
 * has for it. Opens RetroShop on that console.
 */
object ShopCard {
    class Style(val top: Int, val bottom: Int, val accent: Int, val plus: Int = Color.WHITE)

    fun style(console: Console): Style = when (console.id) {
        "nes" -> Style(0xFF3A1D24.toInt(), 0xFF1A1022.toInt(), 0xFFE2575C.toInt())
        "snes" -> Style(0xFF2A2346.toInt(), 0xFF16122C.toInt(), 0xFF8A5CF6.toInt())
        "gb" -> Style(0xFF1E2A3E.toInt(), 0xFF121A2A.toInt(), 0xFF3A8EE0.toInt())
        "gbc" -> Style(0xFF4A37D6.toInt(), 0xFF241A6E.toInt(), Color.WHITE, 0xFF4A37D6.toInt())
        "gba" -> Style(0xFF1D2160.toInt(), 0xFF12143A.toInt(), 0xFF5B6BFF.toInt())
        "md" -> Style(0xFF2A1414.toInt(), 0xFF140D10.toInt(), 0xFFE53E3E.toInt())
        "sms" -> Style(0xFF2A1418.toInt(), 0xFF120C10.toInt(), 0xFFFF3B3B.toInt())
        "n64" -> Style(0xFF16281E.toInt(), 0xFF0E1614.toInt(), 0xFF2FB45A.toInt())
        "psx" -> Style(0xFF22252C.toInt(), 0xFF121418.toInt(), 0xFF3A8EE0.toInt())
        else -> Style(0xFF2A2346.toInt(), 0xFF16122C.toInt(), 0xFF9D8CFF.toInt())
    }

    fun background(console: Console, radiusPx: Float) = style(console).let {
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(it.top, it.bottom)).apply { cornerRadius = radiusPx }
    }

    // ---- How many games RetroShop has for each console (its last catalog kept on the stick) ----

    private val counts = ConcurrentHashMap<String, Int>()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** "1.024 en RetroShop", or "En RetroShop" until the catalog has been seen once (then [onReady] with the count). */
    fun countText(context: Context, console: Console, onReady: (String) -> Unit): String {
        counts[console.id]?.let { return label(it) }
        val app = context.applicationContext
        io.execute {
            val n = runCatching { ShopApi.cached(app, console)?.games?.size }.getOrNull() ?: return@execute
            counts[console.id] = n
            main.post { onReady(label(n)) }
        }
        return "En RetroShop"
    }

    private fun label(n: Int) = java.text.NumberFormat.getIntegerInstance(java.util.Locale("es", "ES")).format(n) + " en RetroShop"

    /** The card's content for the covers shelf (inside item_cover's coverInner, ~98 dp square). */
    fun shelfContent(context: Context, console: Console): View {
        val s = style(console)
        fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        // White, like the unselected tabs: the colour logos (Game Boy Color's dark letters) get lost on the card's colour.
        // Nintendo 64's letters are white already, so its N and 64 keep their colours.
        val logo = ImageView(context).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_START
            if (console.colourOnCard) setImageResource(console.logo)
            else if (console.logoWhite != null) setImageResource(console.logoWhite)
            else { setImageResource(console.logo); setColorFilter(Color.WHITE) }
            alpha = 0.92f
        }
        val media = ConsoleMediaView(context, console)
        val plus = TextView(context).apply {
            text = "+"; gravity = Gravity.CENTER; setTextColor(s.plus)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f); typeface = context.resources.getFont(R.font.inter_bold)
            includeFontPadding = false
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(s.accent) }
            elevation = dp(3).toFloat()
        }
        val title = TextView(context).apply {
            text = "Añadir juegos"; setTextColor(context.getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f); typeface = context.resources.getFont(R.font.inter_extrabold)
            maxLines = 1
        }
        val count = TextView(context).apply {
            setTextColor(Color.parseColor("#C9C3EA")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 6.5f); maxLines = 1
            typeface = context.resources.getFont(R.font.inter_regular)
        }
        count.text = countText(context, console) { count.text = it }
        return FrameLayout(context).apply {
            addView(logo, FrameLayout.LayoutParams(dp(70), dp(13), Gravity.TOP or Gravity.START).apply { leftMargin = dp(7); topMargin = dp(7) })
            addView(media, FrameLayout.LayoutParams(dp(54), dp(48), Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = dp(21) })
            addView(plus, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.START).apply { leftMargin = dp(64); topMargin = dp(55) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title); addView(count)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin = dp(7); rightMargin = dp(5); bottomMargin = dp(6) })
        }
    }
}

/**
 * Each console's cartridge or disc, drawn (no images): NES and Super Nintendo cartridges, the Game Boy / Color /
 * Advance ones, Mega Drive and Master System, Nintendo 64, the PlayStation disc. Simplified shapes, not replicas.
 * Drawn in a 128×120 box scaled to fit the view, centred.
 */
class ConsoleMediaView(context: Context, private val console: Console) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width / 128f, height / 120f)
        canvas.save()
        canvas.translate((width - 128 * s) / 2f, (height - 120 * s) / 2f)
        canvas.scale(s, s)
        when (console.id) {
            "nes" -> cartridge(canvas, 4f, 8f, 120f, 104f, floatArrayOf(4f, 4f, 2f, 2f), 0xFF9D9AA6, 0xFF7D7A86) {
                label(canvas, 14f, 20f, 100f, 60f, solid(0xFF2B2B33), 0xFFB8B5C2)
                p.shader = null; p.color = 0x40000000; canvas.drawRect(29f, 88f, 99f, 91f, p)
            }
            "snes" -> cartridge(canvas, 0f, 12f, 128f, 96f, floatArrayOf(22f, 22f, 4f, 4f), 0xFFA9A6B4, 0xFF86838F) {
                label(canvas, 16f, 30f, 96f, 52f, solid(0xFF2B2B33), 0xFFC7C4D0)
            }
            "gb" -> cartridge(canvas, 21f, 10f, 86f, 100f, floatArrayOf(4f, 14f, 4f, 4f), 0xFFA9A6B4, 0xFF86838F) {
                label(canvas, 30f, 26f, 68f, 56f, solid(0xFF6B8F3A), 0xFFC7C4D0)
            }
            "gbc" -> cartridge(canvas, 21f, 10f, 86f, 100f, floatArrayOf(4f, 14f, 4f, 4f), 0xD99678FF, 0xD95A3CC8, stroke = 0x4DFFFFFF) {
                label(canvas, 30f, 26f, 68f, 56f, LinearGradient(30f, 26f, 98f, 82f,
                    intArrayOf(0xFFE53E5A.toInt(), 0xFFF2B632.toInt(), 0xFF3FBF6A.toInt(), 0xFF3A8EE0.toInt()), floatArrayOf(0f, 0.4f, 0.7f, 1f), Shader.TileMode.CLAMP), 0xB3FFFFFF)
            }
            "gba" -> cartridge(canvas, 10f, 25f, 108f, 70f, floatArrayOf(4f, 4f, 10f, 10f), 0xFF5B5F7A, 0xFF3E4158) {
                label(canvas, 22f, 35f, 84f, 40f, solid(0xFF2E3A8F), 0xFF8A8FB0)
            }
            "md" -> cartridge(canvas, 8f, 8f, 112f, 104f, floatArrayOf(6f, 6f, 3f, 3f), 0xFF2D2D33, 0xFF18181C, stroke = 0x1FFFFFFF) {
                label(canvas, 18f, 20f, 92f, 64f, LinearGradient(18f, 20f, 110f, 84f, 0xFF3A6BD9.toInt(), 0xFF1D2A6E.toInt(), Shader.TileMode.CLAMP), 0x26FFFFFF)
            }
            "sms" -> cartridge(canvas, 12f, 10f, 104f, 100f, floatArrayOf(3f, 3f, 3f, 3f), 0xFF232327, 0xFF121214, stroke = 0x1FFFFFFF) {
                p.shader = null; p.color = 0xFFFF3B3B.toInt()
                var x = 22f
                while (x < 106f) { canvas.drawRect(x, 20f, minOf(x + 6f, 106f), 30f, p); x += 9f }
                label(canvas, 22f, 36f, 84f, 56f, solid(0xFFF2F0FF), 0xFFD0CDE0)
                p.shader = LinearGradient(28f, 42f, 100f, 86f, 0xFF3FBF6A.toInt(), 0xFF1D6E3A.toInt(), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(RectF(28f, 42f, 100f, 86f), 2f, 2f, p)
                p.shader = null
            }
            "n64" -> n64Cartridge(canvas)
            "psx" -> disc(canvas)
            else -> cartridge(canvas, 4f, 8f, 120f, 104f, floatArrayOf(4f, 4f, 4f, 4f), 0xFF9D9AA6, 0xFF7D7A86) {}
        }
        canvas.restore()
    }

    private fun solid(color: Long): Shader = LinearGradient(0f, 0f, 0f, 1f, color.toInt(), color.toInt(), Shader.TileMode.CLAMP)

    /** A cartridge body: [r] = top-left, top-right, bottom-right, bottom-left radii. */
    private fun cartridge(c: Canvas, x: Float, y: Float, w: Float, h: Float, r: FloatArray, top: Long, bottom: Long, stroke: Long? = null, inside: () -> Unit) {
        val rect = RectF(x, y, x + w, y + h)
        val path = Path().apply { addRoundRect(rect, floatArrayOf(r[0], r[0], r[1], r[1], r[2], r[2], r[3], r[3]), Path.Direction.CW) }
        c.save(); c.translate(0f, 6f); c.drawPath(path, shadow); c.restore()
        p.shader = LinearGradient(0f, y, 0f, y + h, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        stroke?.let {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = it.toInt()
            c.drawPath(path, p); p.style = Paint.Style.FILL
        }
        inside()
    }

    /** The label window: its art and the border around it. */
    private fun label(c: Canvas, x: Float, y: Float, w: Float, h: Float, art: Shader, border: Long) {
        val rect = RectF(x, y, x + w, y + h)
        p.shader = art; c.drawRoundRect(rect, 3f, 3f, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = border.toInt()
        c.drawRoundRect(RectF(x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f), 2f, 2f, p)
        p.style = Paint.Style.FILL
    }

    /**
     * The N64 cartridge (option C10, https://claude.ai/artifact/Y9c1f1Sfo3TBe1zJTeLM2d): light grey, one continuous arch
     * across the top, the centre panel set apart from the side wings by a fine double line, the label as a big rounded
     * window, and a rounded notch at the bottom outer corner of each wing.
     */
    private fun n64Cartridge(c: Canvas) {
        val body = Path().apply {
            moveTo(2f, 33f)
            quadTo(2f, 31.5f, 3.5f, 31.2f)
            quadTo(64f, 7f, 124.5f, 31.2f)
            quadTo(126f, 31.5f, 126f, 33f)
            lineTo(126f, 100f)
            quadTo(126f, 102f, 124f, 102f)
            lineTo(4f, 102f)
            quadTo(2f, 102f, 2f, 100f)
            close()
        }
        c.save(); c.translate(0f, 5f); c.drawPath(body, shadow); c.restore()
        p.shader = LinearGradient(0f, 20f, 0f, 102f, 0xFFACACB3.toInt(), 0xFF8F8F97.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(body, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        // Fine double line between the centre panel and each wing
        p.strokeWidth = 0.8f; p.color = 0x40000000
        for (x in floatArrayOf(25.6f, 27f, 101f, 102.4f)) c.drawLine(x, 24.4f, x, 102f, p)
        p.strokeWidth = 0.6f; p.color = 0x22FFFFFF
        for (x in floatArrayOf(26.3f, 101.7f)) c.drawLine(x, 24.4f, x, 102f, p)
        // Rounded notch at the bottom outer corner of each wing
        p.strokeWidth = 1f; p.color = 0x48000000
        c.drawPath(Path().apply {
            moveTo(2f, 78f); lineTo(5f, 78f); quadTo(9.8f, 78f, 9.8f, 83f); lineTo(9.8f, 102f)
            moveTo(126f, 78f); lineTo(123f, 78f); quadTo(118.2f, 78f, 118.2f, 83f); lineTo(118.2f, 102f)
        }, p)
        // The label window: its dark rim, then the label in the N64 colours
        val label = Path().apply {
            moveTo(39f, 29.5f)
            quadTo(64f, 25f, 89f, 29.5f)
            quadTo(95.6f, 30.5f, 95.6f, 37f)
            lineTo(95.6f, 91f)
            quadTo(95.6f, 96.6f, 90f, 96.6f)
            lineTo(38f, 96.6f)
            quadTo(32.4f, 96.6f, 32.4f, 91f)
            lineTo(32.4f, 37f)
            quadTo(32.4f, 30.5f, 39f, 29.5f)
            close()
        }
        p.strokeWidth = 3f; p.color = 0x55000000
        c.drawPath(label, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(32.4f, 25f, 95.6f, 96.6f,
            intArrayOf(0xFFE53E3E.toInt(), 0xFFF2B632.toInt(), 0xFF2FB45A.toInt(), 0xFF3A6BD9.toInt()), floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(label, p)
        p.shader = null
    }

    private fun disc(c: Canvas) {
        val cx = 64f; val cy = 60f; val r = 56f
        c.drawCircle(cx, cy + 6f, r, shadow)
        p.shader = SweepGradient(cx, cy, intArrayOf(0xFF1A1A1F.toInt(), 0xFF3A3D48.toInt(), 0xFF6D7385.toInt(), 0xFF2A2C34.toInt(), 0xFF4B5060.toInt(), 0xFF1A1A1F.toInt()), null)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x26FFFFFF
        c.drawCircle(cx, cy, r - 1f, p); p.style = Paint.Style.FILL
        p.color = 0xFFD8DBE3.toInt(); c.drawCircle(cx, cy, 17f, p)
        p.color = 0xFF121418.toInt(); c.drawCircle(cx, cy, 6f, p)
    }
}
