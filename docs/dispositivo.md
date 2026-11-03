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
- `persist.adb.tcp.port` está vacío. **Pendiente:** comprobar que el puerto sigue abierto tras reiniciar el stick.
- Recomendado: reservar la IP del stick en el router (DHCP estático).

## Estado de los ⚠️ VERIFICAR

| Sección | Punto | Estado |
|---|---|---|
| 2 | Espacio de usuario de 32 bits | ✅ Confirmado `armeabi-v7a` |
| 2 | GLES 2.0, sin Vulkan | ✅ Confirmado GLES 2.0 |
| 5.1 | ADB por red en el puerto 5555 | ✅ Funciona; ⏳ falta comprobar tras reinicio |
| 5.5 | Paquete del launcher de Google | ✅ `com.google.android.tvlauncher`, ya desactivado; el launcher actual es FLauncher |
| 5.5 | Abrir Ajustes de Android desde otra app | ✅ `android.settings.SETTINGS` resuelve a `MainSettings` |
| 8.2 | Intent "Emparejar mando" | ✅ `CONNECT_INPUT` funciona; ❌ la alternativa `BLUETOOTH_SETTINGS` no existe |
| 5.2 | Nombre del APK y paquete de RetroArch 32 bits | ⏳ Fase 2 (al instalarlo) |
| 5.2 / 6.2 | Ruta real del `retroarch.cfg` y claves | ⏳ Fase 2 (RetroArch no está instalado) |
| 5.2 | Paquetes de Daijishō y FolderSync | ⏳ Fase 3 (al instalarlos) |
| 5.3 / 8.2 | Intent público de sincronización de FolderSync | ⏳ Fase 3 |
| 6.3 | Nombres de opciones de núcleo y BIOS de PCSX ReARMed | ⏳ Fase 2 (código del núcleo / `.opt` generado) |
