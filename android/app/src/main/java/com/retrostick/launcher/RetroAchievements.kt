package com.retrostick.launcher

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * RetroAchievements in RetroArch. The account lives in Drive as Retro/Config/ra.json — {"user": "…", "token": "…"},
 * the token RetroAchievements gives at login (never the password). Each sync copies it to the stick and
 * writes it into RetroArch's config (cheevos_* keys), and again right before a game starts, because RetroArch
 * rewrites its config when it closes. Softcore: quick saves and fast-forward keep working.
 */
object RetroAchievements {
    const val FILE = "ra.json"

    class Account(val user: String, val token: String)

    /**
     * Consoles whose core RetroAchievements accepts. Super Nintendo runs on Snes9x 2010 (the S805Y can't keep up with
     * current Snes9x), which RetroAchievements doesn't support: its sets unlock a "Warning: Unsupported Core" and real
     * ones may misfire, so achievements are off there (also in stick/retroarch/overrides/Snes9x 2010/Snes9x 2010.cfg).
     */
    fun supports(console: Console) = console.id != "snes"

    fun account(context: Context): Account? = runCatching {
        val j = JSONObject(File(context.filesDir, FILE).readText())
        Account(j.getString("user"), j.getString("token")).takeIf { it.user.isNotBlank() && it.token.isNotBlank() }
    }.getOrNull()

    /** During a Drive sync: the account from Retro/Config (gone from Drive = achievements off). */
    fun sync(context: Context, drive: DriveClient, configFolderId: String) {
        val local = File(context.filesDir, FILE)
        val remote = drive.list(configFolderId).firstOrNull { !it.isFolder && it.name == FILE }
        if (remote == null) {
            if (local.exists()) { local.delete(); apply(context) }
            return
        }
        val tmp = File(context.filesDir, "$FILE.part")
        drive.download(remote.id, tmp)
        if (!tmp.renameTo(local)) { tmp.delete(); return }
        apply(context)
    }

    /** Writes the account (or "off") into retroarch.cfg; keys missing from the file are added at the end. */
    fun apply(context: Context) {
        val a = account(context)
        writeRetroArchCfg(linkedMapOf(
            "cheevos_enable" to (a != null).toString(),
            "cheevos_username" to (a?.user ?: ""),
            "cheevos_token" to (a?.token ?: ""),
            "cheevos_password" to "",
            "cheevos_hardcore_mode_enable" to "false",
            // The unlock pop-up with the achievement's badge and a sound, like a console's trophy
            "cheevos_badges_enable" to "true",
            "cheevos_unlock_sound_enable" to "true",
        ))
    }

    // ---- A game's achievements (api/ra.ts): the Logros screen and the count in the ficha ----

    class Achievement(
        val id: Int, val title: String, val description: String, val points: Int,
        val badge: String, val badgeLocked: String, val earned: String?, val rarity: Double?,
    )

    class Progress(
        val title: String, val icon: String?, val total: Int, val earned: Int, val points: Int, val earnedPoints: Int,
        val achievements: List<Achievement>,
        /** false: this ROM isn't a version the achievements support (found by title); they're off for it */
        val supported: Boolean = true,
    )

    /** Session cache: game key → progress, or null = RetroAchievements has no achievements for it */
    private val progress = ConcurrentHashMap<String, Pair<Progress?, Long>>()
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Known progress of a game (null = not asked yet, or not found: see [known]). */
    fun cached(game: Game): Progress? = progress[game.key]?.first
    fun known(game: Game) = progress.containsKey(game.key)

    /** Asks for a game's progress in the background (once a minute at most); listeners are told. */
    fun request(context: Context, game: Game, force: Boolean = false) {
        val a = account(context) ?: return
        progress[game.key]?.let { if (!force && System.currentTimeMillis() - it.second < 60_000) return }
        if (!inFlight.add(game.key)) return
        val app = context.applicationContext
        io.execute {
            try {
                val p = runCatching { fetch(app, game, a.user) }
                p.onSuccess { progress[game.key] = it to System.currentTimeMillis() }
                    .onFailure { Log.w("RetroStick", "RetroAchievements: ${game.title}", it) }
            } finally {
                inFlight.remove(game.key)
                main.post { listeners.forEach { it() } }
            }
        }
    }

    private fun fetch(context: Context, game: Game, user: String): Progress? {
        val md5 = runCatching { romMd5(context, game) }.getOrNull()
        val url = "${BuildConfig.WEBAPP_URL}/api/ra?console=${game.console.id}&user=${enc(user)}" +
            "&title=${enc(Fichas.igdbTitle(game.title))}" + (md5?.let { "&md5=$it" } ?: "")
        val conn = ShopApi.open(url)
        val j = try {
            if (conn.responseCode != 200) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
            if (conn.contentType?.startsWith("application/json") != true) throw NotPairedException()
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        if (j.has("error")) throw IllegalStateException(j.optString("error"))
        if (!j.optBoolean("found")) return null
        val arr = j.getJSONArray("achievements")
        val list = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            Achievement(
                o.getInt("id"), o.optString("title"), o.optString("description"), o.optInt("points"),
                o.optString("badge"), o.optString("badgeLocked"),
                if (o.isNull("earned")) null else o.optString("earned"),
                if (o.isNull("rarity")) null else o.optDouble("rarity"),
            )
        }
        return Progress(
            j.optString("title"), if (j.isNull("icon")) null else j.optString("icon"),
            j.optInt("total"), j.optInt("earned"), j.optInt("points"), j.optInt("earnedPoints"), list,
            j.optBoolean("supported", true),
        )
    }

    // ---- The user's trophy profile (Retro Ajustes → Mis logros) ----

    class ProfileGame(val title: String, val console: String, val icon: String?, val total: Int, val earned: Int, val last: String?)
    class Recent(val title: String, val description: String, val points: Int, val badge: String?, val game: String, val date: String)
    class Profile(
        val user: String, val avatar: String?, val points: Int, val rank: Int?,
        val games: List<ProfileGame>, val recent: List<Recent>,
    )

    /** The profile, or an exception (no account, offline…). Slow: call it off the main thread. */
    fun fetchProfile(context: Context): Profile {
        val a = account(context) ?: throw IllegalStateException("RetroAchievements no está activado en este stick.")
        val conn = ShopApi.open("${BuildConfig.WEBAPP_URL}/api/ra?profile=1&user=${enc(a.user)}")
        val j = try {
            if (conn.responseCode != 200) throw IllegalStateException("La webapp respondió ${conn.responseCode}")
            if (conn.contentType?.startsWith("application/json") != true) throw NotPairedException()
            JSONObject(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
        if (j.has("error")) throw IllegalStateException(j.optString("error"))
        fun str(o: JSONObject, k: String) = if (o.isNull(k)) null else o.optString(k)
        val games = j.optJSONArray("games")?.let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                ProfileGame(o.optString("title"), o.optString("console"), str(o, "icon"), o.optInt("total"), o.optInt("earned"), str(o, "last"))
            }
        }.orEmpty()
        val recent = j.optJSONArray("recent")?.let { arr ->
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Recent(o.optString("title"), o.optString("description"), o.optInt("points"), str(o, "badge"), o.optString("game"), o.optString("date"))
            }
        }.orEmpty()
        val points = maxOf(j.optInt("points"), j.optInt("softcorePoints"))
        return Profile(j.optString("user", a.user), str(j, "avatar"), points, if (j.isNull("rank")) null else j.optInt("rank"), games, recent)
    }

    /** Each core's folder in RetroArch/config (its overrides). */
    private val CORE_DIRS = mapOf(
        "fceumm" to "FCEUmm", "snes9x2010" to "Snes9x 2010", "gambatte" to "Gambatte", "mgba" to "mGBA",
        "genesis_plus_gx" to "Genesis Plus GX", "pcsx_rearmed" to "PCSX-ReARMed",
    )
    private const val MARK = "# RetroStick: achievements off"

    /**
     * Before a game: a ROM that isn't a supported version gets a game override with achievements off (no
     * "Unsupported Game Version" warning, no misfires); a supported one loses that override again.
     */
    fun applyGame(game: Game) {
        val p = progress[game.key] ?: return
        val dir = CORE_DIRS[game.console.core.removeSuffix("_libretro_android.so")] ?: return
        val f = File("$SDCARD/RetroArch/config/$dir/${game.baseName}.cfg")
        runCatching {
            val unsupported = p.first?.supported == false
            when {
                unsupported -> {
                    f.parentFile?.mkdirs()
                    f.writeText("$MARK (not a version its achievements support)\ncheevos_enable = \"false\"\n")
                }
                f.isFile && f.readText().startsWith(MARK) -> f.delete()
            }
        }.onFailure { Log.w("RetroStick", "RetroAchievements: override for ${game.title}", it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * The ROM's hash as RetroAchievements computes it for cartridges (NES without its 16-byte header, SNES
     * without a 512-byte copier header, the rest whole), kept per file. PlayStation discs: none (found by title).
     */
    private fun romMd5(context: Context, game: Game): String? {
        if (game.console.id == "psx") return null
        val f = game.file
        if (!f.isFile || f.length() > 64L * 1024 * 1024) return null
        val prefs = context.getSharedPreferences("ra_md5", Context.MODE_PRIVATE)
        val k = "${f.path}|${f.length()}|${f.lastModified()}"
        prefs.getString(k, null)?.let { return it }
        val bytes = f.readBytes()
        val skip = when {
            game.console.id == "nes" && bytes.size > 16 && bytes[0] == 0x4E.toByte() && bytes[1] == 0x45.toByte() &&
                bytes[2] == 0x53.toByte() && bytes[3] == 0x1A.toByte() -> 16
            game.console.id == "snes" && bytes.size % 1024 == 512 -> 512
            else -> 0
        }
        val md = MessageDigest.getInstance("MD5").apply { update(bytes, skip, bytes.size - skip) }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().putString(k, hex).apply()
        return hex
    }
}
