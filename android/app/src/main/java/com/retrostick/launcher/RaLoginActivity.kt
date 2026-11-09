package com.retrostick.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * RetroAchievements login on the TV (Retro Ajustes → Mis logros, signed out): user and password with the on-screen
 * keyboard on the left, or the big QR on the right that opens the phone app's login (…/?ra=1). While it shows, the
 * stick looks in Drive every few seconds and moves on to Mis logros when the account arrives from the phone.
 * The password is only sent to log in (api/ra.ts?login=1), never kept. Sizes in dp for the 960×540 dp screen.
 */
class RaLoginActivity : Activity() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var user: EditText
    private lateinit var password: EditText
    private lateinit var enter: Button
    private lateinit var error: TextView
    private lateinit var qr: ImageView
    private lateinit var qrNote: TextView
    private var busy = false
    /** Confirm went down in this screen (its release then opens the keyboard on the field) */
    private var confirmDown = false
    private val before by lazy { RetroAchievements.account(this)?.let { it.user + "|" + it.token } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        before
        val title = text(24f, R.color.text).apply { typeface = font(R.font.inter_extrabold); text = "Inicia sesión en RetroAchievements" }
        val sub = text(11f, R.color.text_2).apply { text = "Mis logros · Para ganar logros en tus juegos. Usa tu cuenta de RetroAchievements (retroachievements.org), no la de RetroStick ni la de Google." }

        user = field("Usuario de RetroAchievements", password = false)
        password = field("Contraseña de RetroAchievements", password = true)
        // The keyboard's Enter: from the user on to the password (keyboard still open), from the password log in
        user.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        password.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_GO
        user.setOnEditorActionListener { _, _, _ -> openKeyboard(password); true }
        password.setOnEditorActionListener { _, _, _ -> hideKeyboard(); login(); true }
        enter = Button(this).apply {
            text = ENTER_LABEL; isAllCaps = false; typeface = font(R.font.inter_bold)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { login() }
            onFocusChangeListener = View.OnFocusChangeListener { _, _ -> styleButton() }
        }
        styleButton()
        error = text(10.5f, R.color.danger_text).apply { visibility = View.GONE }
        val note = text(9.5f, R.color.text_3).apply {
            text = "¿No tienes cuenta? Créala gratis en retroachievements.org. La contraseña solo se usa para pedir el acceso a RetroAchievements: no se guarda ni en el stick ni en Drive."
            setLineSpacing(0f, 1.2f)
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(section("Con tu cuenta de RetroAchievements"))
            addView(user, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            addView(password, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(10) })
            addView(enter, LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(14) })
            addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(note, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }

        qr = ImageView(this).apply { setBackgroundColor(Color.WHITE); scaleType = ImageView.ScaleType.FIT_CENTER }
        qrNote = text(10.5f, R.color.text_2).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.2f)
            text = if (DriveAuth(this@RaLoginActivity).isConnected)
                "Escanéalo con la cámara del móvil e inicia sesión en RetroAchievements allí.\nLa tele lo recogerá sola."
            else "Para entrar con el móvil, conecta antes Google Drive\n(Retro Ajustes → Sincronizar ahora)."
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = round(Color.parseColor("#0DFFFFFF"), 14)
            addView(section("Con el móvil"))
            addView(qr, LinearLayout.LayoutParams(dp(250), dp(250)).apply { topMargin = dp(14) })
            addView(qrNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }

        val or = text(11f, R.color.text_3).apply { text = "o"; gravity = Gravity.CENTER }
        val body = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(left, LinearLayout.LayoutParams(0, -2, 1f))
            addView(or, LinearLayout.LayoutParams(dp(56), -2))
            addView(right, LinearLayout.LayoutParams(dp(360), -2))
        }
        val hints = text(10.5f, R.color.chip_text)
        setHints(hints, "▲ ▼ Moverse      [X]  Escribir / Entrar      [O]  Volver")
        setContentView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_screen)
            addView(LinearLayout(this@RaLoginActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(56), dp(34), dp(56), dp(36))
                addView(title)
                addView(sub, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
            }, FrameLayout.LayoutParams(-1, -1))
            addView(hints, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dp(40); bottomMargin = dp(14) })
        })
        user.requestFocus()

        if (DriveAuth(this).isConnected) {
            io.execute {
                val bmp = runCatching { ShopPairing.raLoginQr(dp(250)) }
                main.post { if (!isDestroyed) bmp.onSuccess { qr.setImageBitmap(it) }.onFailure { qrNote.text = "No se pudo generar el QR: ${it.message}" } }
            }
            main.postDelayed(poll, POLL_MS)
        } else qr.alpha = 0.15f
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(poll)
        io.shutdown()
    }

    /** The account the phone app leaves in Drive (QR login): on to Mis logros when it arrives. */
    private val poll = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            io.execute {
                runCatching { RetroAchievements.pullFromDrive(this@RaLoginActivity) }
                val now = RetroAchievements.account(this@RaLoginActivity)
                main.post {
                    if (isDestroyed) return@post
                    if (now != null && now.user + "|" + now.token != before) done(now.user) else main.postDelayed(this, POLL_MS)
                }
            }
        }
    }

    private fun login() {
        val u = user.text.toString().trim()
        val p = password.text.toString()
        if (busy) return
        if (u.isEmpty() || p.isEmpty()) {
            showError("Escribe el usuario y la contraseña.")
            (if (u.isEmpty()) user else password).requestFocus()
            return
        }
        busy = true
        enter.text = "Entrando…"
        error.visibility = View.GONE
        io.execute {
            val r = runCatching { RetroAchievements.login(this, u, p) }
            main.post {
                if (isDestroyed) return@post
                busy = false
                enter.text = ENTER_LABEL
                r.onSuccess { done(it) }.onFailure { Sounds.play(Sounds.Sound.ERROR); showError(it.message ?: "No se pudo iniciar sesión.") }
            }
        }
    }

    private fun done(name: String) {
        Sounds.play(Sounds.Sound.DONE)
        Toast.makeText(this, "Sesión iniciada como $name", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, ProfileActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    /**
     * The pad's confirm opens the keyboard / presses the button (as DPAD_CENTER) and its back goes back: on Android 9
     * the DualSense's Cross arrives as BUTTON_B, which the system would take as "back".
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && StickStatus.isGamepad(event.device)) followPad(event)
        when (StickStatus.standardKey(event.device, event.keyCode)) {
            // (Enter is left to the field: the keyboard's Enter moves on / logs in, see setOnEditorActionListener)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> {
                val focused = currentFocus
                if (focused is EditText) {
                    // Pressing a field opens the keyboard on it. Only a press made here: the release of the button
                    // that opened this screen (on the Mis logros card) also arrives, and must not open it.
                    if (event.action == KeyEvent.ACTION_DOWN) confirmDown = true
                    else if (event.action == KeyEvent.ACTION_UP && confirmDown) { confirmDown = false; openKeyboard(focused) }
                    return true
                }
                return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount))
            }
            // ▲ ▼ between user, password and the button (a single-line field would keep the arrows for its cursor)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    val order = listOf<View>(user, password, enter)
                    val i = order.indexOf(currentFocus).coerceAtLeast(0)
                    val next = i + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                    if (next in order.indices) { order[next].requestFocus(); Sounds.play(Sounds.Sound.MOVE) }
                }
                return true
            }
            KeyEvent.KEYCODE_BUTTON_B -> {
                // With the keyboard open, B only closes it; pressed again, back out
                if (event.action == KeyEvent.ACTION_UP && !hideKeyboard()) { Sounds.play(Sounds.Sound.BACK); finish() }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Closes the on-screen keyboard; false when it wasn't open. */
    private fun hideKeyboard(): Boolean {
        val token = (currentFocus ?: window.decorView).windowToken
        return getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(token, 0)
    }

    private fun openKeyboard(field: EditText) {
        field.requestFocus()
        field.setSelection(field.text.length)
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun field(hint: String, password: Boolean) = EditText(this).apply {
        this.hint = hint
        // The keyboard only opens when the field is pressed (confirm on it), never just by focusing it
        showSoftInputOnFocus = false
        setSingleLine()
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(getColor(R.color.text)); setHintTextColor(getColor(R.color.text_3))
        setPadding(dp(14), 0, dp(14), 0)
        inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        // Setting inputType (password) switches to monospace: the app's font again
        typeface = font(R.font.inter_regular)
        RetroAchievements.account(this@RaLoginActivity)?.takeIf { !password }?.let { setText(it.user) }
        fun style(focused: Boolean) {
            background = round(Color.parseColor(if (focused) "#24FFFFFF" else "#12FFFFFF"), 10).apply {
                setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else Color.parseColor("#22FFFFFF"))
            }
        }
        style(false)
        onFocusChangeListener = View.OnFocusChangeListener { _, f -> style(f) }
    }

    private fun styleButton() {
        val focused = ::enter.isInitialized && enter.isFocused
        enter.background = round(if (focused) Color.WHITE else Color.parseColor("#2E9D8CFF"), 10)
        enter.setTextColor(if (focused) getColor(R.color.button_text) else getColor(R.color.text))
    }

    private fun section(name: String) = text(9f, R.color.text_3).apply { text = name.uppercase(); letterSpacing = 0.14f; typeface = font(R.font.inter_semibold) }


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
        private const val POLL_MS = 5000L
        private const val ENTER_LABEL = "Entrar en RetroAchievements"
    }
}
