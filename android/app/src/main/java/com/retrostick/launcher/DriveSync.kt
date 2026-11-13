package com.retrostick.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Stick <-> Google Drive (README 3-4, replaces FolderSync):
 *  - Partidas/{saves,states,.stick} <-> Retro/Partidas/... : both ways, newest wins, never deletes.
 *  - Retro/JugandoAhora/<console> -> ROMs/<console> : downloads; deletes only games it downloaded itself
 *    and uploads games copied to the stick by hand.
 *    Unless [autoRoms] is off (a second stick on the same account, decided on its first sync): then it only keeps
 *    up the games this stick chose (DriveCollection lists the rest as «Drive»), never deletes them because another
 *    stick took them off, and what it adds goes to the collection, so the other sticks don't download it.
 * Runs only while the launcher is on screen, so RetroArch never has a save open.
 */
object DriveSync {
    enum class Phase { IDLE, SYNCING, ERROR }

    @Volatile var phase = Phase.IDLE
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var cancelRequested = false
    /** A sync was requested while the running one was being cancelled: run again when it ends. */
    @Volatile private var rerun = false

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private var saveState: JSONObject? = null

    private class Cancelled : Exception()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun lastSuccess(context: Context) = prefs(context).getLong("last_success", 0)

    /** This stick downloads by itself what is in Retro/JugandoAhora (off: the user picks the games). */
    fun autoRoms(context: Context) = prefs(context).getBoolean("autoRoms", true)

    fun setAutoRoms(context: Context, on: Boolean) = prefs(context).edit().putBoolean("autoRoms", on).apply()

    /** Starts a sync unless one is running or Drive is not connected. */
    fun sync(context: Context) {
        val app = context.applicationContext
        val auth = DriveAuth(app)
        if (!auth.isConfigured || !auth.isConnected) return
        if (phase == Phase.SYNCING) {
            // Paused and resumed quickly (Home, a short dialog): keep going instead of dropping this request.
            if (cancelRequested) rerun = true
            cancelRequested = false
            return
        }
        phase = Phase.SYNCING
        cancelRequested = false
        notifyListeners()
        executor.execute {
            try {
                Syncer(app, auth).run()
                prefs(app).edit().putLong("last_success", System.currentTimeMillis()).apply()
                lastError = null
                phase = Phase.IDLE
            } catch (e: Cancelled) {
                phase = Phase.IDLE
            } catch (e: Exception) {
                Log.w("RetroStick", "Drive sync failed", e)
                if ((e as? DriveException)?.httpCode == 404) prefs(app).edit().remove("folders").apply()
                lastError = e.message
                phase = Phase.ERROR
            } finally {
                val again = rerun
                rerun = false
                notifyListeners()
                if (again) main.post { sync(app) }
            }
        }
    }

    /** Stops between files (a game is about to start). */
    fun cancel() { cancelRequested = true }

    /** Every save/state of the game is in Drive exactly as it is on the stick now. */
    fun isBackedUp(context: Context, game: Game): Boolean {
        if (game.saveFiles.isEmpty()) return false
        val state = saveState ?: loadState(context).also { saveState = it }
        return game.saveFiles.all { f ->
            val rec = state.optJSONObject("${f.parentFile?.name}/${f.name}")
            rec != null && rec.getLong("lm") == f.lastModified() && rec.getLong("ls") == f.length()
        }
    }

    /**
     * Takes a game off the stick (its ROM files: the .m3u/.cue and the discs or tracks it lists). Saves stay.
     *  - [alsoDrive] false: the copy in Retro/JugandoAhora moves to Retro/Coleccion (the webapp shows it there
     *    and can send it back); a game Drive doesn't have yet (just downloaded from RetroShop) is uploaded first.
     *  - [alsoDrive] true: the Drive copy goes to the bin (recoverable for 30 days).
     * Runs on the sync thread, after any sync in progress, so the sync never downloads it again halfway.
     * Without Drive it only deletes the files. [done] gets null or the error, on the main thread.
     */
    fun removeGame(context: Context, game: Game, alsoDrive: Boolean, done: (String?) -> Unit) {
        val app = context.applicationContext
        cancel()
        executor.execute {
            val error = try {
                val files = romFiles(game)
                val auth = DriveAuth(app)
                if (auth.isConfigured && auth.isConnected) {
                    val drive = DriveClient(auth)
                    val playing = folderId(app, drive, "Retro/JugandoAhora/${game.console.id}")
                    val remote = drive.list(playing).filter { !it.isFolder }.associateBy { it.name }
                    val collection = folderId(app, drive, "Retro/Coleccion/${game.console.id}")
                    val kept = drive.list(collection).filter { !it.isFolder }.associateBy { it.name }
                    // A picking stick (autoRoms off) leaves JugandoAhora as it is: it's what the other sticks have
                    val auto = autoRoms(app)
                    for (f in files) {
                        val r = remote[f.name]
                        val c = kept[f.name]
                        when {
                            alsoDrive -> { if (r != null) drive.trash(r.id); if (!auto && c != null) drive.trash(c.id) }
                            !auto -> if (r == null && c?.size != f.length()) drive.upload(f, collection, null)
                            // Already in the collection (same size): the JugandoAhora copy is a duplicate.
                            c?.size == f.length() -> if (r != null) drive.trash(r.id)
                            r != null -> drive.move(r.id, collection, playing)
                            else -> drive.upload(f, collection, null)
                        }
                    }
                }
                val manifest = prefs(app).getStringSet("roms", emptySet())!!.toMutableSet()
                for (f in files) {
                    f.delete()
                    manifest -= "${game.console.id}/${f.name}"
                }
                prefs(app).edit().putStringSet("roms", manifest).apply()
                null
            } catch (e: Exception) {
                Log.w("RetroStick", "Remove ${game.file.name} failed", e)
                e.message ?: e.toString()
            }
            main.post { done(error) }
        }
    }

    /** The game's file plus the ones it points to: discs listed in a .m3u, tracks in a .cue (and theirs). */
    fun romFiles(game: Game): List<File> {
        val out = linkedSetOf<File>()
        fun add(f: File) {
            if (!f.isFile || !out.add(f)) return
            val refs = when (f.extension.lowercase()) {
                "m3u" -> f.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                "cue" -> f.readLines().mapNotNull { Regex("""^\s*FILE\s+"(.+)"""").find(it)?.groupValues?.get(1) }
                else -> emptyList()
            }
            refs.forEach { add(File(f.parentFile, it)) }
        }
        add(game.file)
        return out.toList()
    }

    /** Runs [block] on the sync thread (after a sync in progress, which is asked to stop first). */
    fun runExclusive(block: () -> Unit) {
        cancel()
        executor.execute(block)
    }

    /** A ROM put in /ROMs by RetroStick from JugandoAhora: the sync treats it as one it downloaded itself. */
    fun markDownloaded(context: Context, consoleId: String, name: String) {
        val manifest = prefs(context).getStringSet("roms", emptySet())!!.toMutableSet()
        manifest += "$consoleId/$name"
        prefs(context).edit().putStringSet("roms", manifest).apply()
    }

    /** Drive folder id for a "Retro/..." path (same cache as the sync), created when missing. */
    fun folderId(context: Context, drive: DriveClient, path: String): String {
        val folders = JSONObject(prefs(context).getString("folders", "{}")!!)
        folders.optString(path).takeIf { it.isNotEmpty() }?.let { return it }
        val parent = if ('/' in path) folderId(context, drive, path.substringBeforeLast('/')) else "root"
        val id = drive.folder(parent, path.substringAfterLast('/'))
        val latest = JSONObject(prefs(context).getString("folders", "{}")!!).put(path, id)
        prefs(context).edit().putString("folders", latest.toString()).apply()
        return id
    }

    fun forget(context: Context) {
        prefs(context).edit().clear().apply()
        saveState = null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("drive_sync", Context.MODE_PRIVATE)
    private fun loadState(context: Context) = JSONObject(prefs(context).getString("saves", "{}")!!)
    private fun notifyListeners() = main.post { listeners.toList().forEach { it() } }

    private class Syncer(private val context: Context, auth: DriveAuth) {
        private val drive = DriveClient(auth)
        private val prefs = prefs(context)
        private val folders = JSONObject(prefs.getString("folders", "{}")!!)
        private val state = loadState(context)

        fun run() {
            if (DriveAuth(context).email == null) DriveAuth(context).email = drive.accountEmail()
            // Same tree as README 4, so the phone app finds it.
            CONSOLES.forEach { dir("Retro/Coleccion/${it.id}"); dir("Retro/JugandoAhora/${it.id}") }
            dir("Retro/Config/bios")
            if (!prefs.contains("autoRoms")) decideAutoRoms()
            // Games the webapp asked for this stick (before the status, so it already lists them)
            runCatching { takeRequests() }.onFailure { Log.w("RetroStick", "Requests from the webapp failed", it) }
            runCatching { StickStatus.writeStatusJson(context) }
            listOf("saves", "states", ".stick").forEach { syncSaves(it) }
            // A new launcher published with android/publish-update.mjs (before the ROMs, which can take long).
            runCatching { Updater.check(context, drive, dir(Updater.FOLDER)) }.onFailure { Log.w("RetroStick", "Update check failed", it) }
            // RetroAchievements account (Retro/Config/ra.json) into RetroArch
            runCatching { RetroAchievements.sync(context, drive, dir("Retro/Config")) }.onFailure { Log.w("RetroStick", "RetroAchievements sync failed", it) }
            // The user's BIOS (boot screens), uploaded from the phone app
            runCatching { Bios.sync(drive, dir("Retro/Config/bios")) }.onFailure { Log.w("RetroStick", "BIOS sync failed", it) }
            CONSOLES.forEach { syncRoms(it) }
        }

        private fun checkCancel() { if (cancelRequested) throw Cancelled() }

        /**
         * The webapp's Colección, on a stick that picks its games: Retro/Config/stick/pedido-<stick id>-<time>.json,
         * {"console", "file", "add"}, one per change, applied oldest first and deleted. «add» downloads the game
         * (and the discs / tracks its .m3u / .cue lists) from Coleccion or JugandoAhora without moving it in Drive;
         * otherwise it's taken off this stick (Drive keeps it, uploaded to the collection if it had no copy).
         */
        private fun takeRequests() {
            val id = StickStatus.stickId(context)
            val requestsDir = dir("Retro/Config/stick")
            val mine = drive.list(requestsDir).filter { !it.isFolder && it.name.startsWith("pedido-$id-") }.sortedBy { it.name }
            if (mine.isEmpty()) return
            val manifest = prefs.getStringSet("roms", emptySet())!!.toMutableSet()
            var added = 0
            var removed = 0
            for (f in mine) {
                checkCancel()
                val tmp = File(context.cacheDir, "pedido.json")
                val j = runCatching { drive.download(f.id, tmp); JSONObject(tmp.readText()) }.getOrNull()
                tmp.delete()
                val c = CONSOLES.firstOrNull { it.id == j?.optString("console") }
                val name = j?.optString("file").orEmpty().substringAfterLast('/')
                if (c != null && name.isNotEmpty()) {
                    val playing = drive.list(dir("Retro/JugandoAhora/${c.id}")).filter { !it.isFolder }.associateBy { it.name }
                    val collectionId = dir("Retro/Coleccion/${c.id}")
                    val collection = drive.list(collectionId).filter { !it.isFolder }.associateBy { it.name }
                    if (j!!.optBoolean("add")) {
                        c.romDir.mkdirs()
                        val queue = ArrayDeque(listOf(name))
                        val seen = mutableSetOf<String>()
                        while (queue.isNotEmpty()) {
                            val n = queue.removeFirst()
                            if (!seen.add(n)) continue
                            val remote = collection[n] ?: playing[n] ?: continue
                            val target = File(c.romDir, n)
                            if (!(target.isFile && target.length() == remote.size)) drive.download(remote.id, target)
                            manifest += "${c.id}/$n"
                            DriveCollection.references(target).forEach { queue.addLast(it) }
                        }
                        if (File(c.romDir, name).isFile) added++
                    } else {
                        val game = File(c.romDir, name)
                        if (game.isFile) {
                            for (rom in romFiles(Game(c, game))) {
                                if (rom.name !in playing && collection[rom.name]?.size != rom.length()) drive.upload(rom, collectionId, null)
                                rom.delete()
                                manifest -= "${c.id}/${rom.name}"
                            }
                            removed++
                        }
                    }
                    prefs.edit().putStringSet("roms", manifest.toSet()).apply()
                }
                runCatching { drive.delete(f.id) }
            }
            if (added + removed > 0) main.post {
                DriveCollection.refresh(context, force = true)
                val what = listOfNotNull(
                    if (added > 0) "$added ${if (added == 1) "juego descargado" else "juegos descargados"}" else null,
                    if (removed > 0) "$removed ${if (removed == 1) "juego quitado" else "juegos quitados"}" else null,
                ).joinToString(" y ")
                android.widget.Toast.makeText(context, "Desde el móvil: $what", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        /**
         * First sync of a stick: when the account's JugandoAhora already has games this stick doesn't, another stick
         * uses the account, and this one lets the user pick its games instead of downloading them all.
         * A stick that synced before (an update from a version without the choice) keeps downloading them.
         */
        private fun decideAutoRoms() {
            val firstSync = prefs.getLong("last_success", 0) == 0L
            val others = firstSync && CONSOLES.any { c ->
                val local = localFiles(c.romDir).keys
                drive.list(dir("Retro/JugandoAhora/${c.id}")).any { !it.isFolder && it.name !in local }
            }
            prefs.edit().putBoolean("autoRoms", !others).apply()
            if (others) main.post {
                DriveCollection.refresh(context, force = true)
                android.widget.Toast.makeText(context, "Tu Drive ya tiene juegos de otro stick: no se descargan solos. " +
                    "Elige los que quieras en cada consola (salen como «Drive»).", android.widget.Toast.LENGTH_LONG).show()
            }
        }

        /** Folder id for a "Retro/..." path, created when missing and cached. */
        private fun dir(path: String): String {
            folders.optString(path).takeIf { it.isNotEmpty() }?.let { return it }
            val parent = if ('/' in path) dir(path.substringBeforeLast('/')) else "root"
            val id = drive.folder(parent, path.substringAfterLast('/'))
            folders.put(path, id)
            prefs.edit().putString("folders", folders.toString()).apply()
            return id
        }

        private fun localFiles(dir: File) = dir.apply { mkdirs() }.listFiles()
            // Neo Geo's BIOS sits next to its games (Bios.kt puts it there): not a game to back up
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") && it.name != NEOGEO_BIOS }
            ?.associateBy { it.name }.orEmpty()

        private fun syncSaves(sub: String) {
            val folderId = dir("Retro/Partidas/$sub")
            val localDir = File("$SDCARD/Partidas/$sub")
            val remote = drive.list(folderId).filter { !it.isFolder }.associateBy { it.name }
            val local = localFiles(localDir)

            fun record(key: String, file: File, r: DriveClient.RemoteFile) {
                state.put(key, JSONObject().put("id", r.id).put("rm", r.modified)
                    .put("lm", file.lastModified()).put("ls", file.length()))
                prefs.edit().putString("saves", state.toString()).apply()
                saveState = state
            }
            fun up(key: String, file: File, existingId: String?) = record(key, file, drive.upload(file, folderId, existingId))
            fun down(key: String, r: DriveClient.RemoteFile) {
                val file = File(localDir, r.name)
                drive.download(r.id, file)
                file.setLastModified(r.modified)
                record(key, file, r)
            }

            for (name in remote.keys + local.keys) {
                checkCancel()
                val key = "$sub/$name"
                val l = local[name]
                val r = remote[name]
                val rec = state.optJSONObject(key)
                when {
                    r == null -> up(key, l!!, null)
                    l == null -> down(key, r)
                    else -> {
                        val localChanged = rec == null || l.lastModified() != rec.getLong("lm") || l.length() != rec.getLong("ls")
                        val remoteChanged = rec == null || r.modified != rec.getLong("rm") || r.id != rec.getString("id")
                        when {
                            !localChanged && !remoteChanged -> Unit
                            localChanged && !remoteChanged -> up(key, l, r.id)
                            !localChanged -> down(key, r)
                            // First sight of a pair that is already identical
                            l.length() == r.size && abs(l.lastModified() - r.modified) < 2000 -> record(key, l, r)
                            // Changed on both sides: the newest wins
                            l.lastModified() >= r.modified -> up(key, l, r.id)
                            else -> down(key, r)
                        }
                    }
                }
            }
        }

        private fun syncRoms(console: Console) {
            val folderId = dir("Retro/JugandoAhora/${console.id}")
            val remote = drive.list(folderId).filter { !it.isFolder }.associateBy { it.name }
            val local = localFiles(console.romDir)
            val manifest = prefs.getStringSet("roms", emptySet())!!.toMutableSet()
            fun save() = prefs.edit().putStringSet("roms", manifest.toSet()).apply()
            val auto = autoRoms(context)

            for (r in remote.values) {
                checkCancel()
                val key = "${console.id}/${r.name}"
                val l = local[r.name]
                // Picking: only the games this stick has or chose (DriveCollection.passToStick marks them)
                if (!auto && l == null && key !in manifest) continue
                if (l == null || l.length() != r.size) drive.download(r.id, File(console.romDir, r.name))
                manifest += key
                save()
            }
            val collectionId by lazy { dir("Retro/Coleccion/${console.id}") }
            val collection by lazy { drive.list(collectionId).filter { !it.isFolder }.associateBy { it.name } }
            for (l in local.values) {
                if (l.name in remote) continue
                checkCancel()
                val key = "${console.id}/${l.name}"
                when {
                    // Downloaded by us and no longer in JugandoAhora: removed from the stick on purpose.
                    key in manifest && auto -> { l.delete(); manifest -= key }
                    // Picking: taken off by another stick, or chosen from the collection; this one keeps it.
                    key in manifest -> Unit
                    // Copied to the stick by hand: keep it and back it up.
                    auto -> { drive.upload(l, folderId, null); manifest += key }
                    // ...on a picking stick, into the collection, where the other sticks don't pull it
                    else -> {
                        if (collection[l.name]?.size != l.length()) drive.upload(l, collectionId, null)
                        manifest += key
                    }
                }
                save()
            }
        }
    }
}
