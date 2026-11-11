package com.retrostick.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

const val SDCARD = "/storage/emulated/0"
const val RETROARCH_PACKAGE = "com.retroarch.ra32"
private const val RETROARCH_ACTIVITY = "com.retroarch.browser.retroactivity.RetroActivityFuture"
internal const val RETROARCH_CFG = "$SDCARD/Android/data/$RETROARCH_PACKAGE/files/retroarch.cfg"

data class Console(
    val id: String,
    val name: String,
    val logo: Int,
    val extensions: Set<String>,
    val core: String,
    /** libretro-thumbnails repository name (README 7.5). */
    val thumbnailSystem: String,
    /** White variant for unselected tabs when tinting the logo white would hide it (GBA's blue plate). */
    val logoWhite: Int? = null,
    /** Dark logo that disappears on the selected (purple) tab: drawn white there too (Game Boy's navy). */
    val whiteWhenSelected: Boolean = false,
    /** Logo whose letters are already white: the «Añadir juegos» card keeps its colours (Nintendo 64's N and 64). */
    val colourOnCard: Boolean = false,
) {
    val romDir get() = File("$SDCARD/ROMs/$id")
}

val CONSOLES = listOf(
    Console("nes", "NES", R.drawable.logo_nes, setOf("nes"),
        "fceumm_libretro_android.so", "Nintendo_-_Nintendo_Entertainment_System"),
    Console("snes", "Super Nintendo", R.drawable.logo_snes, setOf("sfc", "smc"),
        "snes9x2010_libretro_android.so", "Nintendo_-_Super_Nintendo_Entertainment_System"),
    Console("gb", "Game Boy", R.drawable.logo_gb, setOf("gb"),
        "gambatte_libretro_android.so", "Nintendo_-_Game_Boy", whiteWhenSelected = true),
    Console("gbc", "Game Boy Color", R.drawable.logo_gbc, setOf("gbc"),
        "gambatte_libretro_android.so", "Nintendo_-_Game_Boy_Color"),
    Console("gba", "Game Boy Advance", R.drawable.logo_gba, setOf("gba"),
        "mgba_libretro_android.so", "Nintendo_-_Game_Boy_Advance", logoWhite = R.drawable.logo_gba_white),
    // Sega: Genesis Plus GX runs both (and is light enough for this stick)
    Console("md", "Mega Drive", R.drawable.logo_md, setOf("md", "gen", "smd"),
        "genesis_plus_gx_libretro_android.so", "Sega_-_Mega_Drive_-_Genesis", whiteWhenSelected = true),
    Console("sms", "Master System", R.drawable.logo_sms, setOf("sms"),
        "genesis_plus_gx_libretro_android.so", "Sega_-_Master_System_-_Mark_III"),
    // Mupen64Plus-Next (GLES2 build): full speed on the stick with its threaded renderer (core options in
    // stick/retroarch/core-options/Mupen64Plus-Next). Big-endian .z64 is the No-Intro format; .n64/.v64 also run.
    Console("n64", "Nintendo 64", R.drawable.logo_n64, setOf("z64", "n64", "v64"),
        "mupen64plus_next_gles2_libretro_android.so", "Nintendo_-_Nintendo_64", colourOnCard = true),
    // .bin tracks are reached through their .cue; discs listed in a .m3u are hidden behind it.
    Console("psx", "PlayStation", R.drawable.logo_psx, setOf("m3u", "cue", "chd", "pbp"),
        "pcsx_rearmed_libretro_android.so", "Sony_-_PlayStation"),
)

class Game(val console: Console, val file: File) {
    /** No-Intro / Redump name without extension; also the base name of saves and runtime logs. */
    val baseName: String = file.nameWithoutExtension
    val title: String = displayTitle(baseName)
    val region: String? = Regex("""\(([^)]*)\)""").find(baseName)?.groupValues?.get(1)?.let(::regionName)
    val key get() = "${console.id}/$baseName"

    var saveFiles: List<File> = emptyList()
    var runtimeSeconds: Long = 0
    var playCount: Int = 0
    /** Epoch millis of the last session: runtime log first, newest save file otherwise. 0 = never. */
    var lastPlayed: Long = 0
    var favorite = false
    /** Marked as finished (Finished), or null. */
    var finished: Finished.Entry? = null

    val hasProgress get() = saveFiles.isNotEmpty()
}

/** "Legend of Zelda, The - Oracle of Ages (Europe) (En,Fr)" -> "The Legend of Zelda - Oracle of Ages" */
fun displayTitle(baseName: String): String {
    val name = baseName.replace(Regex("""\s*[(\[].*$"""), "").trim()
    val article = Regex("""^(.+?), (The|A|An|El|La|Los|Las|Le|Les|Die|Der|Das)( - .*)?$""").find(name)
        ?: return name
    val (main, art, rest) = article.destructured
    return "$art $main$rest"
}

private fun regionName(group: String) = when (group) {
    "Europe" -> "Europa"
    "USA" -> "EE. UU."
    "Japan" -> "Japón"
    "Spain" -> "España"
    "World" -> "Mundo"
    "USA, Europe" -> "EE. UU. y Europa"
    else -> group
}

class Library(private val context: Context) {
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val savesDir = File("$SDCARD/Partidas/saves")
    private val statesDir = File("$SDCARD/Partidas/states")
    private val runtimeLogs = File("$SDCARD/RetroArch/playlists/logs")
    private val logDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Scans /ROMs. Runs on a background thread. */
    fun scan(): Map<String, List<Game>> {
        val favorites = prefs.getStringSet("favorites", emptySet())!!
        val finished = Finished.load()
        val saves = listFiles(savesDir) + listFiles(statesDir)
        val logs = runtimeLogs.listFiles()?.filter { it.isDirectory }?.flatMap { listFiles(it) }.orEmpty()

        return CONSOLES.associate { console ->
            val files = listFiles(console.romDir).filter { it.extension.lowercase() in console.extensions }
            val inPlaylists = files.filter { it.extension.equals("m3u", true) }
                .flatMap { m3u -> m3u.readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") } }
                .toSet()
            val games = files.filter { it.name !in inPlaylists }.map { Game(console, it) }
            games.forEach { game ->
                game.favorite = game.key in favorites
                game.finished = finished[game.key]
                // <name>.srm, <name>.state, <name>.state.auto, <name>.state1...
                game.saveFiles = saves.filter {
                    it.name == "${game.baseName}.srm" || (it.name.startsWith("${game.baseName}.state") && !it.name.endsWith(".png"))
                }
                readRuntime(game, logs.filter { it.name == "${game.baseName}.lrtl" })
                if (game.lastPlayed == 0L) game.lastPlayed = game.saveFiles.maxOfOrNull { it.lastModified() } ?: 0
            }
            console.id to games.sortedWith(
                compareByDescending<Game> { it.favorite }.thenByDescending { it.lastPlayed }.thenBy { it.title.lowercase() }
            )
        }
    }

    /**
     * Saves and play time of a game that isn't on the stick (one in the Drive collection): they stay in
     * /Partidas and RetroArch's logs after the ROM is taken off.
     */
    fun describe(game: Game) {
        game.finished = Finished.load()[game.key]
        val saves = listFiles(savesDir) + listFiles(statesDir)
        game.saveFiles = saves.filter { it.name == "${game.baseName}.srm" || (it.name.startsWith("${game.baseName}.state") && !it.name.endsWith(".png")) }
        val logs = runtimeLogs.listFiles()?.filter { it.isDirectory }?.flatMap { listFiles(it) }.orEmpty()
        readRuntime(game, logs.filter { it.name == "${game.baseName}.lrtl" })
        if (game.lastPlayed == 0L) game.lastPlayed = game.saveFiles.maxOfOrNull { it.lastModified() } ?: 0
    }

    private fun readRuntime(game: Game, logFiles: List<File>) {
        // One .lrtl per core: {"runtime": "h:mm:ss", "last_played": "yyyy-MM-dd HH:mm:ss", "play_count": "n"}
        for (file in logFiles) {
            try {
                val json = JSONObject(file.readText())
                val parts = json.optString("runtime").split(":").mapNotNull { it.toLongOrNull() }
                if (parts.size == 3) game.runtimeSeconds += parts[0] * 3600 + parts[1] * 60 + parts[2]
                game.playCount += json.optString("play_count").toIntOrNull() ?: 0
                val last = logDate.parse(json.optString("last_played"))?.time ?: 0
                game.lastPlayed = maxOf(game.lastPlayed, last)
            } catch (e: Exception) {
                // A half-written log must not break the home screen.
            }
        }
    }

    fun setFavorite(game: Game, favorite: Boolean) {
        val set = prefs.getStringSet("favorites", emptySet())!!.toMutableSet()
        if (favorite) set.add(game.key) else set.remove(game.key)
        prefs.edit().putStringSet("favorites", set).apply()
        game.favorite = favorite
    }

    private fun listFiles(dir: File) = dir.listFiles()?.filter { it.isFile }.orEmpty()
}

/** Where a game starts: the title screen, where it was closed (.state.auto) or the quick save (.state, Share+L1). */
enum class Resume { TITLE, LAST, QUICK }

internal val STATES_DIR = File("$SDCARD/Partidas/states")

/** The state RetroArch writes when the game is closed (savestate_auto_save). */
fun lastState(game: Game) = File(STATES_DIR, "${game.baseName}.state.auto").takeIf { it.isFile }
/** The quick save of slot 0: Share+L1 saves it, Share+R1 loads it. */
fun quickState(game: Game) = File(STATES_DIR, "${game.baseName}.state").takeIf { it.isFile }

/** Writes these keys into retroarch.cfg, or a core's .opt (missing ones are added at the end); false if it couldn't. */
fun writeRetroArchCfg(keys: Map<String, String>, cfg: File = File(RETROARCH_CFG)): Boolean {
    if (!cfg.isFile) return false
    return runCatching {
        val lines = cfg.readLines().toMutableList()
        var changed = false
        for ((k, v) in keys) {
            val line = "$k = \"$v\""
            val i = lines.indexOfFirst { it.substringBefore('=').trim() == k }
            when {
                i < 0 -> { lines += line; changed = true }
                lines[i] != line -> { lines[i] = line; changed = true }
            }
        }
        if (changed) cfg.writeText(lines.joinToString("\n", postfix = "\n"))
    }.onFailure { android.util.Log.w("RetroStick", "retroarch.cfg not written", it) }.isSuccess
}

/** Same intent verified by ADB in docs/dispositivo.md. Returns false when RetroArch is missing. */
fun launchInRetroArch(context: Context, game: Game, resume: Resume = Resume.TITLE): Boolean {
    val dataDir = try {
        context.packageManager.getApplicationInfo(RETROARCH_PACKAGE, 0).dataDir
    } catch (e: Exception) {
        return false
    }
    // RetroArch only auto-loads .state.auto: the quick save goes there first (closing writes it again anyway)
    if (resume == Resume.QUICK) quickState(game)?.let { runCatching { it.copyTo(File(STATES_DIR, "${game.baseName}.state.auto"), overwrite = true) } }
    writeRetroArchCfg(linkedMapOf(
        // From the title screen unless asked to continue; the state on closing is always kept
        "savestate_auto_load" to (resume != Resume.TITLE).toString(),
        "savestate_auto_save" to "true",
        "savestate_auto_index" to "false",
        // A picture with each save (<state>.png): the cards of Detalles → Jugar
        "savestate_thumbnail_enable" to "true",
        "state_slot" to "0",
        // RetroArch shortcuts, the same on every console: hold Create + L1 = quick save, + R1 = load it,
        // + R2 = fast-forward. DualSense codes on Android 9 (docs/mandos.md): Create 104, L1 100, R1 101, R2 103
        "input_enable_hotkey_btn" to "104",
        "input_save_state_btn" to "100",
        "input_load_state_btn" to "101",
        "input_toggle_fast_forward_btn" to "103",
        // A clean start: no RetroAchievements summary (game name + "1 de 124 logros"), login or "not identified"
        // messages, nor RetroArch's remap / override / controller ones. Unlocks and quick save / load still show.
        "cheevos_visibility_summary" to "2",
        "cheevos_visibility_account" to "false",
        "cheevos_verbose_enable" to "false",
        "notification_show_remap_load" to "false",
        "notification_show_config_override_load" to "false",
        "notification_show_autoconfig" to "false",
        "notification_show_set_initial_disk" to "false",
        // Nor the loading band across the middle (RetroArch logo + the game's name)
        "menu_show_load_content_animation" to "false",
    ))
    // RetroArch rewrites its config when it closes: the RetroAchievements account goes back in every time
    RetroAchievements.apply(context)
    RetroAchievements.applyGame(game)
    // The consoles' boot screens (their BIOS), on or off in Retro Ajustes
    Bios.applyOptions(context)
    val intent = Intent(Intent.ACTION_MAIN)
        .setComponent(ComponentName(RETROARCH_PACKAGE, RETROARCH_ACTIVITY))
        .putExtra("ROM", game.file.absolutePath)
        .putExtra("LIBRETRO", "$dataDir/cores/${game.console.core}")
        .putExtra("CONFIGFILE", RETROARCH_CFG)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    return true
}
