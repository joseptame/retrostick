package com.retrostick.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * Trucos: the cheats libretro-database has for a game (the ones RetroArch's own updater brings), chosen in the
 * game's Detalles (CheatsActivity) and handed to RetroArch on every launch as that game's cheat file.
 *
 *  - Which files there are: the webapp's /db/cheats-<console>.json (pwa/scripts/build-cheats.ts), kept a week.
 *  - A game's cheats: its own files (same No-Intro / Redump name, plus «(Game Genie)», «(Code Breaker)»… variants),
 *    else those of another version of the same title; downloaded once from GitHub and kept.
 *  - On launch (launchInRetroArch): RetroArch/cheats/<core>/<rom name>.cht with the chosen ones on, which
 *    RetroArch loads and applies by itself (apply_cheats_after_load). No cheats chosen: no file.
 *  - Achievements keep counting: RetroArch only blocks cheats in hardcore mode, and RetroStick uses softcore.
 */
object Cheats {
    const val DIR = "$SDCARD/RetroArch/cheats"
    private const val RAW = "https://raw.githubusercontent.com/libretro/libretro-database/master/cht/"
    private const val INDEX_DAYS = 7L

    /** libretro-database folder of each console (Neo Geo has none: FinalBurn Neo keeps its own). */
    private val FOLDERS = mapOf(
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "md" to "Sega - Mega Drive - Genesis",
        "sms" to "Sega - Master System - Mark III",
        "n64" to "Nintendo - Nintendo 64",
        "psx" to "Sony - PlayStation",
    )

    /** The core's own name (library_name): RetroArch looks for <cheat folder>/<this>/<game>.cht. */
    private val CORE_NAMES = mapOf(
        "fceumm" to "FCEUmm",
        "snes9x2010" to "Snes9x 2010",
        "gambatte" to "Gambatte",
        "mgba" to "mGBA",
        "genesis_plus_gx" to "Genesis Plus GX",
        "mupen64plus_next_gles2" to "Mupen64Plus-Next",
        "pcsx_rearmed" to "PCSX-ReARMed",
    )

    class Cheat(
        /** desc|code: what is remembered as chosen */
        val id: String,
        /** As written in the file (English) */
        val desc: String,
        /** The Spanish name when it's a common one (vidas infinitas…), else null */
        val spanish: String?,
        /** Every cheatN_<key> of it, as in the file (desc, code, handler, address…), enable left out */
        val fields: Map<String, String>,
        /** A master code («Enable (M)»): on by itself whenever another one is, never shown */
        val master: Boolean,
    ) {
        val name get() = spanish ?: desc
    }

    /** [exact]: the game's own files; false: another version of the same title ([version]), may not all work. */
    class GameCheats(val exact: Boolean, val version: String?, val cheats: List<Cheat>) {
        /** What the list shows: no master codes, and one of each name (the files repeat «Infinite Lives»…). */
        val visible get() = cheats.filter { !it.master }.distinctBy { it.name.lowercase() }
    }

    fun supports(console: Console) = console.id in FOLDERS && coreName(console) != null

    private fun coreName(console: Console) = CORE_NAMES[console.core.removeSuffix("_libretro_android.so")]

    // ---- Which cheats the game has ----

    private fun dir(context: Context) = File(context.filesDir, "cheats")
    private fun indexFile(context: Context, console: Console) = File(dir(context), "index-${console.id}.json")
    private fun gameFile(context: Context, game: Game) = File(dir(context), "${game.console.id}/${game.baseName}.json")

    /** The console's list of cheat files, downloaded again after a week. Blocking; null without one. */
    private fun index(context: Context, console: Console): List<String>? {
        val file = indexFile(context, console)
        val fresh = file.isFile && System.currentTimeMillis() - file.lastModified() < INDEX_DAYS * 86_400_000
        if (!fresh) runCatching {
            val conn = ShopApi.open("${BuildConfig.WEBAPP_URL}/db/cheats-${console.id}.json")
            try {
                if (conn.responseCode == 200 && conn.contentType?.startsWith("application/json") == true) {
                    val text = conn.inputStream.bufferedReader().readText()
                    JSONObject(text).getJSONArray("files")
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }
            } finally {
                conn.disconnect()
            }
        }
        val json = runCatching { JSONObject(file.readText()).getJSONArray("files") }.getOrNull() ?: return null
        return List(json.length()) { json.getString(it) }
    }

    /** What's before the first bracket: the title without region, languages or version. */
    private fun title(name: String) = name.substringBefore(" (").substringBefore(" [").trim().lowercase()

    /** The first bracket: «(Europe)», «(USA, Europe)»… */
    private fun regionOf(name: String) = Regex("""\(([^)]*)\)""").find(name)?.groupValues?.get(1)

    /** The cheat files for [baseName]: its own, else those of the best other version of the same title. */
    internal fun match(names: List<String>, baseName: String): Pair<List<String>, Boolean>? {
        val own = names.filter { it == baseName || it.startsWith("$baseName (") }
        if (own.isNotEmpty()) return own to true
        val t = title(baseName)
        val same = names.filter { title(it) == t }
        if (same.isEmpty()) return null
        val byRegion = same.groupBy { regionOf(it) }
        val mine = regionOf(baseName)
        val pick = listOf(mine, "World", "Europe", "USA, Europe", "Europe, USA", "USA").firstOrNull { it in byRegion } ?: byRegion.keys.first()
        // The plain version of that region first («Title (Europe)»), with its device variants
        val group = byRegion.getValue(pick)
        val base = group.minByOrNull { it.length }!!
        return group.filter { it == base || it.startsWith("$base (") }.ifEmpty { group } to false
    }

    /** Kept on the stick: shown at once, and what each launch uses. */
    fun cached(context: Context, game: Game): GameCheats? {
        val j = runCatching { JSONObject(gameFile(context, game).readText()) }.getOrNull() ?: return null
        val list = j.getJSONArray("cheats")
        val cheats = List(list.length()) { i ->
            val c = list.getJSONObject(i)
            val f = c.getJSONObject("fields")
            cheat(f.keys().asSequence().associateWith { f.getString(it) })
        }
        return GameCheats(j.optBoolean("exact", true), j.optString("version").takeIf { it.isNotEmpty() }, sort(cheats))
    }

    /** Blocking: the game's cheats (downloaded the first time). An empty list: libretro has none for it. */
    fun load(context: Context, game: Game): GameCheats {
        cached(context, game)?.let { return it }
        val folder = FOLDERS[game.console.id] ?: return GameCheats(true, null, emptyList())
        val names = index(context, game.console) ?: throw IllegalStateException("No se pudo bajar la lista de trucos")
        val (files, exact) = match(names, game.baseName) ?: (emptyList<String>() to true)
        val seen = HashSet<String>()
        val all = files.flatMap { name ->
            val url = RAW + enc(folder) + "/" + enc("$name.cht")
            val conn = ShopApi.open(url)
            val text = try {
                if (conn.responseCode != 200) throw IllegalStateException("GitHub respondió ${conn.responseCode}")
                conn.inputStream.bufferedReader().readText()
            } finally {
                conn.disconnect()
            }
            parse(text)
        }.filter { seen.add(it.id.lowercase()) }
        val out = JSONObject()
            .put("exact", exact)
            .put("version", if (exact) "" else files.firstOrNull()?.let { regionOf(it) } ?: "")
            .put("cheats", JSONArray().apply { all.forEach { put(JSONObject().put("fields", JSONObject(it.fields))) } })
        gameFile(context, game).apply { parentFile?.mkdirs() }.writeText(out.toString())
        return cached(context, game)!!
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** A RetroArch .cht: cheats = N, then cheatI_desc / _code / _enable (and more for RetroArch's own ones). */
    internal fun parse(text: String): List<Cheat> {
        val byIndex = sortedMapOf<Int, MutableMap<String, String>>()
        val line = Regex("""^\s*cheat(\d+)_(\w+)\s*=\s*(.*?)\s*$""")
        text.lineSequence().forEach { l ->
            val m = line.find(l) ?: return@forEach
            val value = m.groupValues[3].removeSurrounding("\"")
            byIndex.getOrPut(m.groupValues[1].toInt()) { linkedMapOf() }[m.groupValues[2]] = value
        }
        return byIndex.values
            .filter { !it["code"].isNullOrBlank() || it["handler"] == "1" }
            .map { cheat(it - "enable") }
    }

    private fun cheat(fields: Map<String, String>): Cheat {
        val desc = fields["desc"].orEmpty().trim().ifEmpty { "Truco sin nombre" }
        return Cheat("$desc|${fields["code"].orEmpty()}", desc, spanish(desc), fields, MASTER.containsMatchIn(desc))
    }

    private val MASTER = Regex("""\(M\)|master code|must be on|^enable code""", RegexOption.IGNORE_CASE)

    /** The common ones first (in the order of SPANISH), then the rest as in the file. */
    private fun sort(cheats: List<Cheat>): List<Cheat> {
        val rank = { c: Cheat -> c.spanish?.let { s -> SPANISH.indexOfFirst { it.second == s.substringBefore(" · ") } }?.takeIf { it >= 0 } ?: Int.MAX_VALUE }
        return cheats.withIndex().sortedWith(compareBy({ rank(it.value) }, { it.index })).map { it.value }
    }

    // ---- Spanish names of the usual ones ----

    private const val ANY = "(?:infinite|unlimited|inf\\.?|never lose|endless)"
    private val SPANISH: List<Pair<Regex, String>> = listOf(
        "$ANY lives?|lives never decrease|don'?t lose lives" to "Vidas infinitas",
        "$ANY (?:health|hp|energy|life|life ?bar|vitality|hit points)" to "Salud infinita",
        "(?:totally )?invincib(?:le|ility)|god mode|invulnerab(?:le|ility)" to "Invencible",
        "$ANY time|time (?:stopped|stops|freeze|frozen)|freeze timer|stop timer" to "Tiempo infinito",
        "$ANY (?:ammo|ammunition|bullets|shots)" to "Munición infinita",
        "$ANY continues?" to "Continuaciones infinitas",
        "$ANY credits?" to "Créditos infinitos",
        "$ANY (?:money|cash|gold|gil|zenny)" to "Dinero infinito",
        "$ANY rupees?" to "Rupias infinitas",
        "$ANY coins?" to "Monedas infinitas",
        "$ANY rings?" to "Anillos infinitos",
        "$ANY bombs?" to "Bombas infinitas",
        "$ANY (?:magic|mp|mana)" to "Magia infinita",
        "$ANY items?" to "Objetos infinitos",
        "$ANY (?:special|specials|super)" to "Especial infinito",
        "$ANY (?:oxygen|air)" to "Oxígeno infinito",
        "$ANY fuel" to "Combustible infinito",
        "$ANY jumps?" to "Saltos infinitos",
        "$ANY (?:power|power-?ups?)" to "Poder infinito",
        "$ANY (?:hearts?)" to "Corazones infinitos",
        "(?:max(?:imum)?|full) (?:health|hp|life|energy)" to "Salud al máximo",
        "(?:max(?:imum)?) (?:money|cash|gold)" to "Dinero al máximo",
        "(?:max(?:imum)?|all) stats" to "Estadísticas al máximo",
        "(?:max(?:imum)?) level" to "Nivel máximo",
        "one hit (?:kills?|ko)|one-hit kills?|kill enemies (?:in|with) one hit" to "Matar de un golpe",
        "always big" to "Siempre grande",
        "always have fireballs?|always fire mario" to "Siempre con bolas de fuego",
        "moon ?jump|super jump|high jump|jump higher" to "Súper salto",
        "walk through walls|no ?clip" to "Atravesar paredes",
        "(?:level|stage|round|area) select" to "Elegir nivel",
        "debug mode|debug menu" to "Modo depuración",
        "all weapons" to "Todas las armas",
        "all items" to "Todos los objetos",
        "all levels?(?: unlocked)?|unlock all levels" to "Todos los niveles",
        "all characters?(?: unlocked)?|unlock all characters" to "Todos los personajes",
        "always running|always run" to "Correr siempre",
        "faster running|run faster|super speed" to "Correr más rápido",
    ).map { (re, es) -> Regex("^(?:$re)(?:\\s*\\(.*\\))?[.!]?$", RegexOption.IGNORE_CASE) to es }

    private val PLAYER = Regex("""^(?:p|player\s*|1p|2p)\s*([1-4])\s*[-:]?\s+|^([1-4])p\s+""", RegexOption.IGNORE_CASE)
    private val START_AT = Regex("""^start (?:on|at|in|from) (world|level|stage|round|area) ([\w-]+)$""", RegexOption.IGNORE_CASE)

    /** «Infinite Lives» → «Vidas infinitas»; «P1 Infinite Health» → «Salud infinita · jugador 1»; else null. */
    internal fun spanish(desc: String): String? {
        var d = desc.trim()
        var player: String? = null
        PLAYER.find(d)?.let { m -> player = m.groupValues[1].ifEmpty { m.groupValues[2] }; d = d.substring(m.range.last + 1).trim() }
        Regex("""\s*\((?:p|player\s*)([1-4])\)$""", RegexOption.IGNORE_CASE).find(d)?.let { m -> player = m.groupValues[1]; d = d.substring(0, m.range.first).trim() }
        val es = SPANISH.firstOrNull { it.first.matches(d) }?.second
            ?: START_AT.matchEntire(d)?.let { m ->
                val what = when (m.groupValues[1].lowercase()) { "world" -> "el mundo"; "round" -> "la ronda"; "area" -> "la zona"; else -> "el nivel" }
                "Empezar en $what ${m.groupValues[2]}"
            }
            ?: return null
        return if (player != null) "$es · jugador $player" else es
    }

    // ---- Chosen, per game ----

    private fun prefs(context: Context) = context.getSharedPreferences("cheats", Context.MODE_PRIVATE)
    private fun key(game: Game) = "${game.console.id}/${game.baseName}"

    fun chosen(context: Context, game: Game): Set<String> =
        prefs(context).getString(key(game), null)?.let { s -> runCatching { JSONArray(s) }.getOrNull() }
            ?.let { a -> List(a.length()) { a.getString(it) }.toSet() }.orEmpty()

    fun setChosen(context: Context, game: Game, ids: Set<String>) {
        prefs(context).edit().apply { if (ids.isEmpty()) remove(key(game)) else putString(key(game), JSONArray(ids.toList()).toString()) }.apply()
    }

    /** The names of the chosen cheats, as shown (Detalles: «Trucos al jugar»). */
    fun chosenNames(context: Context, game: Game): List<String> {
        val ids = chosen(context, game)
        if (ids.isEmpty()) return emptyList()
        val all = cached(context, game)?.cheats ?: return ids.map { it.substringBefore("|") }
        return all.filter { it.id in ids && !it.master }.map { it.name }
    }

    /** Before RetroArch opens [game]: its cheat file with the chosen ones on, or none. */
    fun apply(context: Context, game: Game) {
        val core = coreName(game.console) ?: return
        val file = File("$DIR/$core/${game.file.nameWithoutExtension}.cht")
        val ids = chosen(context, game)
        val all = if (ids.isEmpty()) emptyList() else cached(context, game)?.cheats.orEmpty()
        val on = all.filter { it.id in ids }
        if (on.isEmpty()) { file.delete(); return }
        val list = all.filter { it.master } + on
        val text = buildString {
            append("cheats = ${list.size}\n\n")
            list.forEachIndexed { i, c ->
                c.fields.forEach { (k, v) -> append("cheat${i}_$k = \"${v.replace("\"", "'")}\"\n") }
                append("cheat${i}_enable = \"true\"\n\n")
            }
        }
        runCatching { file.parentFile?.mkdirs(); file.writeText(text) }
    }
}
