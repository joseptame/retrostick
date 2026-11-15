package com.retrostick.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * RetroStick as the stick's home screen, only when chosen (Retro Ajustes → Inicio). Android offers as home apps only
 * the activities with the HOME filter, and that filter lives on the activity-alias HomeAlias (manifest), turned off
 * on the first start unless RetroStick already is the home (migrate): installing it doesn't change the home screen.
 * Choosing it turns the alias on and opens Android's window; leaving it (or backing out) turns it off again.
 */
object HomeApp {
    private fun alias(context: Context) = ComponentName(context.packageName, "${HomeApp::class.java.`package`!!.name}.HomeAlias")

    fun isHome(context: Context): Boolean =
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName

    fun setOffered(context: Context, on: Boolean) {
        context.packageManager.setComponentEnabledSetting(alias(context),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }

    /** Not the home screen: stop being offered as one (so Android doesn't ask on Home or at boot). */
    fun settle(context: Context) {
        if (!isHome(context)) setOffered(context, false)
    }

    /**
     * Once, on the first start (the alias is on in the manifest so an update keeps a stick whose home is RetroStick):
     * where RetroStick is the home screen it stays, also on Android 9 whose «Always» was saved for MainActivity (its
     * preferred activity); anywhere else (a new install, a TV still asking or with its own launcher) it's turned off.
     */
    @Suppress("DEPRECATION")
    fun migrate(context: Context) {
        val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        if (prefs.getBoolean("aliasSet", false)) return
        val filters = ArrayList<android.content.IntentFilter>()
        val chosen = runCatching { context.packageManager.getPreferredActivities(filters, ArrayList(), context.packageName) }.getOrNull() != null &&
            filters.any { it.hasCategory(Intent.CATEGORY_HOME) }
        setOffered(context, isHome(context) || chosen)
        prefs.edit().putBoolean("aliasSet", true).apply()
    }
}
