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

    /**
     * Manuals outside the collections, by console and loose title: "identifier::file" on archive.org.
     * "img::identifier::page|page|…" is a manual scanned as photos of its pages (one image each, in order).
     * Dragon Ball Final Bout: the US manual ("Dragon Ball GT: Final Bout") isn't in the collection; this is a scan of
     * it, page spreads as photos (the PDF of the translated Japanese one sits on a very slow server).
     */
    private val EXTRA = mapOf(
        "psx" to mapOf(
            "dragonballfinalbout" to "img::19-ff-3-a-08-8-ac-9-4-ced-8-c-14-983-dbbc-312-f-1::Front Cover|Pages 0-1|Pages 02-03|" +
                "Pages 04-05|Pages 06-07|Pages 08-09|Pages 10-11|Pages 12-13|Pages 14-15|Pages 16-17|Pages 18-19|Pages 20-21|" +
                "Pages 22-23|Pages 24-25|Pages 27",
        ),
    )

    fun isImages(name: String) = name.startsWith("img::")
    fun pages(name: String): List<String> = name.substringAfterLast("::").split('|')

    private val pageLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** Whether a page of an image manual is already on the stick. */
    fun hasPage(context: Context, console: Console, name: String, index: Int): Boolean {
        val id = name.removePrefix("img::").substringBefore("::")
        return File(context.filesDir, "manuals/${console.id}/$id/$index.jpg").let { it.isFile && it.length() > 0 }
    }

    /**
     * One page of an image manual, downloaded the first time (blocking). One download per page at a time: the
     * reader and the background prefetch may ask for the same page together.
     */
    fun pageFile(context: Context, console: Console, name: String, index: Int): File =
        synchronized(pageLocks.getOrPut("${console.id}|$name|$index") { Any() }) { downloadPage(context, console, name, index) }

    private fun downloadPage(context: Context, console: Console, name: String, index: Int): File {
        val id = name.removePrefix("img::").substringBefore("::")
        val page = pages(name)[index]
        val dir = File(context.filesDir, "manuals/${console.id}/$id").apply { mkdirs() }
        val target = File(dir, "$index.jpg")
        if (target.isFile && target.length() > 0) return target
        val conn = ShopApi.open("https://archive.org/download/$id/${URLEncoder.encode(page, "UTF-8").replace("+", "%20")}")
        val tmp = File(dir, "$index.jpg.part")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("archive.org respondió ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) throw IllegalStateException("No se pudo guardar la página")
        return target
    }

    /** Spanish Pokémon editions under their English names, as the (US) manuals are called. */
    private val ALIASES = listOf(
        "edicion rojo fuego" to "firered version", "edicion verde hoja" to "leafgreen version", "edicion rubi" to "ruby version",
        "edicion zafiro" to "sapphire version", "edicion esmeralda" to "emerald version", "edicion roja" to "red version",
        "edicion azul" to "blue version", "edicion amarilla" to "yellow version", "edicion oro" to "gold version",
        "edicion plata" to "silver version", "edicion cristal" to "crystal version",
    )

    /** Loose key of a title: "Legend of Zelda, The - Oracle of Ages (USA)" == "The Legend of Zelda - Oracle of Ages". */
    private fun key(name: String): String {
        var t = displayTitle(name.substringBeforeLast(".pdf")).lowercase()
        for ((es, en) in ALIASES) t = t.replace(es, en)
        return looseKey(t)
    }

    /** The manual's file name in its collection, or null (none, or the list isn't loaded yet). */
    fun find(context: Context, console: Console, baseName: String): String? {
        EXTRA[console.id]?.get(key(baseName))?.let { return it }
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
    fun file(context: Context, console: Console, name: String, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val dir = File(context.filesDir, "manuals/${console.id}").apply { mkdirs() }
        // "identifier::file" for the manuals outside the collections (EXTRA)
        val id = if ("::" in name) name.substringBefore("::") else COLLECTIONS.getValue(console.id)
        val file = name.substringAfter("::")
        val target = File(dir, file)
        if (target.isFile && target.length() > 0) return target
        val tmp = File(dir, "$file.part")
        val have = if (tmp.isFile) tmp.length() else 0L
        val conn = ShopApi.open("https://archive.org/download/$id/${URLEncoder.encode(file, "UTF-8").replace("+", "%20")}", if (have > 0) "bytes=$have-" else null)
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) throw IllegalStateException("archive.org respondió $code")
            val append = code == 206
            var done = if (append) have else 0L
            val total = done + conn.contentLengthLong.coerceAtLeast(0)
            conn.inputStream.use { input ->
                java.io.FileOutputStream(tmp, append).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        val now = System.currentTimeMillis()
                        if (now - last > 400) { last = now; progress(done, total) }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) throw IllegalStateException("No se pudo guardar el manual")
        return target
    }
}
