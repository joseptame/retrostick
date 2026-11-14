package com.retrostick.launcher

import android.content.Context

/**
 * The home screen's tabs as chosen in Retro Ajustes → Inicio (HomeSetupActivity): «Seguir jugando» on or off, which
 * consoles show, and their order. Kept in the "home" preferences, the same keys MainActivity always used
 * (recentTab, hiddenConsoles) plus consoleOrder (ids, comma separated; consoles it doesn't name go last, in their
 * usual order). «Seguir jugando» is always first.
 */
object HomeTabs {
    private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)

    /** Every console, in the chosen order (shown or not). */
    fun ordered(context: Context): List<Console> {
        val ids = prefs(context).getString("consoleOrder", null)?.split(',').orEmpty()
        val byId = CONSOLES.associateBy { it.id }
        val chosen = ids.mapNotNull { byId[it] }
        return chosen + CONSOLES.filter { it !in chosen }
    }

    fun setOrder(context: Context, consoles: List<Console>) =
        prefs(context).edit().putString("consoleOrder", consoles.joinToString(",") { it.id }).apply()

    fun hidden(context: Context): Set<String> = prefs(context).getStringSet("hiddenConsoles", emptySet()).orEmpty()

    fun setHidden(context: Context, ids: Set<String>) = prefs(context).edit().putStringSet("hiddenConsoles", ids).apply()

    fun recentTab(context: Context) = prefs(context).getBoolean("recentTab", true)

    fun setRecentTab(context: Context, on: Boolean) = prefs(context).edit().putBoolean("recentTab", on).apply()

    /** The tabs of the home screen, left to right (at least one console, even if all were hidden). */
    fun tabs(context: Context): List<Console> {
        val hidden = hidden(context)
        val consoles = ordered(context).filter { it.id !in hidden }.ifEmpty { ordered(context) }
        return listOfNotNull(RECENT.takeIf { recentTab(context) }) + consoles
    }
}
