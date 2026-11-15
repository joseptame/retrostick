package com.retrostick.launcher

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.zip.ZipInputStream

/**
 * Spanish fan translations (Detalles → Idioma, TranslationsActivity). archive.org's «Compilaciones Traducciones al
 * Español (2024/08)» keeps each translated game as a zip with the ROM already patched; the webapp lists them per
 * console (/db/trans-<console>.json, pwa/scripts/build-translations.ts) and a game finds its own by title.
 *
 * Choosing one downloads that ROM to Traducciones/<console>/ under the game's own name, so RetroArch keeps the same
 * saves, states and cheats; the ROM in ROMs/ (and Drive) is never touched. Launching then opens the translated file
 * ([romFor]); «Original» deletes it and goes back. RetroAchievements counts only the versions it knows: most
 * translations aren't among them.
 */
object Translations {
    private const val ITEM = "compilaciones-traducciones-al-espanol-2024-08"
    private const val INDEX_DAYS = 7L
    // N64 and GBA come from compilations of their own (.7z; the index names the item and the ROM inside)
    private val CONSOLES = setOf("nes", "snes", "gb", "gbc", "md", "sms", "n64", "gba")

    class Translation(
        /** The zip in the archive.org item (also the id kept as chosen) */
        val file: String,
        val size: Long,
        /** GoodTools «T+»: complete; «T-»: older or partial */
        val complete: Boolean,
        val by: String,
        val version: String,
        /** What follows the tag in the name («25th Aniversary»…), or null */
        val extra: String?,
        /** Revision of the game it was made on (0: the first release) */
        val rev: Int = 0,
        /** Made on another revision than this stick's ROM (shown only when its own has none) */
        val otherRevision: Boolean = false,
        /** archive.org item holding it */
        val item: String = ITEM,
        /** The ROM inside a .7z, downloaded by itself (archive.org serves it); null: [file] is a zip to unpack */
        val inner: String? = null,
    ) {
        val name get() = "Español - Traducido por $by"
        val details: String
            get() = listOfNotNull(
                // GoodTools «T+»: the newest version of that translation («T-»: an earlier one)
                if (complete) "Versión más reciente" else null,
                version.takeIf { it.isNotBlank() }?.let { if (it.endsWith("%")) it else "v$it" },
                if (otherRevision) "para la revisión ${if (rev == 0) "original" else "1.$rev"} del juego" else null,
                extra,
                "%.1f MB".format(java.util.Locale("es", "ES"), size / 1e6),
            ).joinToString(" · ")
    }

    fun supports(console: Console) = console.id in CONSOLES

    private fun dir(context: Context) = File(context.filesDir, "translations")
    // «v3»: lists before the item / ROM-inside fields (N64, GBA; 2026-11-15) are fetched again
    private fun indexFile(context: Context, console: Console) = File(dir(context), "index-v3-${console.id}.json")
    private fun romDir(console: Console) = File("$SDCARD/Traducciones/${console.id}")

    /** Blocking: the console's list, downloaded again after a week. Null without one. */
    fun refreshIndex(context: Context, console: Console) {
        val file = indexFile(context, console)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < INDEX_DAYS * 86_400_000) return
        runCatching {
            val conn = ShopApi.open("${BuildConfig.WEBAPP_URL}/db/trans-${console.id}.json")
            try {
                if (conn.responseCode == 200 && conn.contentType?.startsWith("application/json") == true) {
                    val text = conn.inputStream.bufferedReader().readText()
                    JSONObject(text).getJSONArray("entries")
                    file.parentFile?.mkdirs()
                    file.writeText(text)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** The game's translations from the list kept on the stick (complete ones first); null if there's no list yet. */
    fun cachedFor(context: Context, game: Game): List<Translation>? = cachedFor(context, game.console, game.title, game.baseName)

    /** Same for a game not on the stick yet (RetroShop): its title and No-Intro name. */
    fun cachedFor(context: Context, console: Console, title: String, baseName: String): List<Translation>? {
        if (!supports(console)) return emptyList()
        val index = runCatching { JSONObject(indexFile(context, console).readText()) }.getOrNull() ?: return null
        val entries = index.optJSONArray("entries") ?: return null
        val item = index.optString("item").ifEmpty { ITEM }
        val key = looseKey(title)
        val all = (0 until entries.length()).map { entries.getJSONObject(it) }.filter { it.optString("k") == key }
        // The ones made on this ROM's revision («(Rev 1)»); every revision only when none was
        val mine = Regex("""\(Rev\s*(\d+)\)""", RegexOption.IGNORE_CASE).find(baseName)?.groupValues?.get(1)?.toInt() ?: 0
        val same = all.filter { it.optInt("rev") == mine }
        return (same.ifEmpty { all }).map {
            Translation(it.getString("f"), it.optLong("s"), it.optBoolean("c"), it.optString("by"), it.optString("v"),
                it.optString("x").takeIf { x -> x.isNotBlank() }, it.optInt("rev"), otherRevision = same.isEmpty() && it.optInt("rev") != mine,
                item = item, inner = it.optString("in").takeIf { x -> x.isNotBlank() })
        }.sortedByDescending { it.complete }
    }

    /** Loose titles with at least one translation in the list kept on the stick (RetroShop's «Traducción» badge). */
    fun cachedTitles(context: Context, console: Console): Set<String> {
        if (!supports(console)) return emptySet()
        val entries = runCatching { JSONObject(indexFile(context, console).readText()).getJSONArray("entries") }.getOrNull() ?: return emptySet()
        return (0 until entries.length()).mapTo(HashSet()) { entries.getJSONObject(it).optString("k") }
    }

    /** Blocking: as [cachedFor], fetching the list first when needed. */
    fun forGame(context: Context, game: Game): List<Translation> {
        refreshIndex(context, game.console)
        return cachedFor(context, game) ?: throw IllegalStateException("No se pudo bajar la lista de traducciones")
    }

    // ---- Chosen, per game ----

    private fun prefs(context: Context) = context.getSharedPreferences("translations", Context.MODE_PRIVATE)
    private fun key(game: Game) = "${game.console.id}/${game.baseName}"

    /** The chosen translation's zip and where its ROM is, or null (the original). */
    private fun chosenEntry(context: Context, game: Game): Pair<String, File>? {
        val j = prefs(context).getString(key(game), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return j.getString("f") to File(j.getString("path"))
    }

    fun chosen(context: Context, game: Game): String? = chosenEntry(context, game)?.first

    /** The ROM RetroArch opens: the chosen translation when it's on the stick, else null (the original). */
    fun romFor(context: Context, game: Game): File? = chosenEntry(context, game)?.second?.takeIf { it.isFile }

    /** Back to the original: its translated ROM goes. */
    fun useOriginal(context: Context, game: Game) {
        chosenEntry(context, game)?.second?.delete()
        prefs(context).edit().remove(key(game)).apply()
    }

    /** Blocking: downloads [t] and makes it the game's ROM from now on. [progress]: bytes so far of [Translation.size]. */
    fun use(context: Context, game: Game, t: Translation, progress: (Long) -> Unit) {
        val enc = { p: String -> p.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") } }
        val base = "https://archive.org/download/${enc(t.item)}/${enc(t.file)}"
        val dir = romDir(game.console).apply { mkdirs() }
        // The ROM inside a .7z: straight to its place, named like the game (same saves, states and cheats)
        t.inner?.let { inner ->
            val out = File(dir, "${game.baseName}.${inner.substringAfterLast('.').lowercase()}")
            val tmp = File(dir, ".${out.name}.part")
            val conn = ShopApi.open("$base/${enc(inner)}")
            try {
                if (conn.responseCode !in 200..299) throw IllegalStateException("archive.org respondió ${conn.responseCode}")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            progress(done)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            out.delete()
            if (!tmp.renameTo(out)) throw IllegalStateException("No se pudo guardar la traducción")
            chosenEntry(context, game)?.second?.takeIf { it != out }?.delete()
            prefs(context).edit().putString(key(game), JSONObject().put("f", t.file).put("path", out.path).toString()).apply()
            return
        }
        val url = base
        val zip = File(context.cacheDir, "translation.zip")
        val conn = ShopApi.open(url)
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("archive.org respondió ${conn.responseCode}")
            conn.inputStream.use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress(done)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        var target: File? = null
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val ext = e.name.substringAfterLast('.', "").lowercase()
                if (e.isDirectory || ext.isEmpty()) continue
                // Named like the game: RetroArch finds the same saves, states and cheats
                val out = File(dir, "${game.baseName}.$ext")
                val tmp = File(dir, ".${out.name}.part")
                tmp.outputStream().use { z.copyTo(it) }
                out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("No se pudo guardar la traducción")
                target = out
                break
            }
        }
        zip.delete()
        val rom = target ?: throw IllegalStateException("La descarga no traía el juego")
        // Another translation chosen before: its file goes (unless it's the same path)
        chosenEntry(context, game)?.second?.takeIf { it != rom }?.delete()
        prefs(context).edit().putString(key(game), JSONObject().put("f", t.file).put("path", rom.path).toString()).apply()
    }
}
