package com.retrostick.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * The ficha of each game (description, rating, year, genres, players, trailer id, screenshot URLs: api/igdb.ts)
 * kept on the stick as a small JSON (filesDir/fichas/<console>/<hash of the title>.json, 2–3 KB), so the home
 * screen shows it at once and without internet. The screenshots and the trailer's thumbnail go to CoverLoader's
 * disk cache. Every game on the stick gets its ficha in the background ([prefetch]) and keeps it; one IGDB
 * doesn't know is asked again after [RETRY_NONE_MS]. A failed request (no internet,
 * RetroShop not paired) isn't kept.
 */
object Fichas {
    class Entry(val info: IgdbInfo?, val at: Long) {
        /** Only "IGDB doesn't know it" is asked again; a ficha found is kept for good (retro games don't change). */
        val stale get() = info == null && System.currentTimeMillis() - at > RETRY_NONE_MS
    }

    private const val RETRY_NONE_MS = 24L * 60 * 60 * 1000

    private val memory = ConcurrentHashMap<String, Entry>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** Requests that failed this session (no internet, not paired): not retried for [RETRY_FAILED_MS] */
    private val failedAt = ConcurrentHashMap<String, Long>()
    private const val RETRY_FAILED_MS = 2L * 60 * 1000
    /** What the screen asks for: never waits behind the background prefetch */
    private val io = Executors.newSingleThreadExecutor()
    private val background = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }

    private fun key(console: String, title: String) = "$console|$title"

    private fun file(context: Context, console: String, title: String): File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(title.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(context.filesDir, "fichas/$console/$hash.json")
    }

    /** The kept ficha (memory, else the JSON on the stick), or null if there's none yet. */
    fun cached(context: Context, title: String, console: String): Entry? {
        val k = key(console, title)
        memory[k]?.let { return it }
        val f = file(context, console, title)
        if (!f.isFile) return null
        val e = runCatching { read(JSONObject(f.readText())) }.getOrNull() ?: return null
        memory[k] = e
        return e
    }

    /** Whether the last request for this ficha failed (the screen stops showing "loading"). */
    fun failed(title: String, console: String) = failedAt.containsKey(key(console, title))

    /** Fetches the ficha if there's none or it's old; listeners are told when it arrives (or when it fails). */
    fun request(context: Context, title: String, console: String) {
        val e = cached(context, title, console)
        if (e != null && !e.stale) return
        failedAt[key(console, title)]?.let { if (System.currentTimeMillis() - it < RETRY_FAILED_MS) return }
        val app = context.applicationContext
        io.execute { fetch(app, title, console); main.post { listeners.forEach { it() } } }
    }

    /**
     * Background: the ficha of every game on the stick, then its trailer thumbnail and screenshots into the
     * image cache (so the backdrop and the thumbnails show at once too).
     */
    fun prefetch(context: Context, games: List<Game>, covers: CoverLoader) {
        val app = context.applicationContext
        background.execute {
            var changed = false
            for (g in games) {
                val e = cached(app, g.title, g.console.id)
                if (e == null || e.stale) changed = fetch(app, g.title, g.console.id) || changed
                val info = cached(app, g.title, g.console.id)?.info ?: continue
                mediaUrls(info).forEach { covers.prefetchUrl(it) }
            }
            if (changed) main.post { listeners.forEach { it() } }
        }
    }

    /** Trailer thumbnail first, then the screenshots (what the home screen shows). */
    fun mediaUrls(info: IgdbInfo, max: Int = 5): List<String> =
        (listOfNotNull(info.trailerId?.let { "https://i.ytimg.com/vi/$it/mqdefault.jpg" }) + info.screenshots).take(max)

    /** When a game leaves the stick. */
    fun forget(context: Context, game: Game) {
        memory.remove(key(game.console.id, game.title))
        file(context, game.console.id, game.title).delete()
    }

    /** Blocking. True when a ficha (or "IGDB doesn't know it") was kept. */
    private fun fetch(context: Context, title: String, console: String): Boolean {
        val k = key(console, title)
        if (!inFlight.add(k)) return false
        try {
            val result = runCatching { ShopApi.igdbOrThrow(igdbTitle(title), console) }
            if (result.isFailure) { failedAt[k] = System.currentTimeMillis(); return false }
            failedAt.remove(k)
            val info = result.getOrNull()
            val e = Entry(info, System.currentTimeMillis())
            memory[k] = e
            val f = file(context, console, title)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".part")
            tmp.writeText(write(e).toString())
            tmp.renameTo(f)
            return true
        } finally {
            inFlight.remove(k)
        }
    }

    /** Spanish Pokémon releases: IGDB only knows them by their English name (Rojo Fuego → FireRed Version). */
    private val ALIASES = listOf(
        "Edicion Rojo Fuego" to "FireRed Version", "Edicion Verde Hoja" to "LeafGreen Version", "Edicion Rubi" to "Ruby Version",
        "Edicion Zafiro" to "Sapphire Version", "Edicion Esmeralda" to "Emerald Version", "Edicion Roja" to "Red Version",
        "Edicion Azul" to "Blue Version", "Edicion Amarilla" to "Yellow Version", "Edicion Oro" to "Gold Version",
        "Edicion Plata" to "Silver Version", "Edicion Cristal" to "Crystal Version",
    )

    fun igdbTitle(title: String) = ALIASES.fold(title) { t, (es, en) -> t.replace(es, en, ignoreCase = true) }

    private fun write(e: Entry) = JSONObject().apply {
        put("v", 2)
        put("at", e.at)
        val i = e.info
        put("found", i != null)
        if (i != null) {
            put("description", i.description ?: JSONObject.NULL)
            put("trailerId", i.trailerId ?: JSONObject.NULL)
            put("screenshots", JSONArray(i.screenshots))
            put("rating", i.rating ?: JSONObject.NULL)
            put("genres", JSONArray(i.genres))
            put("year", i.year ?: JSONObject.NULL)
            put("players", i.players ?: JSONObject.NULL)
        }
    }

    private fun read(j: JSONObject): Entry {
        val at = j.getLong("at")
        // "Not found" from before the English-name aliases (v1): asked again
        if (!j.optBoolean("found")) return Entry(null, if (j.optInt("v") < 2) 0L else at)
        fun str(n: String) = if (j.isNull(n)) null else j.optString(n).takeIf { it.isNotEmpty() }
        fun list(n: String) = j.optJSONArray(n)?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()
        return Entry(
            IgdbInfo(
                description = str("description"), trailerId = str("trailerId"), screenshots = list("screenshots"),
                rating = if (j.isNull("rating")) null else j.optDouble("rating").takeIf { !it.isNaN() },
                genres = list("genres"),
                year = if (j.isNull("year")) null else j.optInt("year").takeIf { it > 0 },
                players = if (j.isNull("players")) null else j.optInt("players").takeIf { it > 0 },
            ),
            at,
        )
    }
}
