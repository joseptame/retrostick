package com.retrostick.launcher

import android.text.SpannableStringBuilder
import android.text.style.DynamicDrawableSpan
import android.text.style.ImageSpan
import android.widget.TextView

/** Marks "players" in a meta line; [withPlayersIcon] draws it as the app's line icon (not an emoji). */
const val PLAYERS = "\u0000players\u0000"

/** [text] with every [PLAYERS] token as the players line icon, sized and tinted like this TextView's text. */
fun TextView.withPlayersIcon(text: String): CharSequence {
    val out = SpannableStringBuilder(text)
    var at = out.indexOf(PLAYERS)
    while (at >= 0) {
        val size = (textSize * 1.05f).toInt()
        val d = context.getDrawable(R.drawable.ic_players)!!.mutate().apply { setTint(currentTextColor); setBounds(0, 0, size, size) }
        out.replace(at, at + PLAYERS.length, " ")
        out.setSpan(ImageSpan(d, DynamicDrawableSpan.ALIGN_BASELINE), at, at + 1, 0)
        at = out.indexOf(PLAYERS, at + 1)
    }
    return out
}
