package com.retrostick.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Keeps RetroStick alive while it downloads (RetroShop, a game passed from Drive). The downloads run on RetroStick's
 * own threads; once a game starts RetroStick is in the background and Android, short of memory for the emulator,
 * killed it: the download stopped and only resumed when it was asked again. A foreground service (with its
 * notification, as Android requires) tells Android RetroStick is working; it ends with the last download.
 */
object KeepAlive {
    private var busy = 0

    fun begin(context: Context) {
        val start = synchronized(this) { busy++ == 0 }
        if (start) runCatching {
            val app = context.applicationContext
            val intent = Intent(app, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(intent) else app.startService(intent)
        }
    }

    fun end(context: Context) {
        val stop = synchronized(this) { busy = maxOf(0, busy - 1); busy == 0 }
        if (stop) runCatching { context.applicationContext.stopService(Intent(context.applicationContext, KeepAliveService::class.java)) }
    }
}

class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Descargas", NotificationManager.IMPORTANCE_LOW))
            Notification.Builder(this, CHANNEL)
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        startForeground(1, builder
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("RetroStick")
            .setContentText("Descargando juegos")
            .setOngoing(true)
            .build())
        return START_NOT_STICKY
    }

    private companion object { const val CHANNEL = "downloads" }
}
