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

/** Same idea as the webapp's looseKey: lower case, no accents, letters and digits only. */
fun looseKey(s: String): String =
    Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

object ShopApi {
    private fun cacheFile(context: Context, console: Console) = File(context.cacheDir, "shop-${console.id}.json")

    /** Last catalog downloaded for the console, shown at once while a fresh one loads. */
    fun cached(context: Context, console: Console): ShopCatalog? =
        runCatching { parse(console, cacheFile(context, console).readText()) }.getOrNull()

    /** Blocking: downloads the catalog from the webapp and keeps a copy. */
    fun fetch(context: Context, console: Console): ShopCatalog {
        val conn = open("${BuildConfig.WEBAPP_URL}/api/shop?console=${console.id}")
        val text = try {
            if (conn.responseCode != 200) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
            if (conn.contentType?.startsWith("application/json") != true) throw IllegalStateException("La clave del stick no es válida")
            conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
        val catalog = parse(console, text)
        cacheFile(context, console).writeText(text)
        return catalog
    }

    fun open(url: String, range: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            if (url.startsWith(BuildConfig.WEBAPP_URL)) setRequestProperty("x-stick-key", BuildConfig.STICK_KEY)
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
