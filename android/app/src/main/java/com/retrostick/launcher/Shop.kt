package com.retrostick.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * RetroShop: the webapp's catalog (api/shop.ts) browsed on the TV. Games download straight into /ROMs/<console>
 * and the next Drive sync backs them up to Retro/JugandoAhora (DriveSync.syncRoms uploads files it didn't put
 * there), so they show up in the webapp too.
 */
class ShopGame(
    val title: String,
    val file: String,
    val size: Long,
    /** No-Intro name to save it under (no extension) */
    val name: String,
    /** Per-game archive holding the ROM ("ActRaiser (Europe).zip") */
    val pack: String?,
    val discs: List<Pair<String, Long>>,
    val rating: Double?,
    val genres: List<String>,
    val year: Int?,
    /** Box art URL chosen by the webapp (resized WebP), or null */
    val cover: String?,
) {
    val looseTitle = looseKey(title)
}

class ShopCatalog(
    val console: Console,
    val collection: String,
    val title: String,
    val restricted: Boolean,
    /** The .zip holding every ROM of the collection, if that's how it is stored */
    val archive: String?,
    val games: List<ShopGame>,
)

/** IGDB data shown in the ficha (same as the webapp). */
class IgdbInfo(val description: String?, val trailerId: String?, val screenshots: List<String>, val rating: Double?)

/** Same idea as the webapp's looseKey: lower case, no accents, letters and digits only. */
fun looseKey(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

/** Thrown when the webapp answers with its login page: the stick is not paired (or its pass was revoked). */
class NotPairedException : Exception("RetroShop no está vinculado")

/**
 * Pairing with the webapp account: the TV shows a QR with a code, the phone app leaves a pass in
 * Drive (Retro/Config/stick/pair-<code>.json, see api/pair.ts) and the stick keeps it.
 */
object ShopPairing {
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private fun prefs(context: Context) = context.getSharedPreferences("shop", Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString("token", null)
    fun forget(context: Context) = prefs(context).edit().remove("token").apply()

    fun newCode(): String {
        val random = java.security.SecureRandom()
        return String(CharArray(8) { ALPHABET[random.nextInt(ALPHABET.length)] })
    }

    /** QR of the pairing link, drawn from the module matrix of api/qr.ts (white quiet zone included). */
    fun qrBitmap(code: String, sizePx: Int): android.graphics.Bitmap {
        val conn = ShopApi.open("${BuildConfig.WEBAPP_URL}/api/qr?code=$code")
        val json = try {
            if (conn.responseCode != 200) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        val rows = json.getJSONArray("rows")
        val n = json.getInt("size")
        val quiet = 2
        val cell = maxOf(1, sizePx / (n + quiet * 2))
        val side = cell * (n + quiet * 2)
        val bmp = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { color = android.graphics.Color.BLACK }
        for (r in 0 until n) {
            val row = rows.getString(r)
            for (c in 0 until n) if (row[c] == '1') {
                val x = ((c + quiet) * cell).toFloat()
                val y = ((r + quiet) * cell).toFloat()
                canvas.drawRect(x, y, x + cell, y + cell, paint)
            }
        }
        return bmp
    }

    /** Blocking: looks for the pass the phone left for [code]; keeps it and deletes the file. */
    fun poll(context: Context, code: String): Boolean {
        val drive = DriveClient(DriveAuth(context))
        var dir = "root"
        for (name in listOf("Retro", "Config", "stick")) dir = drive.folder(dir, name)
        val file = drive.list(dir).firstOrNull { it.name == "pair-$code.json" } ?: return false
        val tmp = File(context.cacheDir, "pair.json")
        drive.download(file.id, tmp)
        val token = JSONObject(tmp.readText()).getString("token")
        tmp.delete()
        prefs(context).edit().putString("token", token).apply()
        runCatching { drive.delete(file.id) }
        return true
    }
}

object ShopApi {
    /** Pass sent with every request to the webapp (set once paired). */
    @Volatile var token: String? = null

    private fun cacheFile(context: Context, console: Console) = File(context.cacheDir, "shop-${console.id}.json")

    /** Last catalog downloaded for the console, shown at once while a fresh one loads. */
    fun cached(context: Context, console: Console): ShopCatalog? =
        runCatching { parse(console, cacheFile(context, console).readText()) }.getOrNull()

    /** Blocking: downloads the catalog from the webapp and keeps a copy. */
    fun fetch(context: Context, console: Console): ShopCatalog {
        val conn = open("${BuildConfig.WEBAPP_URL}/api/shop?console=${console.id}")
        val text = try {
            if (conn.responseCode != 200) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
            // The login page instead of JSON: the pass is missing or no longer valid.
            if (conn.contentType?.startsWith("application/json") != true) throw NotPairedException()
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val catalog = parse(console, text)
        cacheFile(context, console).writeText(text)
        return catalog
    }

    /** The webapp ficha data for a title (api/igdb.ts): description, trailer, screenshots; null if IGDB has none. */
    fun igdb(title: String): IgdbInfo? {
        val conn = open("${BuildConfig.WEBAPP_URL}/api/igdb?title=${URLEncoder.encode(title, "UTF-8")}")
        val json = try {
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        if (json.optBoolean("found", true).not() || json.has("error")) return null
        val shots = json.optJSONArray("screenshots")
        return IgdbInfo(
            description = json.optString("description").takeIf { it.isNotEmpty() && it != "null" },
            trailerId = json.optString("trailerId").takeIf { it.isNotEmpty() && it != "null" },
            screenshots = if (shots == null) emptyList() else List(shots.length()) { shots.getString(it) },
            rating = if (json.isNull("rating")) null else json.optDouble("rating").takeIf { !it.isNaN() },
        )
    }

    fun open(url: String, range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            if (url.startsWith(BuildConfig.WEBAPP_URL)) token?.let { setRequestProperty("x-stick-token", it) }
            range?.let { setRequestProperty("Range", it) }
        }

    private fun parse(console: Console, text: String): ShopCatalog {
        val json = JSONObject(text)
        val genres = json.getJSONArray("genres").let { a -> List(a.length()) { a.getString(it) } }
        val list = json.getJSONArray("games")
        val games = List(list.length()) { i ->
            val g = list.getJSONObject(i)
            val discs = g.optJSONArray("d")?.let { d ->
                List(d.length()) { d.getJSONObject(it).let { x -> x.getString("f") to x.getLong("s") } }
            }.orEmpty()
            val gi = g.optJSONArray("g")
            ShopGame(
                title = g.getString("t"),
                file = g.getString("f"),
                size = g.getLong("s"),
                name = g.getString("n"),
                pack = g.optString("p").takeIf { it.isNotEmpty() },
                discs = discs,
                rating = if (g.has("r")) g.getDouble("r") else null,
                genres = gi?.let { a -> List(a.length()) { genres.getOrElse(a.getInt(it)) { "" } } }.orEmpty().filter { it.isNotEmpty() },
                year = if (g.has("y")) g.getInt("y") else null,
                cover = g.optString("c").takeIf { it.isNotEmpty() },
            )
        }
        return ShopCatalog(
            console, json.getString("collection"), json.optString("title"), json.optBoolean("restricted"),
            json.optString("archive").takeIf { it.isNotEmpty() && it != "null" }, games,
        )
    }
}

object ShopDownloads {
    enum class State { QUEUED, RUNNING, DONE, ERROR }

    class Job(val catalog: ShopCatalog, val game: ShopGame) {
        @Volatile var state = State.QUEUED
        @Volatile var done = 0L
        @Volatile var total = game.size
        @Volatile var error: String? = null
        val progress get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private const val CHUNK = 8L * 1024 * 1024
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    val jobs = CopyOnWriteArrayList<Job>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = main.post { listeners.forEach { it() } }

    fun jobFor(game: ShopGame) = jobs.lastOrNull { it.game === game || (it.game.name == game.name && it.game.file == game.file) }
    val active get() = jobs.firstOrNull { it.state == State.RUNNING } ?: jobs.firstOrNull { it.state == State.QUEUED }

    fun enqueue(context: Context, catalog: ShopCatalog, game: ShopGame) {
        jobFor(game)?.let { if (it.state == State.QUEUED || it.state == State.RUNNING) return }
        val job = Job(catalog, game)
        jobs += job
        notifyListeners()
        val app = context.applicationContext
        executor.execute {
            job.state = State.RUNNING
            notifyListeners()
            try {
                download(job)
                job.state = State.DONE
                // Backs the new ROM up to Retro/JugandoAhora: the webapp lists it as "en el stick".
                main.post { DriveSync.sync(app) }
            } catch (e: Exception) {
                Log.w("RetroStick", "RetroShop download failed", e)
                job.error = e.message ?: e.javaClass.simpleName
                job.state = State.ERROR
            }
            notifyListeners()
        }
    }

    private fun download(job: Job) {
        val catalog = job.catalog
        val game = job.game
        val dir = catalog.console.romDir.apply { mkdirs() }
        if (game.discs.isNotEmpty()) {
            // PS1 multi-disc: every disc keeps its name, a .m3u ties them together (as the webapp does).
            job.total = game.discs.sumOf { it.second }
            var before = 0L
            for ((file, size) in game.discs) {
                fetchTo(catalog, file, size, File(dir, file.substringAfterLast('/'))) { job.done = before + it; tick() }
                before += size
            }
            val names = game.discs.map { it.first.substringAfterLast('/') }
            val m3u = names.first().replace(Regex("""\s*\(Disc \d+\)""", RegexOption.IGNORE_CASE), "").substringBeforeLast('.') + ".m3u"
            File(dir, m3u).writeText(names.joinToString("\n", postfix = "\n"))
            return
        }
        if (game.pack != null) {
            // One archive per game: the ROM inside is read from archive.org's listing of it.
            val inner = packedRom(catalog, game.pack)
            val ext = inner.substringAfterLast('.').lowercase()
            val url = "https://archive.org/download/${enc(catalog.collection)}/${encPath(game.pack)}/${encPath(inner)}"
            get(url, File(dir, "${game.name}.$ext")) { job.done = it; tick() }
            return
        }
        val ext = game.file.substringAfterLast('.').lowercase()
        val target = File(dir, if (catalog.console.id == "psx") game.file.substringAfterLast('/') else "${game.name}.$ext")
        fetchTo(catalog, game.file, game.size, target) { job.done = it; tick() }
    }

    private var lastTick = 0L
    private fun tick() {
        val now = System.currentTimeMillis()
        if (now - lastTick > 400) {
            lastTick = now
            notifyListeners()
        }
    }

    /** A file of the collection: inside its .zip, straight from archive.org, or (restricted items) through the webapp. */
    private fun fetchTo(catalog: ShopCatalog, file: String, size: Long, target: File, progress: (Long) -> Unit) {
        val id = enc(catalog.collection)
        when {
            catalog.archive != null -> get("https://archive.org/download/$id/${encPath(catalog.archive)}/${encPath(file)}", target, progress)
            catalog.restricted -> viaWebapp(catalog, file, size, target, progress)
            else -> get("https://archive.org/download/$id/${encPath(file)}", target, progress)
        }
    }

    private fun get(url: String, target: File, progress: (Long) -> Unit) {
        val tmp = File(target.parentFile, ".${target.name}.part")
        val conn = ShopApi.open(url)
        try {
            if (conn.responseCode != 200) throw IllegalStateException("archive.org respondió ${conn.responseCode}")
            var n = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        n += r
                        progress(n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        finish(tmp, target)
    }

    /** Restricted items (PS1 set): 8 MiB pieces through api/download.ts, which adds the archive.org keys. */
    private fun viaWebapp(catalog: ShopCatalog, file: String, size: Long, target: File, progress: (Long) -> Unit) {
        val tmp = File(target.parentFile, ".${target.name}.part")
        val url = "${BuildConfig.WEBAPP_URL}/api/download?id=${enc(catalog.collection)}&file=${enc(file)}"
        RandomAccessFile(tmp, "rw").use { out ->
            out.setLength(0)
            var start = 0L
            while (start < size) {
                val end = minOf(start + CHUNK, size) - 1
                var attempt = 0
                while (true) {
                    try {
                        val conn = ShopApi.open(url, "bytes=$start-$end")
                        try {
                            if (conn.responseCode != 206) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
                            val data = conn.inputStream.use { it.readBytes() }
                            if (data.size.toLong() != end - start + 1) throw IllegalStateException("Trozo incompleto")
                            out.seek(start)
                            out.write(data)
                        } finally {
                            conn.disconnect()
                        }
                        break
                    } catch (e: Exception) {
                        if (++attempt >= 4) throw e
                        Thread.sleep(3000L * attempt)
                    }
                }
                start = end + 1
                progress(start)
            }
        }
        finish(tmp, target)
    }

    private fun finish(tmp: File, target: File) {
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IllegalStateException("No se pudo guardar ${target.name}")
        }
    }

    private fun packedRom(catalog: ShopCatalog, pack: String): String {
        val conn = ShopApi.open("https://archive.org/download/${enc(catalog.collection)}/${encPath(pack)}/")
        val html = try {
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val names = Regex("""<tr><td><a\b[^>]*>([^<]*)</a>""").findAll(html).map { decode(it.groupValues[1]) }.toList()
        return names.firstOrNull { it.substringAfterLast('.').lowercase() in catalog.console.extensions }
            ?: throw IllegalStateException("$pack no contiene ninguna ROM")
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'").replace("&apos;", "'")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun encPath(s: String) = s.split('/').joinToString("/") { enc(it) }
}
