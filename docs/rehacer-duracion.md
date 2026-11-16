# Rehacer: duración de los juegos (HowLongToBeat)

**Qué pasó (2026-11-16):** la duración de los juegos llegó a estar hecha y compilando: launcher 1.31.0 (versionCode
103), unos 10.600 juegos con tiempo en las 10 consolas. Pero todo estaba en el usuario de Windows «Josep 2» sin
subir a GitHub, y ese usuario se borró. No queda nada: ni los datos (`time-*.json`), ni la caché del rascado
(`pwa/scripts/.hltb/`), ni el código. Este documento sirve para rehacerlo desde cero.

**Cómo usarlo:** abre Claude Code **en tu PC**, en la carpeta del proyecto (`retrostick`), copia todo el bloque de
abajo y pégalo como mensaje. Tiene que ser en el PC: hace falta acceso a howlongtobeat.com (desde una sesión en la
nube está bloqueado), el JDK para compilar y el stick para probar.

> **Lección:** sube a GitHub cada paso que funcione (al menos a una rama), aunque no esté terminado.

## Prompt

```
RetroStick: rehacer desde cero «cuánto dura cada juego» (HowLongToBeat). Háblame siempre en castellano.
Ya se hizo una vez (launcher 1.31.0), pero se perdió al borrar el usuario de Windows antes de subirlo.
Diseño y criterios: docs/pendiente-duracion.md (Paso 2) y este prompt. No hace falta repetir la investigación.

## Reglas
- Nunca inventes ni estimes tiempos: sin dato de un juego, no se muestra nada.
- Sube el trabajo a una rama de GitHub en cuanto cada parte funcione (pregúntame antes del push, y luego hazlo):
  que no se vuelva a perder.
- No publiques el launcher (node stick/publish-stick.mjs --launcher) ni hagas push a main sin preguntarme.
- Antes de publicar, que todo esté probado; la publicación es lo último.

## Paso 0 — La fuente de los datos (decide conmigo antes de programar)
La vez anterior los datos salieron del buscador interno de HowLongToBeat (endpoint no oficial). En
docs/pendiente-duracion.md puse como regla no saltarse ninguna protección, y ese buscador pide un token anti-bots.
Mira cómo funciona hoy el buscador y explícame en dos líneas qué haría falta. Si hay que saltarse una
protección, para y pregúntame si sigo así o lo aparcamos. Si sigo: pocas peticiones, con pausas (≥1 s), un
User-Agent normal y caché en disco (pwa/scripts/.hltb/, en .gitignore) para no repetir búsquedas.

## Paso 1 — Datos: pwa/scripts/build-times.ts
- Como pwa/scripts/build-players.ts: `npx tsx scripts/build-times.ts [consola…]` y escribe
  pwa/public/db/time-<consola>.json para nes, snes, gb, gbc, gba, md, sms, n64, neogeo, psx:
  { generated, source: "HowLongToBeat", games: { [looseKey]: [historia, extras, completo] } }
  En horas con un decimal; null cuando falta un tiempo; juego sin ningún tiempo → no entra.
- Títulos: los de pwa/public/db/<consola>.json (lo de antes del primer paréntesis), pasados por displayTitle y
  looseKey de pwa/src/lib/names.ts. Los de PlayStation en español (pwa/public/db/psx-es.json) se buscan por su
  título europeo (A Sangre Fria → Cold Blood).
- Emparejar con cuidado, filtrando por plataforma del resultado y por título casi exacto; ante la duda, sin dato.
  Casos que deben salir bien:
  · SNES «Final Fantasy III» es el FF6 en HLTB (33 h), no el FF3 real (16 h); «Final Fantasy II» es el FF4 (20 h).
    Haz una tabla de equivalencias para esos títulos americanos renumerados.
  · NES «Super Mario Bros.» → 1,8 h Historia.
- Al final, imprime por consola los juegos con tiempo y el % de cobertura.

## Paso 2 — Webapp: pwa/src/components/GameSheet.tsx
- Un pwa/src/lib/times.ts como pwa/src/lib/igdbdb.ts (carga db/time-<id>.json una vez con fetchData y busca por
  looseKey; GBC mira también el de GB).
- En la ficha (junto a «👥 Hasta N jugadores», hacia la línea 224): tres recuadros «7 h Historia»,
  «11 h Con extras», «15 h Al 100 %» (solo los que haya) y debajo, en pequeño, «Tiempos aproximados, según los
  jugadores de HowLongToBeat». Estilos en pwa/src/styles.css. Sin dato, nada.

## Paso 3 — Stick (android/app/src/main/java/com/retrostick/launcher/)
- Times.kt: como Cheats.kt (función index): baja ${BuildConfig.WEBAPP_URL}/db/time-<consola>.json con
  ShopApi.open, lo guarda en filesDir y no lo vuelve a bajar en una semana; si falla, usa el guardado.
- OJO con las claves: looseKey de Shop.kt (Android) NO quita «The» inicial, y el de la webapp sí
  (names.ts: `.replace(/^the |, the\b/g, '')`). Times.kt debe calcular la clave como la webapp para que
  «The Legend of Zelda…» empareje. Pon un test o una comprobación.
- ShopActivity.kt, fila de filtros (línea ~268 y ~616: Buscar · orden · Género · Para dos · Restablecer):
  añade «⏱ Duración» con una ventana como la de «Género» (~869): Todas / Cortos (hasta 5 h) /
  Medios (5 a 15 h) / Largos (más de 15 h), por el tiempo de «Historia». Con el filtro puesto, cada portada dice
  «⏱ 4 h» en lugar del tamaño; los juegos sin dato solo salen con «Todas». Entra en filtered() (~844) y
  «↺ Restablecer» (~855) lo quita. Icono nuevo res/drawable/ic_time.xml (línea, como ic_players).
- Ficha de RetroShop (ShopActivity.kt, ~997, debajo de la línea de información): los tres recuadros y la línea de
  la fuente, como en la webapp.
- DetailsActivity.kt, junto a «Tiempo jugado» (~150): tarjeta «Duración aproximada» con «23 h» (nunca «unas 23 h»);
  barra «Vas por el 42 % de la historia · te quedan 13 h» (tiempo jugado frente a «Historia»); pasado ese tiempo,
  «Ya has jugado más de lo que suele durar»; con «Lo he terminado» (game.finished), sin barra. Debajo
  «Con extras: 29 h · Al 100 %: 38 h» y la línea de la fuente.

## Paso 4 — Versión y documentación
- android/app/build.gradle.kts: versionCode 103 / versionName "1.31.0" (si ya hay otra más nueva, la siguiente).
- News.kt: su Release al principio de RELEASES («Cuánto dura cada juego»: filtro en RetroShop, ficha y Detalles).
- README.md: sección 8.2l (RetroShop: filtro y ficha) y 8.2m (Detalles: tarjeta), y cómo regenerar los datos.
- .gitignore: pwa/scripts/.hltb/ y android/.kotlin/.

## Paso 5 — Compilar y probar
1. En pwa/: npm run build (tsc --noEmit + vite build) y npm test si hay tests.
2. En android/: ./gradlew assembleDebug con el JDK 17 (pregúntame su ruta: el usuario de Windows ha cambiado).
3. Webapp en local (npm run dev): la ficha de un juego con tiempos y la de uno sin dato.
4. Commit y push a una rama (pregúntame), y que Vercel despliegue la webapp: el stick baja los datos de
   https://retrostick.vercel.app/db/time-<consola>.json, así que hace falta antes de probar en la tele.
5. En el stick de pruebas (app-debug.apk): el filtro «⏱ Duración», las portadas con «⏱ 4 h», «↺ Restablecer»,
   la ficha y la tarjeta de Detalles (con y sin «Lo he terminado»).
6. Comprobaciones del dato: NES Super Mario Bros. 1,8 h · SNES Final Fantasy III 33 h · SNES Final Fantasy II
   20 h · PSX A Sangre Fria con tiempos · un juego oscuro sin dato no muestra nada · un «The…» empareja igual en
   el stick y en la webapp.

## Paso 6 — Publicar (solo si todo lo anterior va, y preguntándome)
node stick/publish-stick.mjs --launcher en la raíz. Los sticks verán las Novedades de la versión nueva.
```
