package com.retrostick.launcher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * RetroAchievements in RetroArch. The account lives in Drive as Retro/Config/ra.json — {"user": "…", "token": "…"},
 * the token RetroAchievements gives at login (never the password). Each sync copies it to the stick and
 * writes it into RetroArch's config (cheevos_* keys), and again right before a game starts, because RetroArch
 * rewrites its config when it closes. Softcore: quick saves and fast-forward keep working.
 */
object RetroAchievements {
    const val FILE = "ra.json"

    class Account(val user: String, val token: String)

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
        val cfg = File(RETROARCH_CFG)
        if (!cfg.isFile) return
        val a = account(context)
        val keys = linkedMapOf(
            "cheevos_enable" to (a != null).toString(),
            "cheevos_username" to (a?.user ?: ""),
            "cheevos_token" to (a?.token ?: ""),
            "cheevos_password" to "",
            "cheevos_hardcore_mode_enable" to "false",
        )
        runCatching {
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
        }.onFailure { Log.w("RetroStick", "RetroAchievements: retroarch.cfg not written", it) }
    }
}
