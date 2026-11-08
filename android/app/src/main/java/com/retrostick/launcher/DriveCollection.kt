package com.retrostick.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Games in the user's Drive collection (Retro/Coleccion/<console>) that are not on the stick: the home screen
 * shows them after the stick's own games, and the ficha can pass one to the stick (moved to JugandoAhora,
 * as the webapp does, and downloaded right away with its progress).
 */
object DriveCollection {
    class Item(val console: Console, val name: String, val size: Long) {
        val baseName = name.substringBeforeLast('.')
        val title = displayTitle(baseName)
        val key get() = "${console.id}/$name"
        /** A Game named like the file, for covers, saves and play time (kept on the stick). */
        fun probe() = Game(console, File(console.romDir, name))
    }

    /** Raw listing of each console's collection folder (file name -> size), last one read. */
    @Volatile private var listing: Map<String, Map<String, Long>> = emptyMap()
    @Volatile private var lastRefresh = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun notifyListeners() = main.post { listeners.toList().forEach { it() } }

    /** Games of [console] in the collection and not on the stick, by title. Runs on any thread. */
    fun items(context: Context, console: Console): List<Item> {
        if (listing.isEmpty()) listing = loadCache(context)
        val files = listing[console.id].orEmpty()
        val onStick = console.romDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        // Discs listed in a .m3u ("Game (Disc 1).chd" next to "Game.m3u") are reached through it.
        val playlists = files.keys.filter { it.endsWith(".m3u", true) }.map { it.substringBeforeLast('.') }.toSet()
        return files.filter { (name, _) ->
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.')
            ext in console.extensions && name !in onStick &&
                (ext == "m3u" || DISC.replace(base, "").trim() !in playlists)
        }.map { (name, size) -> Item(console, name, size) }.sortedBy { it.title.lowercase() }
    }

    fun find(context: Context, console: Console, name: String) = items(context, console).firstOrNull { it.name == name }

    /** Re-reads the collection folders in Drive (at most once a minute unless [force]). */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000) return
        lastRefresh = now
        io.execute {
            try {
                val auth = DriveAuth(app)
                if (!auth.isConfigured || !auth.isConnected) {
                    listing = emptyMap()
                } else {
                    val drive = DriveClient(auth)
                    listing = CONSOLES.associate { c ->
                        val id = DriveSync.folderId(app, drive, "Retro/Coleccion/${c.id}")
                        c.id to drive.list(id).filter { !it.isFolder }.associate { it.name to it.size }
                    }
                }
                saveCache(app)
                notifyListeners()
            } catch (e: Exception) {
                Log.w("RetroStick", "Drive collection refresh failed", e)
                lastRefresh = 0
            }
        }
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "drive-collection.json")

    private fun saveCache(context: Context) {
        val json = JSONObject()
        listing.forEach { (console, files) ->
            json.put(console, JSONArray().apply { files.forEach { (n, s) -> put(JSONObject().put("n", n).put("s", s)) } })
        }
        runCatching { cacheFile(context).writeText(json.toString()) }
    }

    private fun loadCache(context: Context): Map<String, Map<String, Long>> = runCatching {
        val json = JSONObject(cacheFile(context).readText())
        json.keys().asSequence().associateWith { console ->
            val arr = json.getJSONArray(console)
            (0 until arr.length()).associate { arr.getJSONObject(it).let { o -> o.getString("n") to o.getLong("s") } }
        }
    }.getOrDefault(emptyMap())

    // ---- Passing a game to the stick ----

    enum class State { RUNNING, DONE, ERROR }

    class Job(val item: Item) {
        @Volatile var state = State.RUNNING
        @Volatile var done = 0L
        @Volatile var total = item.size
        @Volatile var error: String? = null
        val progress get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    private val jobs = mutableMapOf<String, Job>()

    fun jobFor(item: Item): Job? = synchronized(jobs) { jobs[item.key] }

    fun jobsDone(): Int = synchronized(jobs) { jobs.values.count { it.state == State.DONE } }

    /**
     * Downloads the game (and the tracks / discs its .cue or .m3u lists) into /ROMs/<console>, then moves each
     * file from Coleccion to JugandoAhora: the stick keeps it in sync from then on. Runs on the sync thread.
     */
    fun passToStick(context: Context, item: Item) {
        val app = context.applicationContext
        val job = synchronized(jobs) {
            jobs[item.key]?.takeIf { it.state == State.RUNNING }?.let { return }
            Job(item).also { jobs[item.key] = it }
        }
        notifyListeners()
        DriveSync.runExclusive {
            try {
                val c = item.console
                val drive = DriveClient(DriveAuth(app))
                val collection = DriveSync.folderId(app, drive, "Retro/Coleccion/${c.id}")
                val playing = DriveSync.folderId(app, drive, "Retro/JugandoAhora/${c.id}")
                val inCollection = drive.list(collection).filter { !it.isFolder }.associateBy { it.name }
                val inPlaying = drive.list(playing).filter { !it.isFolder }.associateBy { it.name }
                c.romDir.mkdirs()
                val queue = ArrayDeque(listOf(item.name))
                val seen = mutableSetOf<String>()
                var before = 0L
                var lastNotify = 0L
                while (queue.isNotEmpty()) {
                    val name = queue.removeFirst()
                    if (!seen.add(name)) continue
                    val remote = inCollection[name] ?: inPlaying[name]
                        ?: throw IllegalStateException("Falta $name en tu Drive")
                    if (name != item.name) job.total += remote.size
                    val target = File(c.romDir, name)
                    if (!(target.isFile && target.length() == remote.size)) {
                        drive.download(remote.id, target) { bytes ->
                            job.done = before + bytes
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 500) { lastNotify = now; notifyListeners() }
                        }
                    }
                    before += remote.size
                    job.done = before
                    if (inCollection[name] != null && inPlaying[name] == null) drive.move(remote.id, playing, collection)
                    DriveSync.markDownloaded(app, c.id, name)
                    references(target).forEach { queue.addLast(it) }
                }
                job.state = State.DONE
                listing = listing + (c.id to listing[c.id].orEmpty().filterKeys { it !in seen })
                saveCache(app)
            } catch (e: Exception) {
                Log.w("RetroStick", "Pass ${item.name} to the stick failed", e)
                job.error = e.message ?: e.toString()
                job.state = State.ERROR
            }
            notifyListeners()
        }
    }

    /** Files a .m3u (discs) or .cue (tracks) points to, by name. */
    private fun references(file: File): List<String> = when (file.extension.lowercase()) {
        "m3u" -> file.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        "cue" -> file.readLines().mapNotNull { CUE_FILE.find(it)?.groupValues?.get(1) }
        else -> emptyList()
    }.map { it.substringAfterLast('/') }

    private val DISC = Regex("""\s*\(Disc \d+\)""", RegexOption.IGNORE_CASE)
    private val CUE_FILE = Regex("""^\s*FILE\s+"(.+)"""")
}
