package com.retrostick.launcher

import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.TextView

/** Marks "players" in a text; [withIcons] draws it as the app's line icon (not an emoji). */
const val PLAYERS = "\u0000\u0001\u0000"
/** Marks the "Terminado" trophy in a text; [withIcons] draws it as the app's line icon. */
const val TROPHY = "\u0000\u0002\u0000"

private val ICONS = mapOf(PLAYERS to R.drawable.ic_players, TROPHY to R.drawable.ic_trophy)

/** [text] with every icon token as its line icon, sized and tinted like this TextView's text. */
fun TextView.withIcons(text: CharSequence): CharSequence {
    val out = SpannableStringBuilder(text)
    for ((token, res) in ICONS) {
        var at = out.indexOf(token)
        while (at >= 0) {
            val size = (textSize * 1.05f).toInt()
            val d = context.getDrawable(res)!!.mutate().apply { setTint(currentTextColor); setBounds(0, 0, size, size) }
            out.replace(at, at + token.length, " ")
            out.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BASELINE), at, at + 1, 0)
            at = out.indexOf(token, at + 1)
        }
    }
    return out
}
