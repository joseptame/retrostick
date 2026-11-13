package com.retrostick.launcher

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.os.SystemClock
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Welcome assistant of a new stick (Setup.needed): installs RetroArch and «RetroStick Núcleos», opens RetroArch once
 * so it creates its folders and retroarch.cfg, then copies the configuration pack. Each step that Android makes the
 * user confirm (installing an app, RetroArch's storage permission) waits for A; the rest goes by itself. Coming back
 * (onResume) always picks up from the first step not done yet, so cancelling an installer just asks again.
 */
class SetupActivity : Activity() {

    private enum class Step(val title: String) {
        RETROARCH("RetroArch, el programa de los emuladores"),
        CORES("RetroStick Núcleos, los emuladores de cada consola"),
        FIRST_RUN("Primer arranque de RetroArch"),
        CONFIG("Configuración: mandos, marcos y ajustes de cada consola"),
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var manifest: Setup.Manifest? = null
    private var busy = false
    private var current: Step? = null
    private var progress = -1f
    private var detail = ""
    private var error: String? = null
    /** What A does now (null: nothing to press). */
    private var action: (() -> Unit)? = null
    private var actionLabel = ""
    /** Installers and RetroArch are opened by themselves only once per step; after that, A opens them again. */
    private val autoOpened = mutableSetOf<Step>()
    private var waitingForRetroArch = false

    private lateinit var rows: LinearLayout
    private lateinit var hint: TextView
    private lateinit var foot: TextView
    private lateinit var status: TextView
    private lateinit var statusBar: ProgressBar

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF2A2170.toInt(), 0xFF0D0B1F.toInt()))
            alpha = 0.7f
        }, FrameLayout.LayoutParams(-1, dp(360)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(26f, Color.WHITE, bold = true).apply {
            text = android.text.SpannableString("RetroStick").apply {
                setSpan(android.text.style.ForegroundColorSpan(getColor(R.color.brand_accent)), 5, 10, 0)
            }
        })
        col.addView(label(30f, Color.WHITE, bold = true).apply { text = "Vamos a preparar tu stick" },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        col.addView(label(14f, getColor(R.color.text_2)).apply {
            text = "Instalo los emuladores y dejo cada consola con sus mandos, marcos y ajustes. Tarda unos minutos; " +
                "solo tendrás que pulsar A (o el botón central del mando del stick) cuando Android pida permiso."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
        buildRows()
        hint = label(15f, getColor(R.color.button_text), bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(22), dp(11), dp(22), dp(11))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.WHITE) }
            visibility = View.GONE
            isFocusable = false
            setOnClickListener { action?.invoke() }
        }
        col.addView(hint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(26) })
        foot = label(11.5f, getColor(R.color.text_3))
        col.addView(foot, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        root.addView(col, FrameLayout.LayoutParams(dp(760), -2, Gravity.CENTER))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        advance()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        pulse?.cancel()
        io.shutdownNow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                action?.invoke()
                return true
            }
            // Nothing to go back to: the stick isn't usable until it's set up
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun label(sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        typeface = resources.getFont(if (bold) R.font.inter_bold else R.font.inter_regular)
    }

    // ---- The steps ----

    private fun isDone(step: Step): Boolean {
        val m = manifest
        return when (step) {
            Step.RETROARCH -> Setup.versionCode(this, RETROARCH_PACKAGE) != null
            Step.CORES -> (Setup.versionCode(this, Setup.CORES_PACKAGE) ?: -1) >= (m?.cores?.versionCode ?: 0)
            Step.FIRST_RUN -> File(RETROARCH_CFG).let { it.isFile && it.length() > 0 }
            Step.CONFIG -> false
        }
    }

    /** Runs the first step not done yet. */
    private fun advance() {
        if (busy) return render()
        error = null
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            current = null
            detail = "RetroStick necesita guardar los juegos y la configuración en el stick."
            setAction("Dar permiso") {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
            }
            return render()
        }
        val m = manifest ?: return work(null, "Consultando en internet la última versión de RetroArch, los núcleos y la configuración…", then = ::advance) {
            val found = Setup.manifest()
            main.post { manifest = found }
        }
        val step = Step.entries.first { !isDone(it) }
        current = step
        when (step) {
            Step.RETROARCH -> install(step, m.retroarch, "retroarch.apk")
            Step.CORES -> install(step, m.cores, "cores.apk")
            Step.FIRST_RUN -> firstRun()
            Step.CONFIG -> work(step, "Cerrando RetroArch para poder tocar su configuración…", then = ::finished) {
                // RetroArch rewrites retroarch.cfg when it closes: it must be gone before touching it
                getSystemService(ActivityManager::class.java).killBackgroundProcesses(RETROARCH_PACKAGE)
                Thread.sleep(800)
                val zip = File(cacheDir, "setup/config.zip")
                Setup.download(m.config, zip, downloading("Descargando la configuración", m.config.size))
                var shown = 0L
                Setup.applyConfig(this, zip) { name, i, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - shown >= 120 || i == total) {
                        shown = now
                        val text = if (total > 0) "${describe(name)}  ·  $i de $total" else describe(name)
                        main.post { progress = if (total > 0) i.toFloat() / total else -1f; detail = text; render() }
                    }
                }
                main.post { progress = -1f; detail = "Guardando los ajustes de RetroArch…"; render() }
                zip.delete()
                Setup.markDone(this, m.config.version)
            }
        }
    }

    /** An APK: downloaded (once), then Android's installer, which asks to confirm. */
    private fun install(step: Step, asset: Setup.Asset, name: String) {
        val apk = File(cacheDir, "update/$name")
        val open = {
            if (packageManager.canRequestPackageInstalls()) {
                startActivity(Updater.installIntent(this, name))
            } else {
                // Android 8+: allowed once per app (Ajustes > Seguridad > Fuentes desconocidas); back here, A (or the remote's centre button) installs
                android.widget.Toast.makeText(this, "Activa «Permitir de esta fuente» para RetroStick y vuelve", android.widget.Toast.LENGTH_LONG).show()
                val perApp = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))
                startActivity(if (perApp.resolveActivity(packageManager) != null) perApp else Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        if (apk.isFile && apk.length() == asset.size) {
            detail = "Android te pedirá confirmar la instalación: elige «Instalar». Si te pregunta, permite a RetroStick instalar apps."
            setAction("Instalar") { open() }
            render()
            if (autoOpened.add(step)) open()
            return
        }
        work(step, "Conectando para descargar (${mb(asset.size)} MB)…", then = ::advance) {
            Setup.download(asset, apk, downloading("Descargando", asset.size))
        }
    }

    /** RetroArch's first start creates its folders and retroarch.cfg; back here by itself when it has. */
    private fun firstRun() {
        val open = {
            waitingForRetroArch = true
            detail = "Esperando a que RetroArch guarde su configuración: en su menú, elige «Cerrar RetroArch»…"
            render()
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(RETROARCH_PACKAGE, "com.retroarch.browser.mainmenu.MainMenuActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(::waitForRetroArch, 2000)
        }
        detail = "Se abrirá RetroArch. Si pide permiso para acceder a los archivos, acepta. Cuando veas su menú, baja hasta " +
            "«Cerrar RetroArch» y púlsalo: al cerrarse guarda su configuración y vuelvo aquí solo."
        setAction("Abrir RetroArch") { open() }
        render()
        if (autoOpened.add(Step.FIRST_RUN)) main.postDelayed({ open() }, 1500)
    }

    private fun waitForRetroArch() {
        if (!waitingForRetroArch) return
        if (isDone(Step.FIRST_RUN)) {
            waitingForRetroArch = false
            detail = "RetroArch ya está listo. Volviendo a RetroStick…"
            render()
            // A moment for RetroArch to finish writing, then this screen comes back to the front
            main.postDelayed({
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 3000)
        } else {
            main.postDelayed(::waitForRetroArch, 2000)
        }
    }

    /**
     * Runs [task] off the main thread, then [then] on it. busy goes back to false BEFORE [then]: advance() does
     * nothing while busy, so if the task posted advance() itself the setup stayed stuck on «Buscando lo necesario…».
     */
    private fun work(step: Step?, what: String, then: () -> Unit, task: () -> Unit) {
        busy = true
        current = step ?: current
        detail = what
        progress = -1f
        setAction("") {}
        render()
        io.execute {
            runCatching(task).onFailure { e ->
                android.util.Log.w("RetroStick", "Setup failed", e)
                main.post {
                    busy = false
                    error = when {
                        e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ->
                            "No hay conexión a internet. Comprueba la red del stick."
                        else -> e.message ?: e.toString()
                    }
                    setAction("Reintentar") { advance() }
                    render()
                }
            }.onSuccess { main.post { busy = false; progress = -1f; then() } }
        }
    }

    private fun setAction(text: String, run: () -> Unit) {
        actionLabel = text
        action = if (text.isEmpty()) null else run
    }

    private fun finished() {
        current = null
        detail = ""
        setAction("Empezar") {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        render(done = true)
    }

    // ---- Drawing ----

    private class RowViews(val row: LinearLayout, val halo: View, val badge: TextView, val title: TextView,
                           val detail: TextView, val bar: ProgressBar, var state: Int = -1)

    private val rowViews = mutableListOf<RowViews>()
    private val fontBold by lazy { resources.getFont(R.font.inter_bold) }
    private val fontRegular by lazy { resources.getFont(R.font.inter_regular) }
    /** The step whose number pulses: what's going on now (or waiting for A). */
    private var pulsing: Step? = null
    private var pulse: AnimatorSet? = null

    private fun bar() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.brand_accent))
        indeterminateTintList = progressTintList
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
    }

    /** The rows are made once and only updated: rebuilding them would restart the bars and the pulse each time. */
    private fun buildRows() {
        for (step in Step.entries) {
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(18), dp(4))
            }
            // The number, with a halo behind that spreads and fades while the step is under way
            val box = FrameLayout(this)
            val halo = View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF7B5CFF.toInt()) }
                alpha = 0f
            }
            box.addView(halo, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            val badge = label(15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
            box.addView(badge, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            row.addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            val title = label(15f, Color.WHITE).apply { this.text = step.title }
            text.addView(title)
            val detail = label(12.5f, getColor(R.color.text_2))
            text.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            val bar = bar()
            text.addView(bar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            rowViews += RowViews(row, halo, badge, title, detail, bar)
        }
        // Before the steps (permission, looking for the release): the message and its bar go under the list
        status = label(13f, getColor(R.color.text_2))
        rows.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        statusBar = bar().apply { isIndeterminate = true }
        rows.addView(statusBar, LinearLayout.LayoutParams(dp(320), dp(10)).apply { topMargin = dp(8) })
    }

    private fun render(done: Boolean = false) {
        val active = current
        val err = error
        for (step in Step.entries) {
            val v = rowViews[step.ordinal]
            val state = when {
                done -> 2
                step == active -> 1
                active != null && step.ordinal < active.ordinal -> 2
                manifest != null && step != Step.CONFIG && step.ordinal > (active?.ordinal ?: -1) && isDone(step) -> 2
                else -> 0
            }
            if (state != v.state) {
                v.row.background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(if (state == 1) "#1FFFFFFF" else "#0AFFFFFF"))
                    if (state == 1) setStroke(dp(1), Color.parseColor("#559D8CFF"))
                }
                v.badge.text = if (state == 2) "✓" else "${step.ordinal + 1}"
                v.badge.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(when (state) { 2 -> "#2FB45A"; 1 -> "#7B5CFF"; else -> "#26FFFFFF" }))
                }
                v.badge.alpha = if (state == 0) 0.6f else 1f
                // A step just done: its tick pops
                if (state == 2 && v.state != -1) {
                    v.badge.scaleX = 0.4f; v.badge.scaleY = 0.4f
                    v.badge.animate().scaleX(1f).scaleY(1f).setDuration(350)
                        .setInterpolator(android.view.animation.OvershootInterpolator(3f)).start()
                }
                v.title.setTextColor(if (state == 0) getColor(R.color.text_3) else Color.WHITE)
                v.title.typeface = if (state == 1) fontBold else fontRegular
                v.state = state
            }
            v.detail.visibility = if (state == 1) View.VISIBLE else View.GONE
            if (state == 1) {
                v.detail.text = err ?: detail
                v.detail.setTextColor(if (err != null) getColor(R.color.danger_text) else getColor(R.color.text_2))
            }
            val showBar = state == 1 && err == null && (busy || waitingForRetroArch)
            v.bar.visibility = if (showBar) View.VISIBLE else View.GONE
            if (showBar) {
                val indeterminate = progress < 0
                if (v.bar.isIndeterminate != indeterminate) v.bar.isIndeterminate = indeterminate
                if (!indeterminate) v.bar.progress = (progress * 1000).toInt()
            }
        }
        val before = active == null && !done
        status.visibility = if (before) View.VISIBLE else View.GONE
        status.text = err ?: detail
        status.setTextColor(if (err != null) getColor(R.color.danger_text) else getColor(R.color.text_2))
        statusBar.visibility = if (before && busy && err == null) View.VISIBLE else View.GONE
        setPulse(if (!done && err == null) active else null)
        hint.visibility = if (action != null) View.VISIBLE else View.GONE
        // The stick's remote has no A: its centre button does the same (onKeyDown), so both are named
        hint.text = android.text.SpannableStringBuilder(actionLabel).apply {
            val from = length
            append("\nPulsa A o el botón central del mando")
            setSpan(android.text.style.RelativeSizeSpan(0.72f), from, length, 0)
        }
        foot.text = if (done) "Listo. Ahora conecta tu Google Drive y vincula el stick con la app del móvil desde Retro Ajustes."
            else "Los juegos, las BIOS y las partidas son tuyos: van a tu propio Google Drive."
    }

    private fun setPulse(step: Step?) {
        if (step == pulsing) return
        pulse?.cancel()
        pulsing?.let { rowViews[it.ordinal].halo.apply { alpha = 0f; scaleX = 1f; scaleY = 1f } }
        pulsing = step
        pulse = step?.let {
            val halo = rowViews[it.ordinal].halo
            val parts = listOf(
                ObjectAnimator.ofFloat(halo, View.SCALE_X, 1f, 1.5f),
                ObjectAnimator.ofFloat(halo, View.SCALE_Y, 1f, 1.5f),
                ObjectAnimator.ofFloat(halo, View.ALPHA, 0.6f, 0f),
            ).onEach { a -> a.repeatCount = ValueAnimator.INFINITE; a.duration = 1400 }
            AnimatorSet().apply { playTogether(parts); start() }
        }
    }

    // ---- What's being done, in words ----

    /** A download's progress for [detail]: «34,2 de 120,0 MB · 2,3 MB/s · quedan unos 40 s», a few times a second. */
    private fun downloading(what: String, size: Long): (Float) -> Unit {
        val start = SystemClock.elapsedRealtime()
        var shown = 0L
        return { p ->
            val now = SystemClock.elapsedRealtime()
            if (now - shown >= 300 || p >= 1f) {
                shown = now
                val done = (p * size).toLong()
                val secs = (now - start) / 1000f
                val speed = if (secs > 0.5f) done / secs else 0f
                val text = if (p >= 1f) "Comprobando que la descarga está completa…" else buildString {
                    append(what).append(": ").append(mb(done)).append(" de ").append(mb(size)).append(" MB")
                    if (speed > 0f) append(" · ").append(mb(speed.toLong())).append(" MB/s")
                    if (speed > 0f && secs > 3f) append(" · quedan ").append(eta(((size - done) / speed).toInt()))
                }
                main.post { progress = p; detail = text; render() }
            }
        }
    }

    private fun mb(bytes: Long) = String.format(Locale("es", "ES"), "%.1f", bytes / 1_048_576f)

    private fun eta(s: Int) = when {
        s < 10 -> "unos segundos"
        s < 60 -> "unos ${(s + 4) / 5 * 5} s"
        else -> "unos ${(s + 30) / 60} min"
    }

    /** A file of the configuration pack, as the person would call it. */
    private fun describe(path: String): String {
        val parts = path.removePrefix("RetroArch/").split('/')
        val base = parts.last().substringBeforeLast('.')
        fun console(id: String) = CONSOLES.firstOrNull { it.id == id }?.name
        return when {
            path == "retroarch-keys.cfg" -> "Ajustes generales de RetroArch"
            parts[0] == "autoconfig" -> "Perfil de mando: $base"
            parts[0] == "overlays" -> console(base)?.let { "Marco de $it" } ?: "Marcos de pantalla"
            parts[0] == "config" && parts.getOrNull(1) == "remaps" -> "Botones de ${console(base) ?: parts.getOrNull(2) ?: base}"
            parts[0] == "config" && path.endsWith(".opt") -> "Opciones del emulador ${parts.getOrNull(1) ?: base}"
            parts[0] == "config" -> "Ajustes de ${console(base) ?: parts.getOrNull(1) ?: base}"
            else -> parts.last()
        }
    }
}
