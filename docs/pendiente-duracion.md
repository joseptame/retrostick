# Pendiente: duración de los juegos (HowLongToBeat)

Idea aparcada el 2026-11-15. Para retomarla, abre Claude Code en esta carpeta del proyecto y pega el prompt de abajo.

## Prompt

```
Quiero retomar la duración de los juegos en RetroStick (docs/pendiente-duracion.md).

Antes de programar nada, comprueba si ya hay una forma permitida de sacar los tiempos de HowLongToBeat
(«Historia», «Con extras», «Al 100 %») para los juegos de la tienda, en este orden:
1. ¿HowLongToBeat tiene ya una API pública u oficial, o permite usar sus datos para un proyecto personal?
2. ¿Qué cuesta y qué condiciones tiene el acceso con licencia de TollBit
   (https://tollbit.com/marketplace/how-long-to-beat/)? ¿Acepta proyectos personales y deja guardar los datos?
3. ¿Hay alguna recopilación pública y con licencia libre más completa que PlayMyData (Zenodo 10797761), que
   en 2026-11 solo cubría el 1–13 % de los juegos de la tienda (32 % en N64)?
4. ¿IGDB (game_time_to_beats) ya tiene datos suficientes de juegos antiguos?

Para cada opción, mide la cobertura contra los juegos de la tienda (pwa/public/db/<consola>.json, títulos
con looseKey de src/lib/names.ts), con ejemplos: Super Mario Bros. 3, A Link to the Past, Golden Sun,
Sonic the Hedgehog, Pokémon Rojo, Super Mario 64, Final Fantasy VII.
No te saltes ninguna protección de HowLongToBeat (su buscador pide un token anti-bots): si la única vía es esa,
para y dímelo. Respóndeme en castellano con lo que hay y tu recomendación, y espera a que elija.

Si hay una fuente buena, el diseño ya aprobado es https://claude.ai/artifact/EGWXYSy2CQ4nmbSb9xyWav:
- Ficha de la tienda: tres recuadros «7 h Historia», «11 h Con extras», «15 h Al 100 %» y debajo
  «Tiempos aproximados, según los jugadores de HowLongToBeat» (o la fuente que sea). Sin datos, nada.
- Detalles: tarjeta «Duración aproximada · 23 h» junto al tiempo jugado (nunca «unas 23 h»), barra
  «Vas por el 42 % de la historia · te quedan 13 h», «Con extras: 29 h · Al 100 %: 38 h» y la línea de la
  fuente. Con «Lo he terminado», sin barra.
- RetroShop: filtro «⏱ Duración» (Todas / Cortos hasta 5 h / Medios 5 a 15 h / Largos más de 15 h) por el
  tiempo de «Historia»; con el filtro puesto, cada portada dice su duración; «Restablecer» lo quita.
- Los datos se generan una vez con un script en pwa/scripts (como build-players.ts) a
  pwa/public/db/time-<consola>.json, y la tienda y el stick los leen de ahí.
```

## Lo que se probó (2026-11-15)

- **IGDB** (`game_time_to_beats`): casi nada de juegos antiguos (A Link to the Past con 1 voto; Super Mario 64,
  Sonic y Golden Sun sin datos).
- **HowLongToBeat**: sin API; su buscador (`/api/search/site`) pide un token anti-bots. No se usó.
- **PlayMyData** (CC BY 4.0, IGDB + HowLongToBeat, juegos desde 1993): cobertura de la tienda NES 1 %, SMS 1 %,
  GB 2 %, GBC 3 %, GBA 3 %, MD 4 %, Neo Geo 9 %, SNES 10 %, PS1 13 %, N64 32 %.
- **TollBit** vende el acceso a HowLongToBeat; sin precio publicado (hay que pedir acceso o una demo).
