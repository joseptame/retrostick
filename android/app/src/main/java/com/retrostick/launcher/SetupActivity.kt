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
import java.io.File
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
                "solo tendrás que pulsar A cuando Android pida permiso."
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rows, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(26) })
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
        val m = manifest ?: return work(null, "Buscando lo necesario…") {
            manifest = Setup.manifest()
            main.post { advance() }
        }
        val step = Step.entries.first { !isDone(it) }
        current = step
        when (step) {
            Step.RETROARCH -> install(step, m.retroarch, "retroarch.apk")
            Step.CORES -> install(step, m.cores, "cores.apk")
            Step.FIRST_RUN -> firstRun()
            Step.CONFIG -> work(step, "Copiando la configuración…") {
                // RetroArch rewrites retroarch.cfg when it closes: it must be gone before touching it
                getSystemService(ActivityManager::class.java).killBackgroundProcesses(RETROARCH_PACKAGE)
                Thread.sleep(800)
                val zip = File(cacheDir, "setup/config.zip")
                Setup.download(m.config, zip) { p -> main.post { progress = p; render() } }
                Setup.applyConfig(this, zip)
                zip.delete()
                Setup.markDone(this, m.config.version)
                main.post { finished() }
            }
        }
    }

    /** An APK: downloaded (once), then Android's installer, which asks to confirm. */
    private fun install(step: Step, asset: Setup.Asset, name: String) {
        val apk = File(cacheDir, "update/$name")
        val open = {
            startActivity(Updater.installIntent(this, name))
        }
        if (apk.isFile && apk.length() == asset.size) {
            detail = "Android te pedirá confirmar la instalación: elige «Instalar». Si te pregunta, permite a RetroStick instalar apps."
            setAction("Instalar") { open() }
            render()
            if (autoOpened.add(step)) open()
            return
        }
        work(step, "Descargando (${asset.size / 1_048_576} MB)…") {
            Setup.download(asset, apk) { p -> main.post { progress = p; render() } }
            main.post { advance() }
        }
    }

    /** RetroArch's first start creates its folders and retroarch.cfg; back here by itself when it has. */
    private fun firstRun() {
        val open = {
            waitingForRetroArch = true
            startActivity(Intent(Intent.ACTION_MAIN).setComponent(ComponentName(RETROARCH_PACKAGE, "com.retroarch.browser.mainmenu.MainMenuActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            main.postDelayed(::waitForRetroArch, 2000)
        }
        detail = "Se abrirá RetroArch. Si pide permiso para acceder a los archivos, acepta. Después vuelvo aquí solo."
        setAction("Abrir RetroArch") { open() }
        render()
        if (autoOpened.add(Step.FIRST_RUN)) main.postDelayed({ open() }, 1500)
    }

    private fun waitForRetroArch() {
        if (!waitingForRetroArch) return
        if (isDone(Step.FIRST_RUN)) {
            waitingForRetroArch = false
            // A moment for RetroArch to finish writing, then this screen comes back to the front
            main.postDelayed({
                startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }, 3000)
        } else {
            main.postDelayed(::waitForRetroArch, 2000)
        }
    }

    private fun work(step: Step?, what: String, task: () -> Unit) {
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
            }.onSuccess { main.post { busy = false; progress = -1f } }
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

    private fun render(done: Boolean = false) {
        rows.removeAllViews()
        val active = current
        for (step in Step.entries) {
            val state = when {
                done -> 2
                step == active -> 1
                active != null && step.ordinal < active.ordinal -> 2
                manifest != null && step != Step.CONFIG && step.ordinal > (active?.ordinal ?: -1) && isDone(step) -> 2
                else -> 0
            }
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(18), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(Color.parseColor(if (state == 1) "#1FFFFFFF" else "#0AFFFFFF"))
                    if (state == 1) setStroke(dp(1), Color.parseColor("#559D8CFF"))
                }
            }
            row.addView(label(15f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                text = when (state) { 2 -> "✓"; 1 -> "${step.ordinal + 1}"; else -> "${step.ordinal + 1}" }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(when (state) { 2 -> "#2FB45A"; 1 -> "#7B5CFF"; else -> "#26FFFFFF" }))
                }
                alpha = if (state == 0) 0.6f else 1f
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(15f, if (state == 0) getColor(R.color.text_3) else Color.WHITE, bold = state == 1).apply { this.text = step.title })
            if (state == 1) {
                val err = error
                text.addView(label(12.5f, if (err != null) getColor(R.color.danger_text) else getColor(R.color.text_2)).apply {
                    this.text = err ?: detail
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                if (busy && err == null) {
                    text.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = this@SetupActivity.progress < 0
                        max = 1000
                        this.progress = (this@SetupActivity.progress.coerceAtLeast(0f) * 1000).toInt()
                    }, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
                }
            }
            row.addView(text, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) })
            rows.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        if (active == null && !done) {
            // Before the steps (permission, looking for the release): the message goes under the list
            rows.addView(label(13f, if (error != null) getColor(R.color.danger_text) else getColor(R.color.text_2)).apply {
                text = error ?: detail
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
        hint.visibility = if (action != null) View.VISIBLE else View.GONE
        hint.text = "Ⓐ  $actionLabel"
        foot.text = if (done) "Listo. Ahora conecta tu Google Drive y vincula el stick con la app del móvil desde Retro Ajustes."
            else "Los juegos, las BIOS y las partidas son tuyos: van a tu propio Google Drive."
    }
}
