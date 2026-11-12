package com.retrostick.launcher

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * The consoles' boot screens (the Game Boy's Nintendo logo, the PlayStation's Sony one…) come from their BIOS /
 * boot ROM. RetroStick never downloads one: the user uploads their own from the phone app (Stick → Pantallas de
 * inicio) to Drive Retro/Config/bios, each sync copies them to RetroArch/system, and before each game the launcher
 * turns each core's "boot screen" option on or off (Retro Ajustes → Pantallas de inicio).
 */
object Bios {
    /**
     * [zipCrcs]: a zip BIOS (Neo Geo), checked by the CRC32 of the files inside (its MD5 depends on the packing);
     * [copies]: other places its core looks for it.
     */
    class Entry(val console: String, val file: String, val md5: Set<String>, val zipCrcs: Set<String> = emptySet(),
                val copies: List<File> = emptyList())

    /** Same table as pwa/src/lib/bios.ts. */
    val ALL = listOf(
        Entry("Game Boy", "gb_bios.bin", setOf("32fbbd84168d3482956eb3c5051637f5")),
        Entry("Game Boy Color", "gbc_bios.bin", setOf("dbfce9db9deaa2567f6a84fde55f9680")),
        Entry("Game Boy Advance", "gba_bios.bin", setOf("a860e8c0b6d573d191e4ec7db1b1e4f6")),
        // Any PS1 BIOS is kept as scph5502.bin (PCSX ReARMed's "auto" finds it; the content decides, not the name)
        Entry("PlayStation", "scph5502.bin", setOf(
            "32736f17079d0b2b7024407c39bd3050", "490f666e1afb15b7362b406ed1cea246", "924e392ed05558ffdb115408c263dccf",
            "8dd7d5296a650fac7319bce665a6a53c", "1e68c231d0896b7eadcad1d7d8e76129",
            // SCPH-7502 (v4.1 E), checked on the stick: Sony and PlayStation logos, then the game
            "b9d9a0286c33dc6b7237bb13cd46fdee",
        )),
        Entry("Master System", "bios_E.sms", setOf("840481177270d5642a14ca71ee72844c")),
        Entry("Mega Drive", "bios_MD.bin", setOf("45e298905a08f9cfb38fd504cd6dbc84")),
        // Needed to play at all. FinalBurn Neo's current 000-lo.lo, sfix.sfix, sm1.sm1 (older sets don't start any
        // game) and the European AES BIOS; FinalBurn Neo finds it next to the games (and in system/fbneo).
        Entry("Neo Geo", "neogeo.zip", emptySet(), zipCrcs = setOf("5a86cff2", "c2ea0cfd", "94416d67", "d27a71f1"),
            copies = listOf(File("$SDCARD/ROMs/neogeo/neogeo.zip"), File("$SDCARD/RetroArch/system/fbneo/neogeo.zip"))),
    )

    val SYSTEM_DIR = File("$SDCARD/RetroArch/system")
    private const val PREF = "boot_screens"

    fun enabled(context: Context) = context.getSharedPreferences("retrostick", Context.MODE_PRIVATE).getBoolean(PREF, true)
    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences("retrostick", Context.MODE_PRIVATE).edit().putBoolean(PREF, on).apply()

    enum class State { MISSING, OK, UNKNOWN }

    /**
     * Whether each BIOS is on the stick and is a known dump. An unknown one is not used: a PS1 BIOS that reports
     * v3.0 E but isn't a known dump left Dragon Ball - Final Bout on a black screen (2026-11-09).
     */
    fun states(): List<Pair<Entry, State>> = ALL.map { e ->
        val f = File(SYSTEM_DIR, e.file)
        e to when {
            !f.isFile -> State.MISSING
            e.zipCrcs.isNotEmpty() -> if (zipCrcs(f).containsAll(e.zipCrcs)) State.OK else State.UNKNOWN
            md5(f) in e.md5 -> State.OK
            else -> State.UNKNOWN
        }
    }

    private fun zipCrcs(f: File): Set<String> = runCatching {
        java.util.zip.ZipFile(f).use { z -> z.entries().asSequence().map { "%08x".format(it.crc) }.toSet() }
    }.getOrDefault(emptySet())

    /** A good zip BIOS also where its core looks for it (Neo Geo: next to the games); a bad or missing one, nowhere. */
    private fun placeCopies(e: Entry) {
        if (e.copies.isEmpty()) return
        val src = File(SYSTEM_DIR, e.file)
        val ok = src.isFile && zipCrcs(src).containsAll(e.zipCrcs)
        for (c in e.copies) runCatching {
            when {
                !ok -> if (c.isFile) c.delete()
                !(c.isFile && c.length() == src.length() && md5(c) == md5(src)) -> { c.parentFile?.mkdirs(); src.copyTo(c, overwrite = true) }
            }
        }.onFailure { Log.w("RetroStick", "BIOS ${e.file} copy to $c", it) }
    }

    /** During a Drive sync: Retro/Config/bios mirrored into RetroArch/system (only the files in [ALL]). */
    fun sync(drive: DriveClient, folderId: String) {
        val remote = drive.list(folderId).filter { !it.isFolder }.associateBy { it.name }
        SYSTEM_DIR.mkdirs()
        for (e in ALL) {
            val local = File(SYSTEM_DIR, e.file)
            val r = remote[e.file]
            if (r == null) {
                // Taken out of Drive: off the stick too
                if (local.isFile) local.delete()
                placeCopies(e)
                continue
            }
            if (local.isFile && (r.md5 == null || md5(local) == r.md5)) { placeCopies(e); continue }
            val tmp = File(SYSTEM_DIR, "${e.file}.part")
            runCatching {
                drive.download(r.id, tmp)
                if (!tmp.renameTo(local)) { local.delete(); tmp.renameTo(local) }
            }.onFailure { tmp.delete(); Log.w("RetroStick", "BIOS ${e.file} not copied", it) }
            placeCopies(e)
        }
    }

    /** Each core's boot screen option, written into its .opt before a game (see [launchInRetroArch]). */
    fun applyOptions(context: Context) {
        val on = enabled(context)
        val ok = states().filter { it.second == State.OK }.map { it.first.file }.toSet()
        // A core only uses a BIOS that is a known dump; otherwise it starts the game as before
        fun use(vararg files: String) = files.any { it in ok }
        fun en(b: Boolean) = if (b) "enabled" else "disabled"
        val gb = use("gb_bios.bin", "gbc_bios.bin")
        val gba = use("gba_bios.bin")
        val psx = use("scph5502.bin")
        val sega = use("bios_E.sms", "bios_MD.bin")
        val opts = mapOf(
            "Gambatte" to mapOf("gambatte_gb_bootloader" to en(on && gb)),
            "mGBA" to mapOf("mgba_use_bios" to if (gba) "ON" else "OFF", "mgba_skip_bios" to if (on && gba) "OFF" else "ON"),
            // HLE = PCSX ReARMed's own BIOS, the one used before
            "PCSX-ReARMed" to mapOf("pcsx_rearmed_bios" to if (psx) "auto" else "HLE", "pcsx_rearmed_show_bios_bootlogo" to en(on && psx)),
            "Genesis Plus GX" to mapOf("genesis_plus_gx_bios" to en(on && sega)),
        )
        for ((core, keys) in opts) {
            // Only when RetroArch already made the file (its first game with that core): it fills in the rest
            val f = File("$SDCARD/RetroArch/config/$core/$core.opt")
            if (f.isFile) writeRetroArchCfg(keys, f)
        }
    }

    private val md5Cache = HashMap<String, Pair<Long, String>>()

    private fun md5(f: File): String {
        val stamp = f.length() * 31 + f.lastModified()
        md5Cache[f.path]?.let { if (it.first == stamp) return it.second }
        val d = MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; d.update(buf, 0, n) }
        }
        return d.digest().joinToString("") { "%02x".format(it) }.also { md5Cache[f.path] = stamp to it }
    }
}
