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

## Botones en cada consola (DualSense)

| | Game Boy / Color (Gambatte) | Game Boy Advance (mGBA) | PlayStation (PCSX ReARMed) |
|---|---|---|---|
| ✕ / ○ | A | A | ✕ / ○ |
| □ / △ | B | B | □ / △ |
| Options | Start | Start | Start |
| Create | Select | Select | Select |
| Cruceta y stick izquierdo | Moverse | Moverse | Cruceta / stick |
| L1 | **Guardado rápido** | L | L1 |
| R1 | **Avance rápido x2** (pulsar para activar/desactivar) | R | R1 |
| L2 | — | **Guardado rápido** | L2 |
| R2 | — | **Avance rápido x2** (activar/desactivar) | R2 |
| PS | Menú de RetroArch | Menú de RetroArch | Menú de RetroArch |

También abre el menú **Create + Options** (`input_menu_toggle_gamepad_combo = 4`).

Archivos (los instala `install.ps1`):

| Archivo del repo | En el stick | Qué hace |
|---|---|---|
| `stick/retroarch/remaps/Gambatte/Gambatte.rmp` | `RetroArch/config/remaps/Gambatte/` | ✕○ → A, □△ → B, L/R sin función, stick = cruceta |
| `stick/retroarch/overrides/Gambatte/Gambatte.cfg` | `RetroArch/config/Gambatte/` | L1 guardar, R1 avance rápido, x2 |
| `stick/retroarch/remaps/mGBA/mGBA.rmp` | `RetroArch/config/remaps/mGBA/` | ✕○ → A, □△ → B, L2/R2 sin función, stick = cruceta |
| `stick/retroarch/overrides/mGBA/mGBA.cfg` | `RetroArch/config/mGBA/` | L2 guardar, R2 avance rápido, x2 |

Los atajos de las configuraciones por núcleo usan los códigos del DualSense (100/101/102/103). Con otro mando habrá
que añadir sus códigos cuando se empareje.

`remap_save_on_exit = "false"` evita que RetroArch reescriba los remapeos al salir.

El guardado rápido (L1 en GB/GBC, L2 en GBA) va a la posición 0 (`<juego>.state` en `/Partidas/states`). Para cargarlo:
PS → *Cargar guardado rápido*. Al abrir un juego se carga solo el `.state.auto` del cierre anterior.
