package com.retrostick.launcher

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A game's scanned manual on the TV, page by page: ◀ ▶ turn pages, ✕ switches between the whole page and
 * the page at full width (then ▲ ▼ scroll), ○ closes. Remembers the last page read of each manual.
 */
class ManualActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var image: ImageView
    private lateinit var scroll: ScrollView
    private lateinit var info: TextView
    private lateinit var status: TextView
    private var renderer: PdfRenderer? = null
    private var fd: ParcelFileDescriptor? = null
    private var page = 0
    private var wide = false
    private var bitmap: Bitmap? = null
    private var lastCode = 0
    private var lastTime = 0L
    private val name get() = intent.getStringExtra(EXTRA_FILE) ?: ""
    private val prefs get() = getSharedPreferences("manuals", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val console = CONSOLES.firstOrNull { it.id == intent.getStringExtra(EXTRA_CONSOLE) } ?: return finish()
        Sounds.init(this)
        image = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            addView(image, FrameLayout.LayoutParams(-1, -2))
        }
        info = TextView(this).apply {
            setTextColor(getColor(R.color.text_2))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = resources.getFont(R.font.inter_semibold)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#CC0D0B1F")); cornerRadius = dp(12).toFloat()
            }
        }
        status = TextView(this).apply {
            setTextColor(getColor(R.color.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            text = "Descargando el manual…"
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(scroll, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -1))
            addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        })
        info.visibility = View.GONE
        io.execute {
            val result = runCatching {
                val file = Manuals.file(this, console, name)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                pfd to PdfRenderer(pfd)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { (pfd, r) ->
                    fd = pfd
                    renderer = r
                    status.visibility = View.GONE
                    info.visibility = View.VISIBLE
                    show(prefs.getInt(name, 0))
                }.onFailure {
                    Sounds.play(Sounds.Sound.ERROR)
                    status.text = "No se pudo abrir el manual: ${it.message}\n\nPulsa ○ para volver."
                }
            }
        }
    }

    private fun show(index: Int) {
        val r = renderer ?: return
        page = index.coerceIn(0, r.pageCount - 1)
        prefs.edit().putInt(name, page).apply()
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val bmp = synchronized(r) {
            r.openPage(page).use { p ->
                // Whole page: fit the height. Wide: the full screen width (taller than the screen, scrolls).
                val scale = if (wide) screenW.toFloat() / p.width else minOf(screenH.toFloat() / p.height, screenW.toFloat() / p.width)
                val w = (p.width * scale).toInt().coerceAtLeast(1)
                val h = (p.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                    it.eraseColor(Color.WHITE)
                    p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        image.setImageBitmap(bmp)
        bitmap?.recycle()
        bitmap = bmp
        scroll.scrollTo(0, 0)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        info.text = "$title · página ${page + 1} de ${r.pageCount}      ◀ ▶ Página      ✕ ${if (wide) "Página entera" else "Ampliar"}      ○ Volver"
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = StickStatus.standardKey(event.device, event.keyCode)
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        // One press = one page: the DualSense's D-pad can report a press twice in a row (key + hat), and a held
        // button repeats. Ignore repeats and the same key again within a quarter of a second.
        val turn = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1
        if (turn) {
            if (event.repeatCount > 0) return true
            if (code == lastCode && event.eventTime - lastTime < 250) return true
            lastCode = code
            lastTime = event.eventTime
        }
        when (code) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> { Sounds.play(Sounds.Sound.BACK); finish() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_R1 -> if (renderer != null && page < renderer!!.pageCount - 1) { show(page + 1); Sounds.play(Sounds.Sound.MOVE) }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_L1 -> if (page > 0) { show(page - 1); Sounds.play(Sounds.Sound.MOVE) }
            KeyEvent.KEYCODE_DPAD_DOWN -> scroll.smoothScrollBy(0, dp(160))
            KeyEvent.KEYCODE_DPAD_UP -> scroll.smoothScrollBy(0, -dp(160))
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                wide = !wide
                show(page)
                Sounds.play(Sounds.Sound.OPEN)
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { renderer?.close() }
        runCatching { fd?.close() }
        bitmap?.recycle()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        const val EXTRA_CONSOLE = "console"
        const val EXTRA_FILE = "file"
        const val EXTRA_TITLE = "title"
    }
}
