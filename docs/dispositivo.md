# Dispositivo: diagnóstico (Fase 1)

Datos leídos por ADB del stick real el **2026-11-03**. Todos los comandos son de solo lectura.
No se incluyen número de serie, MAC ni cuentas (el repo es público).

## Sistema

| Dato | Valor | Comando |
|---|---|---|
| Modelo | Xiaomi `MiTV-AESP0` (device `aquaman`) | `getprop ro.product.model` / `ro.product.device` |
| SoC | Amlogic, plataforma `gxl` (S805Y) | `getprop ro.board.platform` / `ro.hardware` |
| ABI | **`armeabi-v7a,armeabi`** (sin 64 bits: `abilist64` vacío) | `getprop ro.product.cpu.abilist` |
| Android | **9** (API **28**) | `getprop ro.build.version.release` / `ro.build.version.sdk` |
| Build | `PI.2055 release-keys` | `getprop ro.build.display.id` |
| Parche de seguridad | 2022-08-05 | `getprop ro.build.version.security_patch` |
| OpenGL ES | `131072` = 0x20000 = **GLES 2.0** | `getprop ro.opengles.version` |
| RAM | 981 MB total, ~245 MB disponibles en reposo | `cat /proc/meminfo` |

## Almacenamiento (antes de la limpieza)

| Partición | Tamaño | Usado | Libre |
|---|---|---|---|
| `/data` (= `/storage/emulated/0`) | 4,8 GB | 3,7 GB | **1,0 GB** |
| `/` (sistema, solo lectura) | 1,3 GB | 1,2 GB | 137 MB |
| `/cache` | 232 MB | 17 MB | 208 MB |

Lo que más ocupa en `/data`:
- `/storage/emulated/0/Download`: **614 MB** de instaladores `.apk` antiguos (las apps ya están instaladas).
- Actualizaciones de apps del sistema en `/data/app` (Google Play Services, WebView, Chromecast…) y caché de apps.

> Con ~1 GB libre no cabían RetroArch + núcleos + juegos de PS1. Tras la limpieza (abajo): **3,7 GB libres** (1,0 GB usados).

## Limpieza (2026-11-03, aprobada por el usuario)

| Paso | Comando | Libre después |
|---|---|---|
| Inicio | | 1,0 GB |
| Borrar instaladores `.apk` de `Download/` y `Download/Downloader/` (614 MB) | `rm -f /storage/emulated/0/Download/*.apk …` | |
| Vaciar caché de todas las apps | `pm trim-caches 64G` | 2,8 GB |
| Apps del sistema ya desactivadas: quitar actualización, borrar datos y volver a desactivar | `pm uninstall <p>` · `pm clear <p>` · `pm disable-user --user 0 <p>` | 3,1 GB |
| Desinstalar Stremio, TizenTube, TDTChannels, S0undTV, Downloader (+ plugin); Prime Video: quitar actualización y desactivar | `pm uninstall <p>` | **3,7 GB** |

Apps del sistema con la actualización quitada: `katniss`, `tvlauncher`, `backdrop`, `tungsten.setupwraith`, `xm.webcontent`,
`marvin.talkback`, `tvrecommendations`, `videos`, `mitv.tvhome.michannel`, `play.games`, `amazon.amazonvideo.livingroom`.

- Al quitar la actualización de una app del sistema, Android **la reactiva**. Por eso se vuelve a desactivar en el mismo paso.
- Comprobado al terminar: siguen desactivadas las mismas 31 apps de antes (+ Prime Video) y el launcher sigue siendo FLauncher.
- **Sin tocar a propósito:** Play Store, Play Services, GSF, WebView, el teclado de Google (necesario para iniciar sesión en FolderSync),
  Ajustes, Bluetooth, servicios de Amlogic (`com.droidlogic*`), mando remoto, Chromecast (`mediashell`) y FLauncher.

Revertir:
```bash
adb shell pm enable <paquete>                 # reactivar una app del sistema (versión de fábrica; Play Store la actualiza)
# Las apps desinstaladas se reinstalan desde Play Store o su APK.
```

## Launcher

- Launcher activo: **`me.efesser.flauncher/.MainActivity`** (FLauncher 0.18.0).
- `com.google.android.tvlauncher` existe y **ya está desactivado** (`pm list packages -d`).

Comando:
```bash
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

## Paquetes

**Instalados por el usuario** (`pm list packages -3`):

| Paquete | App | Versión | APK |
|---|---|---|---|
| `me.efesser.flauncher` | FLauncher | 0.18.0 | 18 MB |
| `com.stremio.one` | Stremio | 1.9.12 | 171 MB |
| `io.gh.reisxd.tizentube.cobalt` | TizenTube Cobalt | 2.0.3 | 136 MB |
| `com.tdtchannels.player` | TDTChannels | v2026.03.1 | 9 MB |
| `com.s0und.s0undtv` | S0undTV | 1.5.10 | 9 MB |
| `com.esaba.downloader` (+ `.browserplugin`) | Downloader | 2.0.1 | 7 MB |
| `android.autoinstalls.config.xiaomi.mibox3` | configuración de Xiaomi | 1001 | 64 KB |

**Sistema ya desactivadas** (31): incluye `tvlauncher`, `youtube.tv`, `youtube.tvmusic`, `netflix.ninja`, `katniss`
(asistente), `backdrop`, `tvrecommendations`, `videos`, `play.games`, `talkback`, `miui.tv.analytics`,
`alphonso_eula`, `mitv.tvhome.michannel`, `captiveportallogin`, `printspooler`, proveedores de calendario/contactos.

**Apps del proyecto:** RetroArch, Daijishō y FolderSync **no están instaladas** todavía.

## Intents de Retro Ajustes (sección 8.2)

Resueltos con `adb shell cmd package query-activities --brief -a <acción>`:

| Acción | Resultado |
|---|---|
| `com.google.android.intent.action.CONNECT_INPUT` | ✅ `com.android.tv.settings/.accessories.AddAccessoryActivity` |
| `android.settings.BLUETOOTH_SETTINGS` | ❌ **ninguna actividad**. No sirve como alternativa; usar `CONNECT_INPUT` |
| `android.settings.WIFI_SETTINGS` | ✅ `com.android.tv.settings/.connectivity.NetworkActivity` |
| `android.settings.SETTINGS` | ✅ `com.android.tv.settings/.MainSettings` |
| `android.settings.INTERNAL_STORAGE_SETTINGS` | ✅ `com.android.tv.settings/.device.StorageResetActivity` |

## Entrada

Dispositivos actuales (`dumpsys input`): `Xiaomi RC` (mando del stick, Bluetooth, VID `0x2717` PID `0x32b9`),
`cec_input` (HDMI-CEC), `aml_keypad`. Aún no hay mandos de juego emparejados: los VID/PID del DualSense y
del Switch Pro se tomarán en la Fase 2.

## ADB por red

- `adb connect <IP>:5555` funciona con la depuración USB activada (`service.adb.tcp.port = 5555`).
- `persist.adb.tcp.port` está vacío, pero el puerto **sigue abierto tras reiniciar** (comprobado el 2026-11-03 con `adb connect` a los 2 min de arrancar).
- Recomendado: reservar la IP del stick en el router (DHCP estático).

## RetroArch (Fase 2)

- Versión **1.22.2** estable del buildbot, `RetroArch_ra32.apk`. `aapt dump badging`: paquete `com.retroarch.ra32`,
  `targetSdkVersion 28`, ABIs `armeabi-v7a` y `x86`, con actividad leanback (aparece en el launcher de la TV).
- Config: **`/storage/emulated/0/Android/data/com.retroarch.ra32/files/retroarch.cfg`**. ADB puede leerlo y escribirlo.
  En Android 11+ (Fire OS 8) ninguna app puede escribir en el `Android/data` de otra (`EACCES`): ahí el launcher usa
  su propio `/storage/emulated/0/RetroArch/retrostick.cfg` y abre los juegos con él (`CONFIGFILE`, `retroArchCfg()` en `Library.kt`).
- Al primer arranque crea `/storage/emulated/0/RetroArch/{saves,states,system,config,downloads,playlists,thumbnails,…}`.
- Directorios internos (`/data/user/0/com.retroarch.ra32/…`, ADB no puede escribir en ellos): `libretro_directory` (núcleos),
  `libretro_info_path`, `assets_directory`, `joypad_autoconfig_dir`, `overlay_directory`.
  Los dos últimos se mueven a `/storage/emulated/0/RetroArch/{autoconfig,overlays}` en `retroarch.overrides.cfg`.
  Por eso hay que ejecutar *Actualizar perfiles de mando* después de instalar.
- Opciones de núcleo: `global_core_options = "false"`, así que van en `/storage/emulated/0/RetroArch/config/<Núcleo>/<Núcleo>.opt`.
- `stick/install.ps1` aplicado el 2026-11-03: 27 claves (13 cambiaban algo), copias `retroarch.cfg.orig` y `.bak`.
- Pasos del Actualizador en línea hechos el 2026-11-03 (manejando el menú por ADB):
  núcleos **Gambatte**, **mGBA** y **PCSX ReARMed** instalados (aparecen en *Cargar núcleo*); información de núcleos actualizada;
  perfiles de mando descargados en `/storage/emulated/0/RetroArch/autoconfig` (1.107 `.cfg`; en `android/` hay
  `DualSense Wireless Controller (Android 11/13, Google TV 12)`, `Pro Controller.cfg` y `Wireless Controller.cfg`).
  Libre después: 3,3 GB.
- *Cargar núcleo → Instalar o restaurar un núcleo* instala un `.so` desde `/storage/emulated/0/RetroArch/downloads`
  (`core_assets_directory`). Es una forma de instalar núcleos sin root a partir de un `adb push`.

### Pruebas de juego (2026-11-03)

Lanzados con el mismo intent que usará Daijishō:
```bash
adb shell am start -n com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture \
  -e ROM "/storage/emulated/0/ROMs/<consola>/<juego>" \
  -e LIBRETRO /data/user/0/com.retroarch.ra32/cores/<núcleo>_libretro_android.so \
  -e CONFIGFILE /storage/emulated/0/Android/data/com.retroarch.ra32/files/retroarch.cfg
```
Núcleos probados: `gambatte_libretro_android.so`, `mgba_libretro_android.so`.
⏳ `pcsx_rearmed_libretro_android.so` sigue el mismo patrón, pero falta probarlo con un juego de PS1.

FPS medidos con `dumpsys SurfaceFlinger --latency 'com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture#0'`
(pantalla a 60 Hz):

| Juego | Núcleo | Escala | Sin mezcla | Con opciones del repo |
|---|---|---|---|---|
| Zelda Oracle of Ages (GBC) | Gambatte | ×7 (1120×1008) | 58,5 FPS | 59,5 FPS |
| Zelda The Minish Cap (GBA) | mGBA | ×6 (1440×960) | 59,0 FPS | 58,1 FPS |

- Al salir con Escape ×2 (`input_exit_emulator`, `quit_press_twice = true`) se crean `<juego>.srm` en `/Partidas/saves` y
  `<juego>.state.auto` en `/Partidas/states`. **Nada en `/ROMs`.** `am force-stop` no guarda.
- `aspect_ratio_index = "22"` es `ASPECT_RATIO_CORE` (`gfx/video_defines.h`): la proporción la da el núcleo. Es lo correcto.
- Opciones de núcleo del repo (`stick/retroarch/core-options/`), copias completas de los `.opt` generados con estos cambios:
  - Gambatte: `gambatte_gb_colorization = "internal"` + `gambatte_gb_internal_palette = "GB - DMG"` (verde clásico; con
    `disabled` los juegos de GB salen en gris), `gambatte_mix_frames = "mix"`, `gambatte_gbc_color_correction = "GBC only"`.
    Alternativas de mezcla: `lcd_ghosting`, `lcd_ghosting_fast`. Gambatte no tiene opción de bordes.
  - mGBA: `mgba_color_correction = "GBA"`, `mgba_interframe_blending = "mix"` (alternativas: `mix_smart`, `lcd_ghosting`,
    `lcd_ghosting_fast`).
  - RetroArch no reescribe los `.opt` al salir (comprobado).

### Manejar el menú de RetroArch por ADB

`adb shell input keyevent <código>` y `adb exec-out screencap -p > captura.png` para ver dónde se está:

| Acción en el menú | Tecla (keyevent) |
|---|---|
| Arriba / abajo / izquierda / derecha | 19 / 20 / 21 / 22 |
| Aceptar | 23 (DPAD_CENTER) |
| Buscar (botón X del RetroPad) | 47 (`S`); después `input text <texto>` y 66 (Intro) |
| Bajar ~10 elementos (botón R) | 51 (`W`) |
| Volver | ⚠️ no funcionan ni 4 (BACK) ni 54 (`Z`) dentro de una búsqueda; es más fiable `am force-stop com.retroarch.ra32` y volver a abrir |

## RetroStick Launcher (Fase 4, en curso)

- Instalado `com.retrostick.launcher` (APK de `android/`, firmado con la clave de depuración local) y fijado como
  pantalla de inicio el 2026-11-03:
  ```bash
  adb shell cmd package set-home-activity com.retrostick.launcher/.MainActivity   # verificado: "Success"
  # Volver a FLauncher (sigue instalado):
  adb shell cmd package set-home-activity me.efesser.flauncher/.MainActivity
  ```
- Comprobado tras `adb reboot`: el stick arranca en el launcher (~24 s hasta `sys.boot_completed`).
- Un `am force-stop` del launcher hace que Android lo reabra al momento (es la app de inicio).
- Arranque en frío: ~1,7 s hasta el primer fotograma. Mientras tanto se ve `bg_launch` (fondo + brillo + mando) y después
  la animación RetroStick (~1,6 s).
- **La animación "Android TV" del arranque no se puede cambiar sin root**: es `/system/media/bootanimation.zip`, `/` está
  montado en solo lectura, `ro.build.type=user`, `ro.debuggable=0`, sin `su`. Tampoco existen
  `/oem/media`, `/product/media` ni `/data/local/bootanimation.zip`.
- `pm grant com.retrostick.launcher android.permission.{READ,WRITE}_EXTERNAL_STORAGE` evita el diálogo de permisos.
- Portadas en caché en `files/covers/<consola>/` (con `index.txt`, índice de `Named_Boxarts` de la API de GitHub).
  Borrar caché: `adb shell run-as com.retrostick.launcher rm -rf files/covers` (solo con APK de depuración).

## Reposo y HDMI-CEC

- Reposo: pantalla apagada a los 15 min (`screen_off_timeout = 900000`) y stick dormido a los 30 min
  (`sleep_timeout = 1800000`). Se mantiene así por decisión del usuario; dormido no sincroniza ni responde por ADB.
- `hdmi_control_auto_device_off_enabled` pasa de `1` a **`0`** (2026-11-03, a petición del usuario): el stick ya no apaga
  la TV al dormirse. HDMI-CEC sigue activo (`hdmi_control_enabled = 1`). Lo aplican también `install.ps1`/`install.sh`.
  Revertir: `adb shell settings put global hdmi_control_auto_device_off_enabled 1`.

## Estado de los ⚠️ VERIFICAR

| Sección | Punto | Estado |
|---|---|---|
| 2 | Espacio de usuario de 32 bits | ✅ Confirmado `armeabi-v7a` |
| 2 | GLES 2.0, sin Vulkan | ✅ Confirmado GLES 2.0 |
| 5.1 | ADB por red en el puerto 5555 | ✅ Funciona y sigue activo tras reiniciar |
| 5.5 | Paquete del launcher de Google | ✅ `com.google.android.tvlauncher`, ya desactivado; el launcher actual es FLauncher |
| 5.5 | `cmd package set-home-activity` en Android 9 | ✅ Funciona (launcher propio fijado y comprobado tras reiniciar) |
| 5.5 | Abrir Ajustes de Android desde otra app | ✅ `android.settings.SETTINGS` resuelve a `MainSettings` |
| 8.2 | Intent "Emparejar mando" | ✅ `CONNECT_INPUT` funciona; ❌ la alternativa `BLUETOOTH_SETTINGS` no existe |
| 5.2 | Nombre del APK y paquete de RetroArch 32 bits | ✅ `RetroArch_ra32.apk` → paquete **`com.retroarch.ra32`** (no `com.retroarch`) |
| 5.2 / 6.2 | Ruta real del `retroarch.cfg` y claves | ✅ Ver "RetroArch" abajo; las 27 claves existen en la 1.22.2 |
| 6.2 | Valor de Start + Select en `input_menu_toggle_gamepad_combo` | ✅ `4` = `INPUT_COMBO_START_SELECT` (`input/input_defines.h`, v1.22.2) |
| 5.2 | Paquetes de Daijishō y FolderSync | ⏳ Fase 3 (al instalarlos) |
| 5.3 / 8.2 | Intent público de sincronización de FolderSync | ⏳ Fase 3 |
| 6.3 | Opciones de Gambatte y mGBA | ✅ Sacadas de los `.opt` generados y de los valores en el código de cada núcleo (ver "Pruebas de juego") |
| 6.3 | RTC de mGBA (Pokémon) | ✅ No hay opción: mGBA lo activa solo para `AXVE/AXVS`, `AXPE/AXPS` y `BPEE/BPES` (`src/gba/overrides.c`) |
| 6.3 | Opciones y BIOS de PCSX ReARMed | ⏳ Falta un juego de PS1 para generar su `.opt` |
