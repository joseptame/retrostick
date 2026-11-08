package com.retrostick.launcher

import org.json.JSONObject
import java.io.File

/**
 * Games marked as finished ("Lo he terminado"): date and play time at that moment. Kept in
 * /Partidas/.stick/terminados.json, which DriveSync copies both ways with the saves, so it survives a reset
 * and the phone can read it too. Key: Game.key ("gba/Legend of Zelda, The - The Minish Cap (Europe)…").
 */
object Finished {
    class Entry(val at: Long, val seconds: Long)

    private val file get() = File("$SDCARD/Partidas/.stick/terminados.json")

    fun load(): Map<String, Entry> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { k ->
            json.getJSONObject(k).let { Entry(it.optLong("at"), it.optLong("seconds")) }
        }
    }.getOrDefault(emptyMap())

    /** Marks or unmarks [game]; returns the new entry (null = not finished). Blocking: tiny file. */
    fun set(game: Game, done: Boolean): Entry? {
        val json = runCatching { JSONObject(file.readText()) }.getOrDefault(JSONObject())
        val entry = if (done) Entry(System.currentTimeMillis(), game.runtimeSeconds) else null
        if (entry != null) json.put(game.key, JSONObject().put("at", entry.at).put("seconds", entry.seconds).put("title", game.title))
        else json.remove(game.key)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "terminados.json.tmp")
        tmp.writeText(json.toString(2))
        if (!tmp.renameTo(file)) { file.writeText(json.toString(2)); tmp.delete() }
        game.finished = entry
        return entry
    }
}
