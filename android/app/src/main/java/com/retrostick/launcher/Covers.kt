package com.retrostick.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Box art from libretro-thumbnails (README 7.5), cached on disk and in memory. */
class CoverLoader(context: Context) {
    private val dir = File(context.filesDir, "covers")
    private val memory = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // One thread: two loads of the same cover (shelf + hero) must not race on the same files.
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Calls [onLoaded] on the main thread with the cover, or null when there is none. */
    fun load(game: Game, sizePx: Int, onLoaded: (Bitmap?) -> Unit) {
        val memKey = "${game.key}@$sizePx"
        memory.get(memKey)?.let { onLoaded(it); return }
        executor.execute {
            val bitmap = coverFile(game)?.let { decode(it, sizePx) }
            if (bitmap != null) memory.put(memKey, bitmap)
            main.post { onLoaded(bitmap) }
        }
    }

    private fun coverFile(game: Game): File? {
        val consoleDir = File(dir, game.console.id).apply { mkdirs() }
        val local = File(consoleDir, "${game.baseName}.png")
        if (local.length() > 0) return local
        val missing = File(consoleDir, "${game.baseName}.none")
        if (missing.exists() && System.currentTimeMillis() - missing.lastModified() < RETRY_MISSING_MS) return null

        val names = boxartIndex(game.console)?.let { listOfNotNull(bestBoxart(game.baseName, it)) }
            ?: candidateNames(game.baseName)
        for (name in names) {
            when (download(game.console.thumbnailSystem, name, local)) {
                Result.OK -> return local
                Result.NOT_FOUND -> continue
                // Offline or server error: try again next time instead of settling for another variant.
                Result.ERROR -> return null
            }
        }
        missing.writeText("")
        return null
    }

    private enum class Result { OK, NOT_FOUND, ERROR }

    /** Box art names available for a console (cached for a week), or null when GitHub can't be reached. */
    private fun boxartIndex(console: Console): List<String>? {
        val cache = File(dir, "${console.id}/index.txt")
        if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < RETRY_MISSING_MS) {
            return cache.readLines()
        }
        val names = fetchIndex(console.thumbnailSystem)
            ?: return if (cache.exists()) cache.readLines() else null
        cache.writeText(names.joinToString("\n"))
        return names
    }

    private fun fetchIndex(system: String): List<String>? {
        val api = "https://api.github.com/repos/libretro-thumbnails/$system/git/trees"
        val root = getJson("$api/master") ?: return null
        val tree = root.getJSONArray("tree")
        val sha = (0 until tree.length()).map { tree.getJSONObject(it) }
            .firstOrNull { it.getString("path") == "Named_Boxarts" }?.getString("sha") ?: return null
        val boxarts = getJson("$api/$sha")?.getJSONArray("tree") ?: return null
        return (0 until boxarts.length()).map { boxarts.getJSONObject(it).getString("path") }
            .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }
    }

    private fun getJson(url: String): JSONObject? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "RetroStick-Launcher")
        if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().readText()) else null
    } catch (e: Exception) {
        null
    }

    private fun download(system: String, name: String, target: File): Result {
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val url = URL("https://raw.githubusercontent.com/libretro-thumbnails/$system/master/Named_Boxarts/$encoded.png")
        val tmp = File.createTempFile("cover", ".part", target.parentFile)
        return try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            when (conn.responseCode) {
                200 -> {
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.renameTo(target)) Result.OK else Result.ERROR
                }
                404 -> Result.NOT_FOUND
                else -> Result.ERROR
            }
        } catch (e: Exception) {
            Result.ERROR
        } finally {
            tmp.delete()
        }
    }

    private fun decode(file: File, sizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= sizePx && bounds.outHeight / (sample * 2) >= sizePx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val RETRY_MISSING_MS = 7L * 24 * 3600 * 1000

        private val EUROPEAN = Regex("""\((?:[^)]*[ ,])?(Europe|Spain)[,)]""")
        private val SPANISH = Regex("""\((?:[^)]*[ ,])?Spain[,)]""")
        private val UNRELEASED = Regex("""\((Beta|Proto|Demo|Sample|Kiosk)""", RegexOption.IGNORE_CASE)

        private fun safeName(baseName: String) = baseName.replace(Regex("""[&*/:`<>?\\|"]"""), "_")
        private fun titleOf(name: String) = name.replace(Regex("""\s*\(.*$"""), "").lowercase()

        /**
         * The user prefers European covers whenever one exists:
         * exact European name > Spanish release > other European > exact name > any other region.
         */
        fun bestBoxart(baseName: String, index: List<String>): String? {
            val safe = safeName(baseName)
            val title = titleOf(safe)
            return index.filter { titleOf(it) == title && (it == safe || !UNRELEASED.containsMatchIn(it)) }
                .minWithOrNull(compareBy<String>({
                    val euro = EUROPEAN.containsMatchIn(it)
                    when {
                        it == safe && euro -> 0
                        SPANISH.containsMatchIn(it) -> 1
                        euro -> 2
                        it == safe -> 3
                        else -> 4
                    }
                }, { it.length }))
        }

        /** Without the GitHub index: full name, without the language list, then common European/USA names. */
        fun candidateNames(baseName: String): List<String> {
            val safe = safeName(baseName)
            val noLanguages = safe.replace(Regex("""\s*\((?:[A-Z][a-z](?:-[A-Z][a-z])?,?)+\)"""), "")
            val title = safe.replace(Regex("""\s*\(.*$"""), "")
            return listOf(safe, noLanguages, "$title (Europe)", "$title (USA, Europe)", "$title (USA)").distinct()
        }
    }
}
