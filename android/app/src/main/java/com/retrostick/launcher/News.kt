package com.retrostick.launcher

import android.content.Context

/**
 * Novedades: what each launcher version brings, in a few plain lines. NewsActivity shows the ones not seen yet
 * the first time the home screen opens after an update (never on a brand-new stick), and Retro Ajustes →
 * Novedades shows the latest ones again. Add a Release here with each version that brings something to tell.
 */
object News {
    class Item(val icon: Int, val title: String, val text: String)
    class Release(val code: Int, val name: String, val items: List<Item>)

    /** Newest first. */
    val RELEASES = listOf(
        Release(85, "1.26.0", listOf(
            Item(R.drawable.ic_star, "Trucos",
                "En la ficha de cada juego: vidas infinitas, invencible, tiempo infinito… Eliges cuáles y se activan al jugar. Los logros siguen contando."),
            Item(R.drawable.ic_sticks, "Varios sticks en la misma cuenta",
                "Cada stick tiene su nombre y sus juegos. Desde el móvil eliges a qué stick mandar cada juego."),
            Item(R.drawable.ic_cloud_down, "Sigue la partida en otro stick",
                "Antes de abrir un juego, el stick trae de Drive la partida que guardaste en el otro."),
            Item(R.drawable.ic_news, "Novedades",
                "Después de cada actualización verás esta pantalla. Vuelve a verla cuando quieras en Retro Ajustes → Novedades."),
        )),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("news", Context.MODE_PRIVATE)

    /** The releases this stick hasn't been told about yet, newest first. */
    fun pending(context: Context): List<Release> {
        val seen = prefs(context).getInt("seen", -1)
        if (seen == -1) {
            // First launcher with Novedades: a stick that was updated gets this version's; a new one, nothing
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (info.firstInstallTime == info.lastUpdateTime) { markSeen(context); return emptyList() }
            return RELEASES.filter { it.code == BuildConfig.VERSION_CODE }
        }
        return RELEASES.filter { it.code > seen && it.code <= BuildConfig.VERSION_CODE }
    }

    fun markSeen(context: Context) = prefs(context).edit().putInt("seen", BuildConfig.VERSION_CODE).apply()
}
