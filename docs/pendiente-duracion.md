# Pendiente: duración de los juegos

Idea aparcada el 2026-11-15 porque no había de dónde sacar los datos de forma permitida.
Para retomarla: abre Claude Code en la carpeta del proyecto (`retrostick`), copia todo el bloque de abajo y pégalo
como mensaje.

## Prompt

```
Retomamos una idea aparcada de RetroStick: mostrar cuánto dura cada juego. Háblame siempre en castellano.

## Qué quiero
Tres tiempos por juego, como los de HowLongToBeat: «Historia» (acabarlo sin desviarse), «Con extras» y
«Al 100 %», en la tienda de la tele (RetroShop), en Detalles y en un filtro de la tienda.

## Por qué se aparcó (2026-11-15)
No había ninguna fuente permitida con buena cobertura de juegos antiguos:
- HowLongToBeat no tiene API; su buscador (/api/search/site) pide un token anti-bots.
- IGDB (game_time_to_beats, la misma API que ya usan las fichas): casi sin datos de juegos antiguos
  (A Link to the Past con 1 voto; Super Mario 64, Sonic y Golden Sun, nada).
- PlayMyData (Zenodo 10797761, CC BY 4.0, IGDB + HowLongToBeat, solo juegos desde 1993): cubría de los
  juegos de la tienda NES 1 %, Master System 1 %, GB 2 %, GBC 3 %, GBA 3 %, Mega Drive 4 %, Neo Geo 9 %,
  SNES 10 %, PlayStation 13 %, N64 32 %.
- TollBit (https://tollbit.com/marketplace/how-long-to-beat/) vende el acceso a HowLongToBeat, sin precio
  publicado.

## Reglas
- No te saltes ninguna protección de ningún sitio (tokens anti-bots, límites, inicio de sesión) ni uses
  scrapers de terceros que lo hagan. Si la única vía es esa, para y dímelo.
- Solo fuentes cuyas condiciones permitan un proyecto personal y guardar los datos.
- No publiques nada, no instales nada en los sticks y no hagas push sin preguntarme.
- Sin datos de un juego no se muestra nada: nunca inventes ni estimes tiempos.

## Paso 1: investigar (sin programar)
Mira qué ha cambiado y mide cada opción:
1. HowLongToBeat: ¿API oficial, exportación o permiso para proyectos personales?
2. TollBit: precio y condiciones (¿proyectos personales?, ¿se pueden guardar los datos?).
3. Recopilaciones públicas con licencia libre más completas que PlayMyData (Kaggle, Zenodo, Hugging Face,
   GitHub…). Comprueba fecha, licencia y que los datos vengan de verdad de HowLongToBeat.
4. IGDB game_time_to_beats: ¿tiene ya datos de juegos antiguos? Credenciales en pwa/.env.local
   (IGDB_CLIENT_ID / IGDB_CLIENT_SECRET); ver pwa/scripts/build-players.ts.
5. Cualquier otra fuente seria que encuentres.

Para cada fuente útil, mide la cobertura por consola contra los títulos de la tienda: los nombres de
pwa/public/db/<consola>.json (nes, snes, gb, gbc, gba, md, sms, n64, neogeo, psx), sin lo que va entre
paréntesis, emparejados con looseKey de pwa/src/lib/names.ts. Comprueba también estos juegos y si los tiempos
cuadran con los de HowLongToBeat: Super Mario Bros. 3, The Legend of Zelda: A Link to the Past, Pokémon Rojo,
Sonic the Hedgehog, Golden Sun, Super Mario 64, Final Fantasy VII.

Termina el paso 1 con una tabla (fuente · licencia/condiciones · coste · cobertura por consola · pega) y tu
recomendación. Si ninguna pasa del 50 % en la mayoría de consolas, dilo y propón seguir aparcándolo.
Espera a que yo elija.

## Paso 2: hacerlo (solo si elijo una fuente)
Diseño aprobado: https://claude.ai/artifact/EGWXYSy2CQ4nmbSb9xyWav (léelo con la herramienta de artifacts).
- Datos: un script en pwa/scripts (como build-players.ts) genera una vez pwa/public/db/time-<consola>.json:
  { generated, source, games: { [looseKey]: [historia, extras, completo] } } en horas, con un decimal.
  Se emparejan los títulos como en players-*.json. Los juegos de PlayStation en español (db/psx-es.json)
  usan el título europeo.
- Ficha de RetroShop (android/.../ShopActivity.kt), debajo de la línea de información: tres recuadros
  «7 h Historia», «11 h Con extras», «15 h Al 100 %» y debajo, en pequeño, «Tiempos aproximados, según los
  jugadores de <fuente>». Si falta uno de los tres, solo los que haya.
- Detalles (DetailsActivity.kt), junto a «Tiempo jugado»: tarjeta «Duración aproximada» con «23 h» (nunca
  «unas 23 h»); debajo, una barra «Vas por el 42 % de la historia · te quedan 13 h» (tiempo jugado frente a
  «Historia»), «Con extras: 29 h · Al 100 %: 38 h» y la línea de la fuente. Pasado el tiempo de la
  historia: «Ya has jugado más de lo que suele durar». Con «Lo he terminado», sin barra.
- Filtro de RetroShop: «⏱ Duración», que se elige en una ventana como «Género»: Todas / Cortos (hasta 5 h) /
  Medios (5 a 15 h) / Largos (más de 15 h), por el tiempo de «Historia». Con el filtro puesto, cada portada
  dice «⏱ 4 h» en lugar del tamaño; los juegos sin datos solo salen con «Todas»; «↺ Restablecer» lo quita.
- El stick baja time-<consola>.json de la webapp y lo guarda una semana, como cheats-<consola>.json (Cheats.kt).
- Webapp (pwa/src/components/GameSheet.tsx): los tres tiempos en la ficha, con la misma línea de la fuente.
- Respeta la licencia de la fuente (cita o atribución donde haga falta).
- Sube versionCode/versionName en android/app/build.gradle.kts, añade su Release en News.kt y documenta en el
  README (sección 8.2l RetroShop y Detalles en 8.2m). Compila con
  JAVA_HOME="C:/Users/Josep 2/tools/jdk-17" ./gradlew assembleDebug en android/.
- Cuando compile, enséñame cómo queda y pídeme permiso antes de publicar
  (node stick/publish-stick.mjs --launcher).
```

## Lo que se probó (2026-11-15)

- **IGDB** (`game_time_to_beats`): casi nada de juegos antiguos.
- **HowLongToBeat**: sin API; su buscador pide un token anti-bots. No se usó.
- **PlayMyData** (CC BY 4.0): cobertura de la tienda entre el 1 % (NES) y el 32 % (N64).
- **TollBit**: vende el acceso a HowLongToBeat; sin precio publicado.
