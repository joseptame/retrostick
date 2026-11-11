package com.retrostick.launcher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Copia del stick (Retro Ajustes → Cuenta de Drive → Copia del stick): everything that isn't a game or a save,
 * zipped to Drive's Retro/Copias, to bring a new or reset stick back as it was.
 *  - The launcher's own settings: home tabs and view, favourites, sounds, boot screens (prefs.json, typed values).
 *  - RetroArch: retroarch.cfg and its config folder (core options, overrides, button remaps incl. Retro Ajustes →
 *    Botones), and the controller profiles (autoconfig).
 * Left out on purpose: Drive's and RetroShop's tokens (signing in again is safer than copying them), the saves,
 * finished games and the RetroAchievements account (DriveSync and Retro/Config already keep those in Drive),
 * the BIOS (Retro/Config/bios) and the games.
 * Once a week by itself (after a sync) and on demand; the last [KEEP] stay, older ones go to Drive's bin.
 */
object Backup {
    private const val FOLDER = "Retro/Copias"
    private const val KEEP = 4
    private const val WEEK = 7L * 24 * 3600_000
    private val PREFS = listOf("home", "library", "sounds", "retrostick")
    private val DIRS = listOf("RetroArch/config", "RetroArch/autoconfig")
    private const val RA_CFG_ENTRY = "retroarch.cfg"

    private fun state(context: Context) = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    fun lastAt(context: Context) = state(context).getLong("lastAt", 0)

    /** A copy in Drive: "RetroStick-copia-2026-11-11-2215.zip". */
    class Copy(val id: String, val name: String, val size: Long, val at: Long)

    private fun drive(context: Context): DriveClient? {
        val auth = DriveAuth(context)
        return if (auth.isConfigured && auth.isConnected) DriveClient(auth) else null
    }

    /** The copies in Drive, newest first. Blocking. */
    fun list(context: Context): List<Copy> {
        val drive = drive(context) ?: return emptyList()
        val folder = DriveSync.folderId(context, drive, FOLDER)
        return drive.list(folder).filter { !it.isFolder && it.name.endsWith(".zip") }
            .map { Copy(it.id, it.name, it.size, it.modified) }.sortedByDescending { it.at }
    }

    /** Makes a copy now and uploads it (blocking); older ones beyond [KEEP] go to Drive's bin. */
    fun makeNow(context: Context) {
        val drive = drive(context) ?: error("Drive no está conectado")
        val app = context.applicationContext
        val zip = File(app.cacheDir, "RetroStick-copia-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip")
        try {
            write(app, zip)
            val folder = DriveSync.folderId(app, drive, FOLDER)
            drive.upload(zip, folder, null)
            list(app).drop(KEEP).forEach { runCatching { drive.trash(it.id) } }
            state(app).edit().putLong("lastAt", System.currentTimeMillis()).apply()
        } finally {
            zip.delete()
        }
    }

    /** The weekly copy, after a sync: only once the new-stick question has been answered (see [shouldOffer]). */
    fun maybeWeekly(context: Context) {
        val app = context.applicationContext
        if (!state(app).getBoolean("offered", false) || System.currentTimeMillis() - lastAt(app) < WEEK) return
        DriveSync.runExclusive { runCatching { makeNow(app) }.onFailure { Log.w("RetroStick", "Backup", it) } }
    }

    /**
     * A new or reset stick (this install never made or restored a copy) with copies in Drive: the newest, to offer.
     * Asked once; then the weekly copies start. Blocking.
     */
    fun shouldOffer(context: Context): Copy? {
        val s = state(context)
        if (s.getBoolean("offered", false)) return null
        if (drive(context) == null) return null
        val copy = runCatching { list(context).firstOrNull() }.getOrNull()
        if (copy == null || lastAt(context) > 0) answered(context)
        return copy.takeIf { lastAt(context) == 0L }
    }

    fun answered(context: Context) = state(context).edit().putBoolean("offered", true).apply()

    /** Downloads [copy] and puts everything back (blocking). RetroArch must not be running. */
    fun restore(context: Context, copy: Copy) {
        val drive = drive(context) ?: error("Drive no está conectado")
        val app = context.applicationContext
        val zip = File(app.cacheDir, copy.name)
        try {
            drive.download(copy.id, zip)
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                while (true) {
                    val e = input.nextEntry ?: break
                    if (e.isDirectory) continue
                    val bytes = input.readBytes()
                    when {
                        e.name == "prefs.json" -> restorePrefs(app, JSONObject(String(bytes)))
                        e.name == RA_CFG_ENTRY -> File(RETROARCH_CFG).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                        e.name.startsWith("sd/") && ".." !in e.name -> File(SDCARD, e.name.removePrefix("sd/")).apply { parentFile?.mkdirs() }.writeBytes(bytes)
                    }
                }
            }
            state(app).edit().putLong("lastAt", copy.at).putBoolean("offered", true).apply()
        } finally {
            zip.delete()
        }
    }

    private fun write(context: Context, zip: File) {
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            fun put(name: String, bytes: ByteArray) { out.putNextEntry(ZipEntry(name)); out.write(bytes); out.closeEntry() }
            put("prefs.json", prefsJson(context).toString().toByteArray())
            File(RETROARCH_CFG).takeIf { it.isFile }?.let { put(RA_CFG_ENTRY, it.readBytes()) }
            DIRS.forEach { dir ->
                File(SDCARD, dir).walkTopDown().filter { it.isFile && it.length() < 2 * 1024 * 1024 }.forEach { f ->
                    put("sd/" + f.relativeTo(File(SDCARD)).path.replace('\\', '/'), f.readBytes())
                }
            }
        }
    }

    /** {"home": {"list": {"t": "b", "v": false}, …}, …}: each value with its type, so it comes back the same. */
    private fun prefsJson(context: Context): JSONObject {
        val out = JSONObject()
        PREFS.forEach { name ->
            val values = JSONObject()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                val typed = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                }
                typed?.let { values.put(k, it) }
            }
            out.put(name, values)
        }
        return out
    }

    private fun restorePrefs(context: Context, json: JSONObject) {
        json.keys().forEach { name ->
            if (name !in PREFS) return@forEach
            val values = json.getJSONObject(name)
            val edit = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.keys().forEach { k ->
                val o = values.getJSONObject(k)
                when (o.optString("t")) {
                    "b" -> edit.putBoolean(k, o.getBoolean("v"))
                    "i" -> edit.putInt(k, o.getInt("v"))
                    "l" -> edit.putLong(k, o.getLong("v"))
                    "f" -> edit.putFloat(k, o.getDouble("v").toFloat())
                    "s" -> edit.putString(k, o.getString("v"))
                    "set" -> o.getJSONArray("v").let { a -> edit.putStringSet(k, (0 until a.length()).map(a::getString).toSet()) }
                }
            }
            edit.commit()
        }
    }
}
