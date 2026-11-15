package com.retrostick.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.InputDevice
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Enviar informe» (Retro Ajustes): what this stick or TV is and how it's set up, for the admin to see in the webapp
 * (api/report.ts) when something goes wrong on a friend's TV that isn't at hand. Never any password or pass: only
 * versions, files that exist, pads, space and the launcher's own recent warnings.
 */
object Report {

    /** The device's short name, as listed in the webapp. */
    fun device() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"

    /** Blocking: everything the report says, as plain text. */
    fun build(context: Context): String = buildString {
        fun line(k: String, v: Any?) = appendLine("$k: ${v ?: "—"}")
        fun section(t: String) = appendLine().appendLine("## $t")
        val pm = context.packageManager

        appendLine("# Informe de RetroStick · ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale("es", "ES")).format(Date())}")
        section("Aparato")
        line("Modelo", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        line("Android", "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("OpenGL ES", (context.getSystemService(android.app.ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion shr 16))
        line("Pantalla", context.resources.displayMetrics.let { "${it.widthPixels}x${it.heightPixels}" } + " · " +
            "%.2f Hz".format(Locale.US, (context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(0)?.refreshRate ?: 0f)))
        val home = pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        line("Pantalla de inicio", home)
        line("Red", StickStatus.network(context))
        line("Libre", "%.1f GB".format(Locale.US, StickStatus.freeBytes() / 1e9))

        section("Apps")
        line("RetroStick", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        line("RetroArch", appVersion(context, RETROARCH_PACKAGE))
        line("RetroStick Núcleos", appVersion(context, Setup.CORES_PACKAGE))

        section("Instalación")
        val setup = context.getSharedPreferences("setup", Context.MODE_PRIVATE)
        line("Asistente", setup.all.entries.joinToString(", ") { "${it.key}=${it.value}" }.ifEmpty { "sin datos" })
        line("Falta configurar", Setup.needed(context))
        line("Permiso de almacenamiento", context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        line("retroarch.cfg", File(RETROARCH_CFG).let { if (it.isFile) "${it.length()} bytes" else "no está" })
        for (path in listOf("RetroArch/config/FCEUmm/nes.cfg", "RetroArch/playlists", "RetroArch/autoconfig/android"))
            line(path, File(SDCARD, path).let { if (it.isDirectory) "carpeta (${it.list()?.size ?: 0})" else if (it.isFile) "${it.length()} bytes" else "no está" })
        line("Perfiles de mando", File(SDCARD, "RetroArch/autoconfig/android").list()?.sorted()?.joinToString(" | "))
        line("BIOS", File(SDCARD, "RetroArch/system").walkTopDown().maxDepth(2).filter { it.isFile }.map { it.name }.sorted().joinToString(", ").ifEmpty { null })
        runCatching { File(RETROARCH_CFG).readLines() }.getOrNull()?.let { cfg ->
            val keys = setOf("video_driver", "video_refresh_rate", "input_driver", "input_joypad_driver", "audio_driver", "libretro_directory",
                "system_directory", "savefile_directory", "savestate_directory", "cheevos_enable", "input_enable_hotkey_btn", "menu_driver")
            for (l in cfg) Regex("""^(\S+) = "(.*)"$""").find(l.trim())?.let { if (it.groupValues[1] in keys) line("  ${it.groupValues[1]}", it.groupValues[2]) }
        }

        section("Mandos")
        val pads = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }.filter { dev -> !dev.isVirtual }
        if (pads.isEmpty()) appendLine("ninguno")
        for (d in pads) {
            val pad = StickStatus.isGamepad(d)
            appendLine("- ${d.name} · %04x:%04x".format(d.vendorId, d.productId) +
                (if (pad) " · mando · botones ${if (StickStatus.viaGenericKl(d)) "Generic.kl (se corrigen)" else "estándar"}" else ""))
        }

        section("Cuentas")
        line("Google Drive", if (DriveAuth(context).isConnected) "conectado" else "no conectado")
        line("RetroShop", if (ShopPairing.token(context) != null) "vinculado" else "sin vincular")
        line("RetroAchievements", if (RetroAchievements.account(context) != null) "con cuenta" else "sin cuenta")

        section("Juegos en el stick")
        runCatching { Library(context).scan() }.getOrNull()?.forEach { (console, games) -> if (games.isNotEmpty()) line("$console", games.size) }

        // RetroArch writes a log only if logging to a file is on; the newest one, its end
        File(SDCARD, "RetroArch/logs").listFiles()?.maxByOrNull { it.lastModified() }?.let { log ->
            section("Registro de RetroArch (${log.name}, final)")
            runCatching { log.readLines().takeLast(150) }.getOrNull()?.forEach { appendLine(it) }
        }

        section("Avisos recientes de RetroStick")
        runCatching {
            val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "400", "--pid=${android.os.Process.myPid()}", "*:W"))
            p.inputStream.bufferedReader().readLines().takeLast(250)
        }.getOrNull()?.forEach { appendLine(it) }
    }

    /** Blocking: sends the report; its code, to say to the admin. */
    fun send(context: Context): String {
        val body = JSONObject().put("device", device()).put("text", build(context)).toString().toByteArray()
        val conn = ShopApi.open("${BuildConfig.WEBAPP_URL}/api/report")
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode == 401 || conn.contentType?.startsWith("application/json") != true) throw NotPairedException()
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            return reply.optString("id").ifEmpty { throw IllegalStateException(reply.optString("error", "La webapp no lo guardó")) }
        } finally {
            conn.disconnect()
        }
    }

    private fun appVersion(context: Context, pkg: String): String? = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(pkg, 0).let { "${it.versionName} (${it.versionCode})" }
    }.getOrNull()
}
