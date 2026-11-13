package com.retrostick.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Retro Ajustes → Inicio → «Abrir RetroStick al encender el stick». For sticks where Android's own launcher is still
 * the home screen (a new stick set up without ADB): RetroStick comes up by itself once the stick has started.
 * On by default only on sticks set up by the assistant; where RetroStick is the home screen it isn't needed.
 * Android 9 still lets an app open its screen from BOOT_COMPLETED (Android 10 stopped it).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED || !enabled(context)) return
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("home", Context.MODE_PRIVATE)
        fun enabled(context: Context) = prefs(context).getBoolean("openOnBoot", Setup.usesCoresApp(context))
        fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean("openOnBoot", on).apply()
    }
}
