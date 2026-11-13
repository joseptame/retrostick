package com.retrostick.launcher

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * A new stick sets itself up (SetupActivity): everything comes from the repository's «stick» release on GitHub,
 * published by stick/publish-stick.mjs:
 *   setup.json               what's there: { retroarch, cores, config, launcher } with file, version, sha256, size
 *   RetroArch_ra32.apk       RetroArch 1.22.2, 32 bits (a copy of libretro's stable build)
 *   retrostick-cores.apk     «RetroStick Núcleos» (android/cores): the emulator cores as native libraries
 *   retrostick-config.zip    the admin's stick configuration (stick/config-pack.mjs)
 *   launcher.json / .apk     launcher updates (Updater.kt), for every stick
 *
 * Cores: RetroArch only installs cores into its private folder through its own menu, so a new stick doesn't: they
 * come in «RetroStick Núcleos», whose lib folder any app may load from (like WebView's), and the launcher hands
 * RetroArch that path ([corePath]). Sticks set up before keep RetroArch's own cores.
 *
 * Afterwards ([checkUpdates], when the launcher shows up, at most every half hour) a stick set up by the assistant
 * also follows the release: newer cores are offered like a launcher update (Android asks to install), and a newer
 * configuration pack is applied by itself, replacing only the files this stick hasn't changed; button remaps are
 * never updated (each stick keeps its own buttons, whoever set them up).
 */
object Setup {
    const val RELEASE = "https://github.com/joseptame/retrostick/releases/download/stick"
    const val CORES_PACKAGE = "com.retrostick.cores"

    private fun prefs(context: Context) = context.getSharedPreferences("setup", Context.MODE_PRIVATE)

    /** This stick was set up by the assistant: its games use the cores of «RetroStick Núcleos». */
    fun usesCoresApp(context: Context) = prefs(context).getBoolean("coresApp", false)

    fun configVersion(context: Context) = prefs(context).getString("config", null)

    /** Newer «RetroStick Núcleos» found by the last [checkUpdates], or null. */
    @Volatile var coresUpdate: Asset? = null
        private set
    /** Cores version the user answered "Ahora no" to (asked again after the launcher restarts). */
    @Volatile var dismissedCores = 0
    @Volatile private var lastCheck = 0L

    /**
     * Newer cores and configuration in the release (only on sticks set up by the assistant). Blocking.
     * Returns true when a newer configuration was just applied.
     */
    fun checkUpdates(context: Context): Boolean {
        if (!usesCoresApp(context)) return false
        val now = System.currentTimeMillis()
        if (now - lastCheck < 30 * 60_000L) return false
        lastCheck = now
        val m = manifest()
        coresUpdate = m.cores.takeIf { it.versionCode > (versionCode(context, CORES_PACKAGE) ?: 0) }
        if (m.config.version <= (configVersion(context) ?: "")) return false
        val zip = File(context.cacheDir, "setup/config.zip")
        download(m.config, zip) {}
        applyConfig(context, zip, update = true)
        zip.delete()
        prefs(context).edit().putString("config", m.config.version).apply()
        return true
    }

    fun markDone(context: Context, config: String) =
        prefs(context).edit().putBoolean("done", true).putBoolean("coresApp", true).putString("config", config).apply()

    /**
     * A stick that still needs the assistant: no RetroArch, or (once storage can be read) no RetroArch configuration.
     * A stick set up by hand (install.ps1, like the admin's) has both, so it never sees it.
     */
    fun needed(context: Context): Boolean {
        if (prefs(context).getBoolean("done", false)) return false
        if (versionCode(context, RETROARCH_PACKAGE) == null) return true
        val storage = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return storage && (!File(RETROARCH_CFG).isFile || !File("$SDCARD/RetroArch/config").isDirectory)
    }

    private fun writtenFile(context: Context) = File(context.filesDir, "config-files.json")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun keysFile(context: Context) = File(context.filesDir, "retroarch-keys.cfg")

    /** The pack's retroarch.cfg keys, kept by [applyConfig] (empty on sticks set up by hand). */
    fun packKeys(context: Context): Map<String, String> =
        runCatching { keysFile(context).readLines() }.getOrDefault(emptyList())
            .mapNotNull { Regex("""^(\S+) = "(.*)"$""").find(it.trim()) }
            .associate { it.groupValues[1] to it.groupValues[2] }
            // RetroAchievements.kt decides this one from the stick's own account
            .filterKeys { it != "cheevos_enable" }

    /** The core RetroArch should load for [core] ("mgba_libretro_android.so"), or null for RetroArch's own. */
    fun corePath(context: Context, core: String): String? {
        if (!usesCoresApp(context)) return null
        val dir = runCatching { context.packageManager.getApplicationInfo(CORES_PACKAGE, 0).nativeLibraryDir }.getOrNull() ?: return null
        return File(dir, "lib$core").takeIf { it.isFile }?.absolutePath
    }

    @Suppress("DEPRECATION")
    fun versionCode(context: Context, pkg: String): Int? =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionCode }.getOrNull()

    // ---- What the release offers ----

    class Asset(val file: String, val version: String, val versionCode: Int, val sha256: String, val size: Long) {
        val url get() = "$RELEASE/$file"
    }

    class Manifest(val retroarch: Asset, val cores: Asset, val config: Asset)

    fun manifest(): Manifest {
        val json = JSONObject(String(get("$RELEASE/setup.json").use { it.readBytes() }))
        fun asset(key: String) = json.getJSONObject(key).let {
            Asset(it.getString("file"), it.optString("version"), it.optInt("versionCode"), it.getString("sha256"), it.optLong("size"))
        }
        return Manifest(asset("retroarch"), asset("cores"), asset("config"))
    }

    private fun get(url: String) = (URL(url).openConnection() as HttpURLConnection).run {
        // GitHub answers with a redirect to its file storage (https → https: followed)
        instanceFollowRedirects = true
        connectTimeout = 15_000
        readTimeout = 30_000
        if (responseCode !in 200..299) throw IllegalStateException("$url: HTTP $responseCode")
        inputStream
    }

    /** Downloads [asset] into [dest], reporting 0..1, and checks its SHA-256 (blocking). */
    fun download(asset: Asset, dest: File, progress: (Float) -> Unit) = download(asset.url, asset.sha256, asset.size, dest, progress)

    fun download(url: String, sha256: String, size: Long, dest: File, progress: (Float) -> Unit) {
        dest.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var done = 0L
        get(url).use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (size > 0) progress((done.toFloat() / size).coerceAtMost(1f))
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hash.equals(sha256, ignoreCase = true)) {
            dest.delete()
            throw IllegalStateException("La descarga de ${dest.name} no coincide con la publicada")
        }
    }

    /**
     * The configuration pack into /sdcard (RetroArch's config, controller profiles, frames) and its retroarch.cfg
     * keys merged into RetroArch's own file. RetroArch must not be running: it rewrites retroarch.cfg on exit.
     * [onFile] hears each entry as it goes (its path in the pack, how many done, how many in all), for the assistant.
     */
    fun applyConfig(context: Context, zip: File, update: Boolean = false, onFile: ((String, Int, Int) -> Unit)? = null) {
        val root = File(SDCARD).canonicalFile
        val total = if (onFile != null) runCatching { java.util.zip.ZipFile(zip).use { it.size() } }.getOrDefault(0) else 0
        var count = 0
        var keys: String? = null
        // What each file was when the pack wrote it: on an update, a file that differs now was changed on this stick
        val written = runCatching { JSONObject(writtenFile(context).readText()) }.getOrDefault(JSONObject())
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.removePrefix("./")
                count++
                if (e.isDirectory || name.isEmpty()) continue
                onFile?.invoke(name, count, total)
                when (name) {
                    "pack.json" -> continue
                    "retroarch-keys.cfg" -> keys = String(z.readBytes())
                    else -> {
                        val out = File(root, name).canonicalFile
                        // Only inside /sdcard/RetroArch: nothing in the pack may write anywhere else
                        if (!out.path.startsWith(File(root, "RetroArch").path + File.separator)) continue
                        val bytes = z.readBytes()
                        // Buttons (remaps) are each stick's own: copied when it's set up, never updated afterwards
                        // (only a console it has none for yet, e.g. a new one, gets the pack's as a start)
                        if (update && out.isFile && name.startsWith("RetroArch/config/remaps/")) continue
                        if (update && out.isFile && sha256(out.readBytes()) != written.optString(name)) {
                            android.util.Log.i("RetroStick", "Config pack: kept this stick's $name")
                            continue
                        }
                        out.parentFile?.mkdirs()
                        out.writeBytes(bytes)
                        written.put(name, sha256(bytes))
                    }
                }
            }
        }
        writtenFile(context).writeText(written.toString())
        keysFile(context).writeText(keys.orEmpty())
        val cfg = File(RETROARCH_CFG)
        if (!cfg.isFile) {
            cfg.parentFile?.mkdirs()
            cfg.writeText("")
        }
        writeRetroArchCfg(packKeys(context), cfg)
        // The folders the launcher and RetroArch use
        (CONSOLES.map { it.romDir } + listOf("Partidas/saves", "Partidas/states", "Partidas/.stick", "RetroArch/system").map { File(SDCARD, it) })
            .forEach { it.mkdirs() }
    }
}
