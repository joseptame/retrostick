# Mandos

## Emparejar

En el launcher: pestaña **Retro Ajustes → Emparejar mando** (o *Ajustes → Mandos y accesorios → Añadir accesorio*).

- **DualSense (PS5):** mantener **PS + Create** hasta que la luz parpadee rápido.
- **Switch Pro Controller:** mantener el botón de sincronización (arriba, junto al USB-C). ⏳ Pendiente de probar.

El DualSense se apaga solo tras unos minutos sin usarse; se vuelve a encender con PS.

## DualSense en Android 9 (medido el 2026-11-03)

Android 9 no trae distribución de teclas para el DualSense (`054c:0ce6`): usa `Generic.kl`, que asigna los botones
HID en su orden interno. Resultado medido con `adb shell getevent -lt /dev/input/event4` pulsando cada botón:

| Botón | Evento del núcleo | Código Android (`Generic.kl`) | Valor |
|---|---|---|---|
| □ | `BTN_GAMEPAD` | `BUTTON_A` | 96 |
| ✕ | `BTN_EAST` | `BUTTON_B` | 97 |
| ○ | `BTN_C` | `BUTTON_C` | 98 |
| △ | `BTN_NORTH` | `BUTTON_X` | 99 |
| L1 | `BTN_WEST` | `BUTTON_Y` | 100 |
| R1 | `BTN_Z` | `BUTTON_Z` | 101 |
| L2 (digital) | `BTN_TL` | `BUTTON_L1` | 102 |
| R2 (digital) | `BTN_TR` | `BUTTON_R1` | 103 |
| Create | `BTN_TL2` | `BUTTON_L2` | 104 |
| Options | `BTN_TR2` | `BUTTON_R2` | 105 |
| Panel táctil (clic) | `BTN_THUMBL` | `BUTTON_THUMBL` | 106 |
| R3 | `BTN_START` | `BUTTON_START` | 108 |
| L3 | `BTN_SELECT` | `BUTTON_SELECT` | 109 |
| PS | `BTN_MODE` | `BUTTON_MODE` | 110 |
| Cruceta | `ABS_HAT0X/Y` | `AXIS_HAT_X/Y` | `h0…` |
| Stick izquierdo | `ABS_X/Y` | `AXIS_X/Y` | ejes 0/1 |
| Stick derecho | `ABS_Z/RZ` | `AXIS_Z/RZ` | ejes 2/3 |
| L2/R2 analógicos | `ABS_RX/RY` | `AXIS_RX/RY` | RetroArch no los lee (usa L2/R2 digitales) |

Sin root no se puede añadir un `.kl` al sistema (`/vendor` es de solo lectura), así que se corrige en cada app:

- **Launcher:** `StickStatus.standardKey()` traduce estos códigos para que en el menú ✕ = Jugar, ○ = Atrás,
  △ = Favorito, □ = Detalles y L1/R1 = cambiar de consola, como indican las ayudas de la pantalla.
- **RetroArch:** perfil `stick/retroarch/autoconfig/android/DualSense Wireless Controller (Android 9, RetroStick).cfg`.
- **Menús de Android (Ajustes, etc.):** siguen con el mapeo de `Generic.kl` (✕ hace de "Atrás"). Para esos menús,
  mejor el mando a distancia.

### Perfil de RetroArch

- El perfil oficial `DualSense Wireless Controller (Android 13).cfg` tiene el mismo nombre y vid/pid pero los códigos de
  Android 11+, y RetroArch elige al azar entre perfiles empatados (`tasks/task_autodetect.c`; `input_phys` no sirve en
  Android porque el driver no la rellena). `install.ps1` lo mueve a `autoconfig/disabled/`.
- **Si se vuelve a usar *Actualizar perfiles de mando* en RetroArch, hay que ejecutar `install.ps1` otra vez.**
- Los `(default-off)` tienen las líneas de identificación comentadas: no compiten.

## Switch Pro Controller en Android 9 (medido el 2026-11-09)

Igual que el DualSense, llega por `Generic.kl` en el orden HID (`getevent -lt`):

| Botón | Evento del núcleo | Código Android (`Generic.kl`) | Valor |
|---|---|---|---|
| A | `BTN_GAMEPAD` | `BUTTON_A` | 96 |
| B | `BTN_EAST` | `BUTTON_B` | 97 |
| X | `BTN_C` | `BUTTON_C` | 98 |
| Y | `BTN_NORTH` | `BUTTON_X` | 99 |
| L / R | `BTN_WEST` / `BTN_Z` | `BUTTON_Y` / `BUTTON_Z` | 100 / 101 |
| ZL / ZR | `BTN_TL` / `BTN_TR` | `BUTTON_L1` / `BUTTON_R1` | 102 / 103 |
| − / + | `BTN_TL2` / `BTN_TR2` | `BUTTON_L2` / `BUTTON_R2` | 104 / 105 |
| Captura | `BTN_THUMBL` | `BUTTON_THUMBL` | 106 |
| L3 / R3 | `BTN_SELECT` / `BTN_START` | `BUTTON_SELECT` / `BUTTON_START` | 109 / 108 |
| Home | `BTN_MODE` | `BUTTON_MODE` | 110 |

Los mismos códigos que el DualSense salvo los cuatro de la cara. **Se juega como el mando de la Play** (decisión
del usuario): **A = ✕**, **B = ○**, **Y = □**, **X = △**; L/R = L1/R1, ZL/ZR = L2/R2, − = Create, + = Options,
Home = PS, Captura = panel táctil. Así valen tal cual los atajos (− + L / R / ZR) y las tablas de abajo.

- **Launcher:** `StickStatus.standardKey()` (tabla `PRO_CONTROLLER_GENERIC_KL`); las ayudas de la pantalla muestran
  las letras de Nintendo (A Jugar · B Atrás · Y Detalles · X Favorito · L/R · ZL Buscar · ZR Logros · − Manual · + Vista).
- **RetroArch:** perfil `stick/retroarch/autoconfig/android/Pro Controller (Android 9, RetroStick).cfg`. El oficial
  `Pro Controller.cfg` (códigos de Android 11+, la X sería L) se mueve a `autoconfig/disabled/`.

## Botones en cada consola (DualSense)

| | NES (FCEUmm) | Super Nintendo (Snes9x 2010) | Game Boy / Color (Gambatte) | Game Boy Advance (mGBA) | Mega Drive / Master System (Genesis Plus GX) | PlayStation (PCSX ReARMed) |
|---|---|---|---|---|---|---|
| ✕ | B | B | A | A | B / 1 | ✕ |
| ○ | A | A | A | A | C / 2 | ○ |
| □ | B | Y | B | B | A / — | □ |
| △ | A | X | B | B | — | △ |
| Options | Start | Start | Start | Start | Start / Pause | Start |
| Create | Select | Select | Select | Select | Mode / — | Select |
| Cruceta y stick izquierdo | Moverse | Moverse | Moverse | Moverse | Moverse | Cruceta / stick |
| L1 | — | L | — | L | — | L1 |
| R1 | — | R | — | R | — | R1 |
| L2 | — | — | — | — | — | L2 |
| R2 | — | — | — | — | — | R2 |
| Create + L1 | **Guardado rápido** | **Guardado rápido** | **Guardado rápido** | **Guardado rápido** | **Guardado rápido** | **Guardado rápido** |
| Create + R1 | **Cargar el guardado rápido** | **Cargar el guardado rápido** | **Cargar el guardado rápido** | **Cargar el guardado rápido** | **Cargar el guardado rápido** | **Cargar el guardado rápido** |
| Create + R2 | **Avance rápido x2** (activar/desactivar) | **Avance rápido x2** (activar/desactivar) | **Avance rápido x2** (activar/desactivar) | **Avance rápido x2** (activar/desactivar) | **Avance rápido x2** (activar/desactivar) | **Avance rápido x2** (activar/desactivar) |
| PS | Menú de RetroArch | Menú de RetroArch | Menú de RetroArch | Menú de RetroArch | Menú de RetroArch | Menú de RetroArch |
| Panel táctil (clic) | **Quitar / poner el marco** | **Quitar / poner el marco** | **Quitar / poner el marco** | **Quitar / poner el marco** | **Quitar / poner el marco** | **Quitar / poner el marco** |

También abre el menú **Create + Options** (`input_menu_toggle_gamepad_combo = 4`).

Archivos (los instala `install.ps1`):

| Archivo del repo | En el stick | Qué hace |
|---|---|---|
| `stick/retroarch/remaps/Gambatte/Gambatte.rmp` | `RetroArch/config/remaps/Gambatte/` | ✕○ → A, □△ → B, L/R sin función, stick = cruceta |
| `stick/retroarch/remaps/mGBA/mGBA.rmp` | `RetroArch/config/remaps/mGBA/` | ✕○ → A, □△ → B, L2/R2 sin función, stick = cruceta |
| `stick/retroarch/remaps/FCEUmm/FCEUmm.rmp` | `RetroArch/config/remaps/FCEUmm/` | Como el mando original: ✕□ → B, ○△ → A, L/R sin función, stick = cruceta |
| `stick/retroarch/remaps/Snes9x 2010/Snes9x 2010.rmp` | `RetroArch/config/remaps/Snes9x 2010/` | Botones como el mando de SNES, L2/R2 sin función, stick = cruceta |
| `stick/retroarch/remaps/Genesis Plus GX/Genesis Plus GX.rmp` | `RetroArch/config/remaps/Genesis Plus GX/` | Botones del núcleo (□✕○ = A B C), L2/R2 sin función, stick = cruceta |
| `stick/retroarch/overrides/<Núcleo>/<Núcleo>.cfg` | `RetroArch/config/<Núcleo>/` | Avance rápido a x2 (los atajos están en `retroarch.cfg`) |
| `stick/retroarch/overrides/<Núcleo>/<consola>.cfg` | `RetroArch/config/<Núcleo>/` | Tamaño fijo del juego y marco de esa consola (ver README 6.4) |
| `stick/retroarch/overlays/retrostick/` | `RetroArch/overlays/retrostick/` | Marcos (PNG 1920×1080) |

Los atajos (Create + L1 / R1 / R2) son los mismos en todas las consolas: el launcher los escribe en `retroarch.cfg`
antes de cada partida (`input_enable_hotkey_btn = "104"`, Create). Usan los códigos del DualSense en Android 9; con
otro mando habrá que añadir los suyos.

`remap_save_on_exit = "false"` evita que RetroArch reescriba los remapeos al salir.

Los juegos empiezan desde el título (`savestate_auto_load = "false"`). El guardado rápido va a la posición 0
(`<juego>.state` en `/Partidas/states`) y al cerrar se guarda también `<juego>.state.auto`. En el launcher,
□ Detalles → **Continuar…** abre el juego en uno de los dos: *Donde lo dejaste* (`.state.auto`) o *Guardado rápido*
(`.state`; el launcher lo copia a `.state.auto` y activa la carga automática solo para esa partida).

## Botones en el inicio del launcher (DualSense)

| Botón | Qué hace |
|---|---|
| ✕ | Jugar / abrir |
| ○ | Atrás |
| △ | Favorito |
| □ | Detalles (continuar desde un guardado, borrar, «Lo he terminado») |
| L1 / R1 | Cambiar de consola |
| L2 | Buscar entre tus juegos del stick y de Drive |
| Options | Cambiar la vista del inicio: carátulas o lista |
| Create | Manual del juego (si hay) |
| R2 | Logros del juego (RetroAchievements; no en Super Nintendo) |
| ▲ (vista de carátulas, desde el estante) | Ir al tráiler y las capturas de la ficha |
| ▶ (vista de lista) | Ir al tráiler y las capturas de la ficha |

En **Mis logros** (Retro Ajustes): ▲▼ moverse, L1/R1 Juegos · Recientes, ✕ ver los logros de ese juego, ○ volver.
En los **logros de un juego**: ▲▼ moverse, L1/R1 Todos · Conseguidos · Por conseguir, ○ volver.

## Jugar a dos

Empareja el segundo mando en Retro Ajustes → Emparejar mando. RetroArch da los puestos por orden de conexión: el
primero que se conecta es el jugador 1 y el segundo, el jugador 2 (el launcher lo avisa al conectarlo). En NES y Super
Nintendo el jugador 2 tiene la misma distribución que el 1 (`input_player2_*` en los `.rmp`). La tienda tiene el filtro
«👥 Para dos» y la ficha dice hasta cuántos jugadores (datos de IGDB, `pwa/scripts/build-players.ts`).
