package com.retrostick.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Home during a game goes back to the game, on every stick. On Android 11+ the pad's Home is Android's own Home key
 * (no app gets it, so RetroArch can't map it), and the remote's Home is that key everywhere: both brought up the
 * home screen and left the game behind. RetroStick is the home screen, so when Home brings it up while a game is
 * open ([playing]) it puts RetroArch back in front, where it was, and says where RetroArch's menu is.
 * RetroArch's menu can't be opened from here: the Android build has no network commands (HAVE_NETWORK_CMD,
 * checked 2026-11-13 on 1.22.2), and an accessibility service that catches Home (it does see it) breaks the
 * Pro Controller's D-pad in RetroArch: keys it lets through reach RetroArch without working (tried 2026-11-13). On Android 9 the pad's Home reaches RetroArch itself (code 110 = menu).
 */
object RetroArchCmd {
    private fun prefs(context: Context) = context.getSharedPreferences("retroarch", Context.MODE_PRIVATE)

    /** A game was handed to RetroArch and RetroStick hasn't come back from it yet (since the stick last booted). */
    fun playing(context: Context): Boolean {
        val p = prefs(context)
        return p.getBoolean("playing", false) && p.getInt("boot", -1) == bootCount(context)
    }

    fun setPlaying(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean("playing", value).putInt("boot", bootCount(context)).apply()

    private fun bootCount(context: Context) =
        android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, 0)

    private val EXTRAS = listOf("ROM", "LIBRETRO", "CONFIGFILE")

    /** The game being launched: Home can open it again if RetroArch was closed meanwhile. */
    fun remember(context: Context, launch: Intent) {
        prefs(context).edit().apply { EXTRAS.forEach { putString(it, launch.getStringExtra(it)) } }.apply()
    }

    /**
     * RetroArch back in front: the same task (the same intent as its root, extras don't count), the game where it
     * was. If Android closed RetroArch meanwhile, the same game starts again instead of RetroArch's empty menu.
     */
    fun backToGame(context: Context) {
        val p = prefs(context)
        val intent = Intent(Intent.ACTION_MAIN)
            .setComponent(ComponentName(RETROARCH_PACKAGE, RETROARCH_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        EXTRAS.forEach { k -> p.getString(k, null)?.let { intent.putExtra(k, it) } }
        if (p.getString("ROM", null) == null) { setPlaying(context, false); return }
        runCatching { context.startActivity(intent) }.onFailure { setPlaying(context, false); return }
        val menu = if (PadProfiles.standardCodes) "Captura (Switch) o PS (DualSense)" else "Home (Switch) o PS (DualSense)"
        Toast.makeText(context.applicationContext, "Menú del juego: $menu", Toast.LENGTH_LONG).show()
    }
}
