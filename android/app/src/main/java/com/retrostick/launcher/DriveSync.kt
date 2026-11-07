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
            runCatching { StickStatus.writeStatusJson(context) }
            listOf("saves", "states", ".stick").forEach { syncSaves(it) }
            // A new launcher published with android/publish-update.mjs (before the ROMs, which can take long).
            runCatching { Updater.check(context, drive, dir(Updater.FOLDER)) }.onFailure { Log.w("RetroStick", "Update check failed", it) }
            CONSOLES.forEach { syncRoms(it) }
        }

        private fun checkCancel() { if (cancelRequested) throw Cancelled() }

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
            ?.filter { it.isFile && !it.name.startsWith(".") && !it.name.endsWith(".part") && !it.name.endsWith(".tmp") }
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

            for (r in remote.values) {
                checkCancel()
                val l = local[r.name]
                if (l == null || l.length() != r.size) drive.download(r.id, File(console.romDir, r.name))
                manifest += "${console.id}/${r.name}"
                save()
            }
            for (l in local.values) {
                if (l.name in remote) continue
                checkCancel()
                val key = "${console.id}/${l.name}"
                if (key in manifest) {
                    // Downloaded by us and no longer in JugandoAhora: removed from the stick on purpose.
                    l.delete()
                    manifest -= key
                } else {
                    // Copied to the stick by hand: keep it and back it up.
                    drive.upload(l, folderId, null)
                    manifest += key
                }
                save()
            }
        }
    }
}
