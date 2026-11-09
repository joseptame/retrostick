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
    class Entry(val console: String, val file: String, val md5: Set<String>)

    /** Same table as pwa/src/lib/bios.ts. */
    val ALL = listOf(
        Entry("Game Boy", "gb_bios.bin", setOf("32fbbd84168d3482956eb3c5051637f5")),
        Entry("Game Boy Color", "gbc_bios.bin", setOf("dbfce9db9deaa2567f6a84fde55f9680")),
        Entry("Game Boy Advance", "gba_bios.bin", setOf("a860e8c0b6d573d191e4ec7db1b1e4f6")),
        // Any PS1 BIOS is kept as scph5502.bin (PCSX ReARMed's "auto" finds it; the content decides, not the name)
        Entry("PlayStation", "scph5502.bin", setOf(
            "32736f17079d0b2b7024407c39bd3050", "490f666e1afb15b7362b406ed1cea246", "924e392ed05558ffdb115408c263dccf",
            "8dd7d5296a650fac7319bce665a6a53c", "1e68c231d0896b7eadcad1d7d8e76129",
        )),
        Entry("Master System", "bios_E.sms", setOf("840481177270d5642a14ca71ee72844c")),
        Entry("Mega Drive", "bios_MD.bin", setOf("45e298905a08f9cfb38fd504cd6dbc84")),
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
            md5(f) in e.md5 -> State.OK
            else -> State.UNKNOWN
        }
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
                continue
            }
            if (local.isFile && (r.md5 == null || md5(local) == r.md5)) continue
            val tmp = File(SYSTEM_DIR, "${e.file}.part")
            runCatching {
                drive.download(r.id, tmp)
                if (!tmp.renameTo(local)) { local.delete(); tmp.renameTo(local) }
            }.onFailure { tmp.delete(); Log.w("RetroStick", "BIOS ${e.file} not copied", it) }
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
