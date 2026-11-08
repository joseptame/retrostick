package com.retrostick.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Scanned game manuals (PDF) from archive.org's manual collections, named like No-Intro ("Spider-Man (USA).pdf"),
 * mostly the US editions. A game's manual is the one whose title matches (regions aside). The list of each
 * collection is read once a week and kept; a manual is downloaded the first time it's opened and kept.
 */
object Manuals {
    private val COLLECTIONS = mapOf(
        "nes" to "NESManuals", "snes" to "SNESManuals", "gb" to "NintendoGameBoyManuals",
        "gbc" to "NintendoGameBoyColorManuals", "gba" to "NintendoGameBoyAdvanceManuals", "psx" to "SonyPlaystationManuals",
    )
    private const val WEEK = 7 * 24 * 3600_000L

    /** Manual file name by loose title, per console. */
    @Volatile private var index: Map<String, Map<String, String>> = emptyMap()
    @Volatile private var loading = false
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Loose key of a title: "Legend of Zelda, The - Oracle of Ages (USA)" == "The Legend of Zelda - Oracle of Ages". */
    private fun key(name: String) = looseKey(displayTitle(name.substringBeforeLast(".pdf")))

    /** The manual's file name in its collection, or null (none, or the list isn't loaded yet). */
    fun find(context: Context, console: Console, baseName: String): String? {
        if (index.isEmpty()) load(context)
        return index[console.id]?.get(key(baseName))
    }

    /** Reads the cached lists, and refreshes them from archive.org when older than a week. */
    fun load(context: Context) {
        if (loading) return
        loading = true
        val app = context.applicationContext
        io.execute {
            try {
                val cache = File(app.filesDir, "manuals-index.json")
                var json = runCatching { JSONObject(cache.readText()) }.getOrNull()
                if (json != null) publish(json)
                if (json == null || System.currentTimeMillis() - json.optLong("at") > WEEK) {
                    val fresh = JSONObject().put("at", System.currentTimeMillis())
                    for ((console, id) in COLLECTIONS) {
                        val names = runCatching { listPdfs(id) }.onFailure { Log.w("RetroStick", "Manuals $id", it) }.getOrNull()
                        val old = json?.optJSONArray(console)
                        fresh.put(console, names?.let { JSONArray(it) } ?: old ?: JSONArray())
                    }
                    cache.writeText(fresh.toString())
                    json = fresh
                    publish(json)
                }
            } catch (e: Exception) {
                Log.w("RetroStick", "Manual index failed", e)
            } finally {
                loading = false
            }
        }
    }

    private fun publish(json: JSONObject) {
        index = COLLECTIONS.keys.associateWith { console ->
            val arr = json.optJSONArray(console) ?: JSONArray()
            val out = HashMap<String, String>()
            for (i in 0 until arr.length()) {
                val name = arr.getString(i)
                // First one wins: the plain scan rather than a "(Rev 1)" duplicate of the same title
                out.putIfAbsent(key(name), name)
            }
            out
        }
        main.post { listeners.toList().forEach { it() } }
    }

    private fun listPdfs(id: String): List<String> {
        val conn = ShopApi.open("https://archive.org/metadata/$id/files")
        val text = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val files = JSONObject(text).getJSONArray("result")
        return (0 until files.length()).map { files.getJSONObject(it).getString("name") }
            .filter { it.endsWith(".pdf", true) && !it.endsWith("_text.pdf", true) && '/' !in it }
            .sorted()
    }

    /** The manual on disk, downloaded the first time (blocking). */
    fun file(context: Context, console: Console, name: String): File {
        val dir = File(context.filesDir, "manuals/${console.id}").apply { mkdirs() }
        val target = File(dir, name)
        if (target.isFile && target.length() > 0) return target
        val id = COLLECTIONS.getValue(console.id)
        val conn = ShopApi.open("https://archive.org/download/$id/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}")
        val tmp = File(dir, "$name.part")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("archive.org respondió ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) throw IllegalStateException("No se pudo guardar el manual")
        return target
    }
}
