package com.retrostick.launcher

import android.content.Context

/**
 * Novedades: what each launcher version brings, in a few plain lines. NewsActivity shows the ones not seen yet
 * the first time the home screen opens after an update (never on a brand-new stick), and Retro Ajustes →
 * Actualizar launcher shows the latest ones again when there is nothing to update. Add a Release here with each version that brings something to tell.
 */
object News {
    class Item(val icon: Int, val title: String, val text: String)
    class Release(val code: Int, val name: String, val items: List<Item>)

    /** Newest first. */
    val RELEASES = listOf(
        Release(96, "1.29.0", listOf(
            Item(R.drawable.ic_news, "PlayStation en español",
                "En RetroShop, los juegos de PlayStation que salieron en España aparecen en español. En muchos otros puedes elegir una versión hecha por aficionados: con voces y textos, solo textos o mejorada."),
            Item(R.drawable.ic_settings, "Mando de PlayStation en más teles",
                "En algunas teles la ✕ y el ○ salían cambiados: ahora RetroStick mira cómo llegan los botones de tu mando."),
        )),
        Release(95, "1.28.0", listOf(
            Item(R.drawable.ic_news, "Juegos traducidos al español",
                "En la ficha de muchos juegos de NES, Super Nintendo, Game Boy, Mega Drive y Master System aparece «Idioma»: elige una traducción de aficionados y el juego se abre en español. Puedes volver al original cuando quieras. En RetroShop, la ficha de cada juego te dice si tiene traducción al español antes de descargarlo."),
        )),
        Release(94, "1.27.4", listOf(
            Item(R.drawable.ic_settings, "PlayStation sin tramado",
                "En la ficha de cada juego de PlayStation puedes quitar el tramado, esa rejilla de puntos sobre los colores: la imagen se ve más limpia."),
        )),
        Release(92, "1.27.2", listOf(
            Item(R.drawable.ic_star, "Trucos más certeros",
                "Las demos y betas ya no se confunden con el juego, y si no hay trucos de tu versión se usan los de una región parecida."),
        )),
        Release(91, "1.27.1", listOf(
            Item(R.drawable.ic_gamepad, "También en teles Android",
                "El asistente prepara RetroArch aunque ya estuviera instalado, y Nintendo 64 usa el emulador adecuado para gráficas más modernas."),
            Item(R.drawable.ic_cloud_down, "PlayStation para todos",
                "Los juegos de PlayStation de RetroShop se pueden descargar desde cualquier cuenta de la webapp."),
            Item(R.drawable.ic_star, "Neo Geo sin BIOS propia",
                "Si no tienes la BIOS de Neo Geo, RetroShop la trae con tu primer juego. Sin la de consola, los juegos van como en la recreativa: mete crédito con Select."),
        )),
        Release(90, "1.27.0", listOf(
            Item(R.drawable.ic_settings, "Elige y ordena tus consolas",
                "Retro Ajustes → Inicio es ahora una pantalla con una tarjeta por consola: enciéndelas o apágalas y cámbialas de sitio con Y. También eliges la vista y con qué arranca el stick."),
        )),
        Release(87, "1.26.2", listOf(
            Item(R.drawable.ic_resume, "Home durante un juego",
                "Te devuelve al juego en vez de sacarte. El menú del juego está en Captura (mando de Switch) o PS (DualSense)."),
            Item(R.drawable.ic_settings, "Arrancar con RetroStick o con otro launcher",
                "Lo eliges en Retro Ajustes → Inicio. Otras apps abre el launcher que tengas."),
            Item(R.drawable.ic_gamepad, "Sticks con Android 11",
                "El mando de Switch funciona como en los demás, y los juegos van sin retraso en teles a 59,94 Hz."),
        )),
        Release(85, "1.26.0", listOf(
            Item(R.drawable.ic_star, "Trucos",
                "En la ficha de cada juego: vidas infinitas, invencible, tiempo infinito… Eliges cuáles y se activan al jugar. Los logros siguen contando."),
            Item(R.drawable.ic_sticks, "Varios sticks en la misma cuenta",
                "Cada stick tiene su nombre y sus juegos. Desde el móvil eliges a qué stick mandar cada juego."),
            Item(R.drawable.ic_cloud_down, "Sigue la partida en otro stick",
                "Antes de abrir un juego, el stick trae de Drive la partida que guardaste en el otro."),
            Item(R.drawable.ic_news, "Novedades",
                "Después de cada actualización verás esta pantalla. Vuelve a verla cuando quieras en Retro Ajustes → Actualizar launcher."),
        )),
    )

    private fun prefs(context: Context) = context.getSharedPreferences("news", Context.MODE_PRIVATE)

    /** The releases this stick hasn't been told about yet, newest first. */
    fun pending(context: Context): List<Release> {
        val seen = prefs(context).getInt("seen", -1)
        if (seen == -1) {
            // First launcher with Novedades: a stick that was updated gets this version's; a new one, nothing
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (info.firstInstallTime == info.lastUpdateTime) { markSeen(context); return emptyList() }
            return RELEASES.filter { it.code == BuildConfig.VERSION_CODE }
        }
        return RELEASES.filter { it.code > seen && it.code <= BuildConfig.VERSION_CODE }
    }

    fun markSeen(context: Context) = prefs(context).edit().putInt("seen", BuildConfig.VERSION_CODE).apply()
}
