package com.retrostick.launcher

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest

/**
 * Launcher updates published in the user's Drive (Retro/Config/launcher: launcher.json + the APK) by
 * android/publish-update.mjs. Checked at the end of each sync; installing goes through Android's installer,
 * which always asks the user to confirm.
 */
object Updater {
    const val FOLDER = "Retro/Config/launcher"
    private const val MANIFEST = "launcher.json"

    class Update(val versionCode: Int, val versionName: String, val apkId: String, val sha256: String, val size: Long)

    /** Newer version found by the last check, or null. */
    @Volatile var available: Update? = null
        private set

    /** Version the user answered "Ahora no" to (asked again after the launcher restarts). */
    @Volatile var dismissedVersion = 0

    @Volatile private var lastCheck = 0L

    /**
     * Check on its own, without waiting for a full sync (which starts late after boot and stops when a game
     * opens): when the launcher shows up and then every half hour. Blocking; returns whether a newer one exists.
     */
    fun checkNow(context: Context, force: Boolean = false): Boolean {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < CHECK_EVERY_MS) return available != null
        val auth = DriveAuth(context)
        if (!auth.isConfigured || !auth.isConnected) return false
        val drive = DriveClient(auth)
        var dir = "root"
        for (name in FOLDER.split('/')) dir = drive.folder(dir, name)
        check(context, drive, dir)
        lastCheck = now
        return available != null
    }

    private const val CHECK_EVERY_MS = 30 * 60_000L

    /** Reads launcher.json from Drive (folder id from the sync) and remembers a newer version. */
    fun check(context: Context, drive: DriveClient, folderId: String) {
        val files = drive.list(folderId)
        val manifest = files.firstOrNull { it.name == MANIFEST } ?: run { available = null; return }
        val tmp = File(context.cacheDir, MANIFEST)
        drive.download(manifest.id, tmp)
        val json = JSONObject(tmp.readText())
        tmp.delete()
        val code = json.getInt("versionCode")
        val apk = files.firstOrNull { it.name == json.getString("apk") }
        available = if (code > BuildConfig.VERSION_CODE && apk != null) {
            Update(code, json.optString("versionName", code.toString()), apk.id, json.getString("sha256"), apk.size)
        } else null
    }

    /** Downloads the APK into the app's cache and checks its SHA-256 (blocking: call off the main thread). */
    fun download(context: Context, update: Update): File {
        val dir = File(context.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val apk = File(dir, APK_NAME)
        DriveClient(DriveAuth(context)).download(update.apkId, apk)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(update.sha256, ignoreCase = true)) {
            apk.delete()
            throw IllegalStateException("La descarga no coincide con la publicada")
        }
        return apk
    }

    /** Android's installer for the downloaded APK (through [ApkProvider]: file:// URIs are not allowed). */
    fun installIntent(context: Context): Intent =
        Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(Uri.parse("content://${context.packageName}.apk/$APK_NAME"), APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, false)

    const val APK_NAME = "launcher.apk"
    const val APK_MIME = "application/vnd.android.package-archive"
}

/** Serves the downloaded update (cache/update/launcher.apk) read-only to the package installer. */
class ApkProvider : ContentProvider() {
    private fun file(uri: Uri): File {
        if (uri.lastPathSegment != Updater.APK_NAME) throw FileNotFoundException(uri.toString())
        return File(context!!.cacheDir, "update/${Updater.APK_NAME}").takeIf { it.exists() }
            ?: throw FileNotFoundException(uri.toString())
    }

    override fun onCreate() = true
    override fun getType(uri: Uri) = Updater.APK_MIME
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
