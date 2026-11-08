package com.retrostick.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * Interface sounds ("Ambiente" set, chosen on 2026-11-08 in docs/maquetas/sonidos.html; the WAVs in res/raw are
 * rendered from that page). Loaded once into a SoundPool so a key press plays with no delay. Never plays while a
 * game runs: only the launcher and RetroShop call it.
 */
object Sounds {
    enum class Sound(val res: Int) {
        MOVE(R.raw.snd_move), TAB(R.raw.snd_tab), OPEN(R.raw.snd_open), BACK(R.raw.snd_back),
        LAUNCH(R.raw.snd_launch), DONE(R.raw.snd_done), ERROR(R.raw.snd_error), BOOT(R.raw.snd_boot),
    }

    enum class Level(val label: String, val volume: Float) {
        OFF("Desactivados", 0f), LOW("Volumen bajo", 0.3f), MEDIUM("Volumen medio", 0.6f), HIGH("Volumen alto", 1f),
    }

    private var pool: SoundPool? = null
    private val ids = mutableMapOf<Sound, Int>()
    private val loaded = mutableSetOf<Int>()
    /** Asked for before it finished loading (the boot sound at a cold start): played once ready. */
    private var pending: Sound? = null
    private lateinit var app: Context

    fun init(context: Context) {
        if (pool != null) return
        app = context.applicationContext
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            ).build()
        p.setOnLoadCompleteListener { _, id, status ->
            if (status != 0) return@setOnLoadCompleteListener
            loaded += id
            pending?.let { s -> if (ids[s] == id) { pending = null; play(s) } }
        }
        Sound.values().forEach { ids[it] = p.load(app, it.res, 1) }
        pool = p
    }

    private fun prefs() = app.getSharedPreferences("sounds", Context.MODE_PRIVATE)

    var level: Level
        get() = runCatching { Level.valueOf(prefs().getString("level", Level.MEDIUM.name)!!) }.getOrDefault(Level.MEDIUM)
        set(value) = prefs().edit().putString("level", value.name).apply()

    /** Off → bajo → medio → alto → off (the Retro Ajustes card). */
    fun nextLevel(): Level {
        val all = Level.values()
        level = all[(level.ordinal + 1) % all.size]
        return level
    }

    fun play(sound: Sound) {
        if (pool == null) return
        val vol = level.volume
        if (vol == 0f) return
        val id = ids[sound] ?: return
        if (id !in loaded) { pending = sound; return }
        // Fewer streams than presses when scrolling fast: the newest move wins, the older ones are cut short.
        pool?.play(id, vol, vol, if (sound == Sound.MOVE) 0 else 1, 0, 1f)
    }
}
