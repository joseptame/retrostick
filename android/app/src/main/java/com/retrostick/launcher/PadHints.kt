package com.retrostick.launcher

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan

/**
 * Controller hints ("[X] Elegir      [O] Volver") drawn with the buttons of the pad in use, like the home screen:
 * PlayStation ✕ ○ △ □ (icons), Nintendo A B X Y, Xbox A B Y X, the remote OK / Atrás. Tokens, by role:
 * [X] confirm · [O] back · [TRI] top button (Favorito, Cerrar sesión…) · [SQ] left button (Detalles, Borrar…) ·
 * [L1R1] the shoulders · [L2] / [R2] the triggers · [OPT] Options / + / Menu.
 */
object PadHints {
    private val PS_ICONS = mapOf(
        "[X]" to R.drawable.ic_hint_cross, "[O]" to R.drawable.ic_hint_circle,
        "[TRI]" to R.drawable.ic_hint_triangle, "[SQ]" to R.drawable.ic_hint_square,
    )

    private fun label(token: String, style: PadStyle): String = when (style) {
        PadStyle.PLAYSTATION -> mapOf("[L1R1]" to "L1 / R1", "[L2]" to "L2", "[R2]" to "R2", "[OPT]" to "Options")
        PadStyle.NINTENDO -> mapOf("[X]" to "A", "[O]" to "B", "[TRI]" to "X", "[SQ]" to "Y", "[L1R1]" to "L / R", "[L2]" to "ZL", "[R2]" to "ZR", "[OPT]" to "+")
        PadStyle.XBOX -> mapOf("[X]" to "A", "[O]" to "B", "[TRI]" to "Y", "[SQ]" to "X", "[L1R1]" to "LB / RB", "[L2]" to "LT", "[R2]" to "RT", "[OPT]" to "Menu")
        // The remote has OK, the arrows and Back; the rest shows as Android's own names
        PadStyle.REMOTE -> mapOf("[X]" to "OK", "[O]" to "Atrás", "[TRI]" to "Y", "[SQ]" to "X", "[L1R1]" to "LB / RB", "[L2]" to "LT", "[R2]" to "RT", "[OPT]" to "Menu")
    }[token] ?: token

    /** The quick save shortcut as this pad names it (Create + L1 on the DualSense). */
    fun quickSave(style: PadStyle) = when (style) {
        PadStyle.NINTENDO -> "− + L"
        PadStyle.XBOX -> "View + LB"
        else -> "Create + L1"
    }

    /** [template] with every token as the pad's button: an icon (PlayStation's face buttons) or a boxed label. */
    fun build(context: Context, template: String, style: PadStyle, color: Int, iconPx: Int): CharSequence {
        val out = SpannableStringBuilder(template)
        val density = context.resources.displayMetrics.density
        for (token in listOf("[X]", "[O]", "[TRI]", "[SQ]", "[L1R1]", "[L2]", "[R2]", "[OPT]")) {
            var at = out.indexOf(token)
            while (at >= 0) {
                val icon = PS_ICONS[token]?.takeIf { style == PadStyle.PLAYSTATION }
                if (icon != null) {
                    val d = context.getDrawable(icon)!!.mutate().apply { setTint(color); setBounds(0, 0, iconPx, iconPx) }
                    out.replace(at, at + token.length, " ")
                    out.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BOTTOM), at, at + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    at = out.indexOf(token, at + 1)
                } else {
                    val text = label(token, style)
                    out.replace(at, at + token.length, text)
                    out.setSpan(PillSpan(color, density), at, at + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    at = out.indexOf(token, at + text.length)
                }
            }
        }
        return out
    }
}
