# RetroStick

Convertir un **Xiaomi Mi TV Stick** en una consola retro para jugar a **mis propios juegos originales dumpeados** (Game Boy, Game Boy Color, Game Boy Advance y PlayStation 1), con:

- Un **menú de inicio en la TV propio** (*RetroStick Launcher*), que es la pantalla de inicio del stick: arranca al encenderlo, con secciones por consola, logos y portadas (diseño: `docs/maquetas/inicio-stick.png`).
- La colección guardada en **Google Drive**, sincronizada automáticamente con el stick.
- Una **PWA en el móvil** para subir copias, elegir qué juegos están en el stick y gestionar las partidas guardadas.
- **Retro Ajustes**, integrado en el launcher como una pestaña más, para emparejar mandos, forzar la sincronización y abrir los ajustes.

> **Cambio de especificación (2026-11-03):** el menú de la TV ya no es Daijishō sino un launcher propio que replica la maqueta. Retro Ajustes deja de ser una app aparte y pasa a ser parte del launcher.

> **Para Claude Code:** este documento es la especificación completa del proyecto. Léelo entero antes de empezar. Trabaja por fases (sección 9) y no pases a la siguiente sin cumplir los criterios de aceptación de la actual. Cuando algo esté marcado como **⚠️ VERIFICAR**, compruébalo en el dispositivo real (por ADB), en la documentación o en el código fuente oficial antes de darlo por bueno. No inventes nombres de paquete, claves de configuración ni intents.

---

## Índice
1. [Reglas del proyecto](#1-reglas-del-proyecto)
2. [Hardware y limitaciones](#2-hardware-y-limitaciones)
3. [Arquitectura](#3-arquitectura)
4. [Google Drive: estructura de carpetas](#4-google-drive-estructura-de-carpetas)
5. [Stick: instalación y configuración](#5-stick-instalación-y-configuración)
6. [Emuladores (RetroArch)](#6-emuladores-retroarch)
7. [PWA del móvil](#7-pwa-del-móvil)
8. [Mini app "Retro Ajustes" (Android TV)](#8-mini-app-retro-ajustes-android-tv)
9. [Plan de trabajo por fases](#9-plan-de-trabajo-por-fases)
10. [Estructura del repositorio](#10-estructura-del-repositorio)
11. [Diseño visual](#11-diseño-visual)
12. [Problemas conocidos y soluciones](#12-problemas-conocidos-y-soluciones)

---

## 1. Reglas del proyecto

- **Nunca** se suben ROMs, imágenes de disco, BIOS ni partidas guardadas al repositorio. Viven solo en el Google Drive del usuario. El `.gitignore` debe bloquear explícitamente `*.gb *.gbc *.gba *.bin *.cue *.chd *.iso *.img *.pbp *.m3u *.srm *.sav *.state* *.zip *.7z`.
- **No se descarga ni se distribuye ninguna BIOS.** La de PS1 la extrae el usuario de su propia consola. Si no existe, se usa la BIOS HLE integrada en PCSX ReARMed.
- **Todo el contenido son copias de juegos que posee el usuario.** La app no debe incluir buscadores ni enlaces de descarga de ROMs.
- Las **portadas** se cargan en tiempo de ejecución desde `libretro-thumbnails` y no se guardan en el repo. Las de `docs/maquetas/img/` son solo para las maquetas.
- **Idioma:** la interfaz de la PWA y del launcher va en **español**. El código y los comentarios pueden ir en inglés.
- **Secretos:** el *Client ID* de OAuth no es secreto, pero se configura por variable de entorno (`VITE_GOOGLE_CLIENT_ID`), no se escribe en el código. No hay *client secret*: la PWA usa el flujo de token en el navegador.

---

## 2. Hardware y limitaciones

| | |
|---|---|
| Dispositivo | Xiaomi Mi TV Stick (1080p), chip **Amlogic S805Y** |
| CPU | 4× Cortex-A53 |
| GPU | Mali-450: **solo OpenGL ES 2.0, sin Vulkan** |
| RAM | 1 GB |
| Almacenamiento | 8 GB (unos 4–5 GB libres) |
| Sistema | **Android TV 9**, espacio de usuario de **32 bits (armeabi-v7a)** ⚠️ VERIFICAR con `adb shell getprop ro.product.cpu.abilist` |
| Conectividad | Wi-Fi ac, Bluetooth 4.2, un micro-USB que también sirve de alimentación (OTG con adaptador alimentado) |

**Consecuencias:**
- Hay que instalar las **builds de 32 bits** de RetroArch y de los núcleos (`armeabi-v7a`).
- Nada que necesite GLES 3 o Vulkan. Shaders solo ligeros (GLSL, de una pasada).
- **Consolas incluidas:** GB, GBC, GBA y PS1. **Excluidas a propósito:** N64, NDS, PSP y Dreamcast, por rendimiento.
- Mandos del usuario: **DualSense (PS5)** y **Switch Pro Controller**. En Android 9 se emparejan por Bluetooth pero no tienen mapeo nativo, así que necesitan *autoconfig* o reasignación en RetroArch. DualShock 4 y los mandos de Xbox funcionan sin configuración.

---

## 3. Arquitectura

```
📱 PWA (móvil) ──Drive API v3──► ☁️ Google Drive /Retro
                                    ├── Coleccion/      (toda la colección; no se sincroniza)
                                    ├── JugandoAhora/   (lo que debe estar en el stick)
                                    ├── Partidas/       (saves + states + estado del stick)
                                    └── Config/         (BIOS del usuario, configuración)
                                              │
                              sincronización propia del launcher (en el stick)
                                              ▼
📺 Mi TV Stick
   ├── /storage/emulated/0/ROMs/      ← JugandoAhora (Drive → stick, en espejo)
   ├── /storage/emulated/0/Partidas/  ← Partidas (bidireccional, sin borrar)
   ├── RetroStick Launcher (app propia = pantalla de inicio de Android)
   │     ├── secciones por consola, portadas, partidas, tiempo jugado
   │     └── pestaña Retro Ajustes (mando, sincronización, Wi-Fi, ajustes)
   └── RetroArch 32 bits (com.retroarch.ra32) + núcleos: Gambatte, mGBA, PCSX ReARMed
```

- **La PWA no habla con el stick.** Solo ordena archivos en Drive. El stick se pone al día en su próxima sincronización.
- **"Poner un juego en el stick"** = mover el archivo de `Coleccion/<consola>/` a `JugandoAhora/<consola>/` (cambiar el *parent* en Drive). **"Quitarlo"** = devolverlo a `Coleccion/`.
- **Las partidas nunca se borran** del Drive al quitar un juego. Por eso viven en una carpeta aparte.
- **Estado del stick:** el launcher escribe `Partidas/.stick/status.json` (espacio libre, hora de la última sincronización, mando conectado). La sincronización del launcher lo sube y la PWA lo lee para la pestaña "Stick".

---

## 4. Google Drive: estructura de carpetas

```
Retro/
├── Coleccion/
│   ├── gb/   gbc/   gba/   psx/
├── JugandoAhora/
│   ├── gb/   gbc/   gba/   psx/
├── Partidas/
│   ├── saves/          ← RetroArch savefile_directory  (*.srm)
│   ├── states/         ← RetroArch savestate_directory (*.state, *.state.auto)
│   └── .stick/status.json
└── Config/
    └── bios/           ← scph*.bin del usuario (opcional)
```

- Los nombres de carpeta de consola (`gb`, `gbc`, `gba`, `psx`) son fijos. Los usan la PWA y el launcher.
- Los archivos de juego se nombran según **No-Intro** (cartuchos) o **Redump** (PS1). Así funcionan las portadas automáticas.
  Ejemplo: `Pokemon - Edicion Esmeralda (Spain).gba`.
- **PS1:** formato preferido `.chd`. Para juegos de varios discos, `.m3u` + discos en la misma carpeta.
- La PWA crea esta estructura la primera vez que se usa, si no existe, y guarda los IDs de las carpetas (en `appProperties` de la carpeta `Retro` y en caché en `localStorage`).

---

## 5. Stick: instalación y configuración

### 5.1 Preparar ADB (desde el PC con VS Code)
1. En el stick: *Ajustes → Preferencias del dispositivo → Información →* pulsar 7 veces en **Compilación**.
2. *Opciones de desarrollador →* activar **Depuración USB**. En Android TV esto suele habilitar también ADB por red en el puerto 5555. ⚠️ VERIFICAR
3. Anotar la IP del stick (*Ajustes → Red*).
4. En el PC:
   ```bash
   adb connect <IP_STICK>:5555
   adb devices          # aceptar el aviso de depuración en la TV
   adb shell getprop ro.product.cpu.abilist
   adb shell getprop ro.build.version.release
   ```

### 5.2 Script de instalación `stick/install.sh` (y `install.ps1` si el PC es Windows)
Debe ser **idempotente** (se puede ejecutar varias veces) y hacer:

1. Comprobar la conexión ADB y la arquitectura. Abortar con un mensaje claro si no es lo esperado.
2. Descargar e instalar los APK (en `stick/apks/`, ignorado por git):
   - **RetroArch 32 bits.** Build estable del buildbot de libretro (`buildbot.libretro.com/stable/<versión>/android/`). El APK de 32 bits suele llamarse `RetroArch_ra32.apk`. ⚠️ VERIFICAR el nombre y el paquete instalado (`adb shell pm list packages | grep -i retroarch`).
   - **RetroStick Launcher.** APK compilado en la fase 4 (`android/app/build/outputs/apk/...`).
3. Crear las carpetas: `/storage/emulated/0/ROMs/{gb,gbc,gba,psx}`, `/storage/emulated/0/Partidas/{saves,states,.stick}`, `/storage/emulated/0/RetroArch/system`.
4. Copiar la configuración de RetroArch (sección 6) a su ruta. Ruta verificada: `/storage/emulated/0/Android/data/com.retroarch.ra32/files/retroarch.cfg` (accesible por ADB). Hacer **copia de seguridad** del cfg existente antes de sobrescribirlo.
5. Copiar los archivos de opciones de núcleo y los *remaps* o *autoconfig* de los mandos.
6. **Opcional, con confirmación explícita:** sustituir el launcher (sección 5.5).
7. Mostrar al final una lista de **pasos manuales pendientes** (descarga de núcleos, conectar Google Drive en Retro Ajustes, etc.).

> Los núcleos (`.so`) se instalan en el directorio interno de la app, al que ADB no puede escribir sin root. **Paso manual:** en RetroArch, *Menú principal → Actualizador en línea → Descargar núcleos →* Gambatte, mGBA y PCSX ReARMed. Después, *Actualizar archivos de información de núcleos* y *Actualizar autoconfiguraciones de mandos*. Si hay alguna forma fiable de automatizarlo sin root, se puede proponer.

### 5.3 Sincronización con Google Drive (dentro del launcher)
> **Cambio (2026-11-03):** FolderSync no se puede instalar desde la Play Store del stick ("Tu dispositivo no es
> compatible") y su documentación dice que Android TV no está soportado. La sincronización la hace el propio launcher
> (`DriveSync.kt`).

- **Inicio de sesión:** flujo de Google para TV: el stick muestra un código y el usuario lo introduce en
  `google.com/device` desde el móvil. Cliente OAuth de tipo *TV y dispositivos de entrada limitada*
  (`docs/google-cloud.md`). Ese flujo **solo admite `drive.file`/`drive.appdata`**.
- **Partidas** (`Retro/Partidas/{saves,states,.stick}` ↔ `/Partidas/...`): en los dos sentidos, gana el más reciente,
  **nunca borra**. Las subidas actualizan el mismo archivo de Drive (conserva las revisiones para la PWA).
- **Juegos** (`Retro/JugandoAhora/<c>` → `/ROMs/<c>`): descarga lo que hay en Drive y **borra solo los juegos que
  descargó él mismo** (lista en las preferencias de la app). Los juegos copiados a mano al stick se suben a
  `JugandoAhora/<c>`.
- **Cuándo:** solo con el launcher en pantalla (nunca durante una partida): al volver a él si la última sincronización
  tiene más de 1 minuto, cada 15 minutos y con *Retro Ajustes → Sincronizar ahora*. Se cancela entre archivos al
  lanzar un juego.
- Crea la estructura de la sección 4 si no existe. Con `drive.file` **solo ve lo creado por RetroStick**: una carpeta
  `Retro/` creada a mano en la web de Drive no se usa.

### 5.4 Menú de la TV
Lo da el launcher propio (sección 8). Sustituye a Daijishō, que ya no se usa.

### 5.5 Arranque directo en el menú de juegos
El launcher declara `android.intent.category.HOME`, así que el stick arranca en él y el botón Home vuelve a él.
- El launcher de Google ya está desactivado (`docs/dispositivo.md`). El actual es **FLauncher** (`me.efesser.flauncher`), que **se conserva** como salida de emergencia.
- Fijar el launcher propio como Home por defecto:
  ```bash
  adb shell cmd package set-home-activity com.retrostick.launcher/.MainActivity   # ⚠️ VERIFICAR en Android 9
  # Revertir a FLauncher:
  adb shell cmd package set-home-activity me.efesser.flauncher/.MainActivity
  ```
- **Requisito previo:** desde la pestaña Retro Ajustes se puede abrir *Ajustes de Android* (`android.settings.SETTINGS`, verificado). Incluir el comando de reversión en `docs/` y en la salida del script.

---

## 6. Emuladores (RetroArch)

### 6.1 Núcleos
| Consola | Núcleo | Carpeta |
|---|---|---|
| Game Boy | Gambatte | `/ROMs/gb` |
| Game Boy Color | Gambatte | `/ROMs/gbc` |
| Game Boy Advance | mGBA (alternativa: gpSP si algún juego va lento) | `/ROMs/gba` |
| PlayStation | PCSX ReARMed | `/ROMs/psx` |

### 6.2 `stick/retroarch/retroarch.overrides.cfg`
No se sustituye el `retroarch.cfg` entero: el script **fusiona** estas claves en el existente, con un script que sustituye o añade líneas `clave = "valor"`. ⚠️ VERIFICAR cada clave contra la versión instalada (generar un `retroarch.cfg` limpio y comparar).

```ini
# Directorios
savefile_directory = "/storage/emulated/0/Partidas/saves"
savestate_directory = "/storage/emulated/0/Partidas/states"
system_directory = "/storage/emulated/0/RetroArch/system"
savefiles_in_content_dir = "false"
savestates_in_content_dir = "false"
sort_savefiles_enable = "false"
sort_savestates_enable = "false"

# Guardado
savestate_auto_save = "true"
savestate_auto_load = "true"
autosave_interval = "10"
savestate_thumbnail_enable = "false"

# Vídeo (Mali-450 / GLES2)
video_driver = "gl"
video_threaded = "true"
video_vsync = "true"
video_smooth = "false"
video_scale_integer = "true"
video_shader_enable = "false"

# Audio
audio_latency = "96"

# Rendimiento
rewind_enable = "false"
run_ahead_enabled = "false"

# Menú y controles
menu_driver = "ozone"
input_menu_toggle_gamepad_combo = "4"     # Start + Select ⚠️ VERIFICAR valor
input_autodetect_enable = "true"
pause_nonactive = "true"
config_save_on_exit = "false"             # para que la configuración del repo no se pise
```

**Atajos con el mando** (botón de atajos = Select). Los índices de botón dependen del mando, así que se definen en el *autoconfig* o se documentan para asignarlos una vez desde el menú:
- Select + Start → menú
- Select + R1 → guardar estado · Select + L1 → cargar estado
- Select + R2 → avance rápido (mantener)

### 6.3 Opciones de núcleo (`stick/retroarch/core-options/*.opt`)
⚠️ VERIFICAR los nombres exactos en el código de cada núcleo o en el `.opt` que genera RetroArch.

**Gambatte (GB/GBC)**
- Paleta GB: verde clásico de la DMG (configurable).
- Corrección de color GBC: activada.
- **Mezcla de fotogramas / LCD ghosting: activada** (transparencias por parpadeo).
- Bordes: desactivados (se usan overlays).

**mGBA (GBA)**
- Corrección de color: activada (pantalla de GBA).
- **Interframe blending: activado.**
- Reloj en tiempo real (RTC): automático (Pokémon Rubí/Zafiro/Esmeralda).

**PCSX ReARMed (PS1)**
- Dynarec: activado.
- Renderizado en hilo: activado si existe la opción.
- Resolución interna: original (sin mejoras).
- Salto de fotogramas: desactivado.
- BIOS: usa la del usuario si existe en `system/`, si no la HLE. ⚠️ VERIFICAR qué nombres de archivo busca la versión instalada (habitualmente `scph1001.bin`, `scph5501.bin`, `scph7001.bin`, `scph101.bin`; comprobar si admite la PAL `scph5502.bin`). Documentar en `docs/bios-ps1.md` cómo extraerla de una PS1 propia (FreePSXBoot + volcador), **sin enlaces a descargas de BIOS**.

### 6.4 Overlays y marcos
- GB/GBC/GBA: escalado entero + marco decorativo (overlays libres de `libretro/overlay-borders` u otro repo con licencia compatible). Comprobar el rendimiento.
- PS1: sin marco, 4:3.

### 6.5 Mandos
- Crear o validar `stick/retroarch/autoconfig/` para **DualSense** y **Switch Pro Controller** en Android 9: nombre del dispositivo y VID/PID tal como los ve RetroArch (*Ajustes → Entrada → Puerto 1*, o `adb shell getevent -il`).
- Primero se actualizan las autoconfiguraciones oficiales. Solo si fallan, se añaden las propias.
- Documentar el emparejamiento en `docs/mandos.md`:
  - DualSense: **PS + Create** hasta que la luz parpadee.
  - Switch Pro: botón de sincronización.

---

## 7. PWA del móvil

### 7.1 Stack
- **Vite + React + TypeScript**
- **vite-plugin-pwa** (manifest, service worker, instalable, pantalla completa)
- **Google Identity Services** (token client) + **Drive API v3** por REST (`fetch`, sin el SDK pesado `gapi` si no hace falta)
- Estilos: CSS propio con tokens (sección 11), sin librerías de componentes pesadas
- Despliegue: **GitHub Pages** con GitHub Actions (`base` de Vite = `/<nombre-repo>/`)

### 7.2 Google Cloud (pasos para el usuario, documentar en `docs/google-cloud.md`)
1. console.cloud.google.com → proyecto nuevo "RetroStick".
2. *APIs y servicios → Biblioteca →* activar **Google Drive API**.
3. *Pantalla de consentimiento OAuth →* tipo **Externo**, estado **Pruebas**, añadir el Gmail del usuario como **usuario de prueba**.
4. *Credenciales → Crear ID de cliente OAuth → Aplicación web.* Orígenes JavaScript autorizados:
   - `http://localhost:5173`
   - `https://<usuario>.github.io`
5. Copiar el Client ID a `.env.local` (`VITE_GOOGLE_CLIENT_ID=...`) y como secreto o variable del repo para el workflow de Pages.

### 7.3 Ámbito OAuth: decisión
- **`https://www.googleapis.com/auth/drive.file`** (actualizado 2026-11-03). El flujo de TV del stick no admite el
  ámbito `drive` completo, y `drive.file` es no sensible: sin revisión de Google ni aviso de ámbito restringido.
- Consecuencia: la PWA y el stick solo ven lo que crea RetroStick dentro de `Retro/`. Los juegos se suben desde la PWA
  (o copiándolos al stick).
- ⚠️ VERIFICAR: si un archivo creado con un cliente OAuth es visible para otro cliente del mismo proyecto con
  `drive.file`. Si no, la PWA usará el mismo cliente de TV (flujo de código desde el navegador).
- El token dura unos 60 minutos: renovación silenciosa y, si falla, botón "Volver a iniciar sesión".

### 7.4 Pantallas (ver `docs/maquetas/pwa-movil.png` y `movil.html`)
Barra inferior: **Colección · Subir · Partidas · Stick**.

**Colección**
- Cabecera: número de juegos y "sincronizado hace X" (de `status.json`).
- Tarjeta de espacio: tamaño total de `JugandoAhora` frente al espacio libre del stick (de `status.json`; si no existe, 4,5 GB por defecto). Avisar si se supera.
- Filtros: Todos · GB · GBC · GBA · PS1.
- Cuadrícula de 2 columnas: portada, nombre legible e interruptor **"En el stick" / "Solo en Drive"**.
- El interruptor mueve el archivo entre `Coleccion/<c>` y `JugandoAhora/<c>` con `files.update?addParents=&removeParents=`. Actualización optimista con *rollback* si falla.
- PS1 con `.m3u`: mover el `.m3u` **y todos los discos** que referencia como una sola unidad. Lo mismo con `.cue` y sus `.bin`.

**Ficha del juego**
- Portada grande, nombre, consola, región y tamaño.
- Interruptor "En el stick" con texto de estado.
- **Partidas guardadas** de ese juego: buscar en `Partidas/saves` y `Partidas/states` archivos cuyo nombre base coincida con el del juego (`<nombre>.srm`, `<nombre>.state`, `<nombre>.state.auto`, `<nombre>.state1`…).
  - **Descargar:** baja el archivo al móvil.
  - **Restaurar copia anterior:** usa `files.revisions.list` + descarga de la revisión + subida como versión actual. Pedir confirmación y avisar de que el stick debe tener el juego cerrado.
- Borrar juego de la colección: con doble confirmación. **Nunca** borra partidas.

**Subir copias**
- Selector de archivos múltiple (`.gb .gbc .gba .chd .cue .bin .m3u .pbp`; `.zip` opcional).
- Detección de consola por extensión. Para `.bin`/`.chd`/`.cue` → psx.
- **Identificación del juego (cartuchos):** calcular **CRC32 y SHA-1** en el navegador (Web Crypto para SHA-1, CRC32 propio o librería ligera) y buscarlo en los DAT **No-Intro** de `libretro-database` (`metadat/no-intro/Nintendo - Game Boy*.dat`).
  - Generar en *build* (script `pwa/scripts/build-db.ts`) un JSON compacto `{sha1|crc: nombre}` por consola en `pwa/public/db/`, para no descargar los DAT en tiempo de ejecución.
  - Si no hay coincidencia: búsqueda aproximada por nombre de archivo y, si tampoco, el usuario escribe el nombre a mano.
- **PS1:** identificación por nombre de archivo frente a los DAT de **Redump** (`metadat/redump/Sony - PlayStation.dat`), o por la etiqueta del disco si es fácil. Si no hay coincidencia, nombre manual.
- Muestra "nombre original tachado → nombre No-Intro", chip de consola, chip "✓ Reconocido" o "⚠ Revisar" y barra de progreso.
- **Subida reanudable** (`uploadType=resumable`, trozos de 8 MiB, múltiplos de 256 KiB) con reintentos y reanudación tras un corte.
- Interruptor **"Enviar también al stick"**: sube directamente a `JugandoAhora/<c>` en vez de a `Coleccion/<c>`.
- Evitar duplicados: si ya existe un archivo con el mismo nombre o SHA-1, preguntar.

**Partidas**
- Lista de todas las partidas (saves y states) ordenadas por fecha, con portada del juego, y las mismas acciones que en la ficha.

**Stick**
- Datos de `status.json`: última sincronización, espacio libre, mando conectado y versión de Retro Ajustes.
- Si `status.json` tiene más de 24 h: aviso "El stick no se ha sincronizado desde…".

### 7.5 Portadas
```
https://raw.githubusercontent.com/libretro-thumbnails/<Sistema>/master/Named_Boxarts/<Nombre>.png
```
- `<Sistema>`: `Nintendo_-_Game_Boy`, `Nintendo_-_Game_Boy_Color`, `Nintendo_-_Game_Boy_Advance`, `Sony_-_PlayStation`.
- `<Nombre>` = nombre No-Intro/Redump sin extensión, sustituyendo `&*/:\`<>?\|"` por `_` y codificado con `encodeURIComponent`.
- **Preferencia del usuario: portada europea siempre que exista.** Con el índice de `Named_Boxarts` (API de GitHub, `git/trees`, en caché una semana), entre las portadas con el mismo título se elige: europea con el nombre exacto > edición española `(Spain)` > otra europea > nombre exacto > otra región. Se descartan Beta/Proto/Demo/Sample/Kiosk.
- Sin índice (sin red o límite de la API): nombre exacto, sin la parte de idiomas `(En,Fr,De,…)`, `(Europe)`, `(USA, Europe)` y `(USA)`. Si no hay ninguna, tarjeta generada con degradado y título (como en la primera maqueta).
- Caché en el service worker (`CacheFirst`, 30 días).

### 7.6 Requisitos técnicos
- Funciona bien con una mano en el móvil. Objetivos táctiles de al menos 44 px.
- Modo offline: muestra la última colección en caché con aviso "sin conexión"; acciones deshabilitadas.
- Capa `drive.ts` con funciones tipadas: `ensureFolders`, `listFolder`, `moveFile`, `uploadResumable`, `download`, `listRevisions`, `restoreRevision`, `readJson`.
- Tests unitarios (Vitest) para: normalización de nombres, URL de portadas, detección de consola, agrupación `.m3u`/`.cue`, emparejamiento juego ↔ partidas.
- Lint + typecheck en CI.

---

## 8. RetroStick Launcher (Android TV)

### 8.1 Stack
- **Kotlin**, una sola Activity, **Views nativas sin Leanback ni Compose** (lo más ligero para 1 GB de RAM y Mali-450; el diseño es propio).
- Paquete `com.retrostick.launcher`. `minSdk 26` (fuentes como recurso), `targetSdk 28`. Se instala por sideload, así que se puede apuntar a 28 y usar almacenamiento heredado en Android 9.
- `AndroidManifest`: categorías `HOME` + `DEFAULT` (pantalla de inicio), `LEANBACK_LAUNCHER` + `LAUNCHER`, banner de TV (320×180), `uses-feature android.software.leanback required=false`, `touchscreen required=false`.
- Compilación del APK en **GitHub Actions** (artefacto descargable) y en local con `./gradlew assembleRelease` (firma con keystore de debug o una propia guardada fuera del repo).

### 8.2 Pantalla de inicio (maqueta `docs/maquetas/inicio.html`)
- **Cabecera:** marca, mando conectado (`InputDevice` con `SOURCE_GAMEPAD`), estado de sincronización (hora del último cambio en `/Partidas`, o "sin sincronizar"), Wi-Fi y hora.
- **Pestañas de consola** (GB, GBC, GBA, PS1) con su logo y el número de juegos en `/ROMs/<c>`. La última es **Retro Ajustes**. Se cambia con L1/R1 o con izquierda/derecha.
- **Juego seleccionado:** portada grande, nombre (No-Intro sin la parte entre paréntesis), consola y región.
  - *Última partida:* fecha del `.srm`/`.state.auto` en `/Partidas`.
  - *Tiempo jugado:* registro de tiempo de RetroArch (`content_runtime_log`). ⚠️ VERIFICAR la ruta y el formato en la 1.22.2.
  - *Partida guardada:* "✓ Copia en Drive" si todas sus partidas están subidas tal como están en el stick; "Pendiente de subir" si no; "En el stick" sin Drive conectado.
  - Botón **Continuar partida** o **Jugar** (si no hay partida).
- **Estante** con las portadas de la consola, marca "Jugando" en el último jugado y tarjeta final "+ Añade juegos desde la app del móvil".
- **Ayudas de botones** abajo, con los símbolos del mando que se está usando (por *vendor id*: Sony, Nintendo, Microsoft u otros con el esquema A/B/X/Y de Android):
  PlayStation ✕ Jugar · ○ Atrás · △ Favorito · □ Detalles · L1/R1 · Xbox A/B/Y/X (colores) · LB/RB · Switch A/B/X/Y · L/R (con mando Nintendo, confirmar = botón derecho; ⚠️ VERIFICAR al emparejar el Pro Controller) · sin mando: OK Jugar · ↑ Consolas.
- **Lanzar un juego:** intent a `com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture` con `ROM`, `LIBRETRO` y `CONFIGFILE` (verificado, ver `docs/dispositivo.md`).
- **Portadas:** libretro-thumbnails (misma regla que la PWA, 7.5), con caché en disco y tarjeta de degradado si no hay portada o no hay red.

### 8.2b Pestaña Retro Ajustes
Botones grandes navegables con el mando, con el estilo de la maqueta de TV:

| Botón | Acción |
|---|---|
| 🎮 **Emparejar mando** | `com.google.android.intent.action.CONNECT_INPUT` (verificado: `AddAccessoryActivity`). `ACTION_BLUETOOTH_SETTINGS` **no existe** en este stick. |
| 🔄 **Sincronizar ahora** | Conecta Google Drive (código) o lanza la sincronización. |
| ☁️ **Cuenta de Drive** | Muestra el correo conectado; permite desconectar. |
| 📶 **Wi-Fi** | `Settings.ACTION_WIFI_SETTINGS` |
| ⚙️ **Ajustes de Android** | `Settings.ACTION_SETTINGS` (salida de emergencia si se sustituyó el launcher) |
| 💾 **Espacio** | Muestra el espacio libre (interno + USB si hay) y lo que ocupa `/ROMs` |
| 🕹️ **Mandos conectados** | Lista de `InputDevice` con fuente `SOURCE_GAMEPAD` |

### 8.3 `status.json`
Al abrir la app y periódicamente (WorkManager, cada 60 min), escribir `/storage/emulated/0/Partidas/.stick/status.json`:
```json
{
  "updatedAt": "2026-11-03T18:45:00Z",
  "freeBytes": 3221225472,
  "romsBytes": 1395864371,
  "controllers": ["DualSense Wireless Controller"],
  "appVersion": "1.0.0"
}
```
La sincronización lo sube a `Retro/Partidas/.stick/` y la PWA lo lee. Permiso: `WRITE_EXTERNAL_STORAGE` (Android 9).

---

## 9. Plan de trabajo por fases

### Fase 0 – Repo y base
- [ ] Estructura del repo (sección 10), `.gitignore` (sección 1), licencia y este README.
- [ ] `docs/maquetas/` con las maquetas existentes.
- **Aceptación:** `git status` limpio y ningún binario de juego en el repo.

### Fase 1 – Stick: diagnóstico
- [ ] Conectar por ADB y guardar en `docs/dispositivo.md`: ABI, versión de Android, paquetes instalados, launcher actual, espacio libre y ruta del `retroarch.cfg` (si ya está instalado).
- [ ] Resolver todos los ⚠️ VERIFICAR que dependan del dispositivo.
- **Aceptación:** `docs/dispositivo.md` con los datos reales.

### Fase 2 – Stick: emuladores ✅ (cerrada el 2026-11-03, con pendientes)
- [x] `stick/install.sh` y `install.ps1`.
- [x] Configuración de RetroArch y opciones de núcleo (Gambatte, mGBA).
- [x] Núcleos, información de núcleos y perfiles de mando descargados.
- [x] GBC y GBA a velocidad completa; `.srm`/`.state.auto` en `/Partidas` (ver `docs/dispositivo.md`).
- [ ] **Pendiente:** emparejar DualSense y Pro Controller, validar autoconfig y atajos (Select+Start, guardar/cargar estado, avance rápido).
- [ ] **Pendiente:** PS1 (opciones de PCSX ReARMed y prueba) cuando haya un juego. Overlays/marcos.

### Fase 3 – Stick: Google Drive ✅ (cerrada el 2026-11-03)
- [x] Proyecto de Google Cloud y cliente OAuth de TV (`docs/google-cloud.md`). Se usa un proyecto existente, en modo
  *Prueba*: la conexión caduca cada 7 días hasta publicar la app (requiere página principal y política de privacidad).
- [x] Sincronización en el launcher (`Drive.kt`, `DriveSync.kt`, sección 5.3).
- [x] Stick conectado (2026-11-03): primera sincronización con 3 juegos, 4 partidas y `status.json`; la ficha muestra
  "✓ Copia en Drive".
- [x] Quitar/devolver un juego (2026-11-03): mover Oracle of Seasons a `Coleccion/gbc` lo borró del stick; devolverlo
  a `JugandoAhora/gbc` lo volvió a descargar (MD5 idéntico al original). Su partida no se tocó.
- [x] Reposo del stick: se mantiene a los 30 min (`sleep_timeout = 1800000`, decisión del usuario). Dormido no
  sincroniza; al despertar, el launcher vuelve a primer plano y sincroniza enseguida.
- **Aceptación:** al conectar se crea `Retro/` en Drive y suben las partidas y los juegos del stick; un archivo nuevo en
  `Retro/JugandoAhora/<c>` aparece en el menú tras sincronizar; quitarlo de ahí lo borra del stick y su partida sigue en
  Drive; la ficha del juego muestra "✓ Copia en Drive".

### Fase 4 – RetroStick Launcher (APK)
- [ ] App Kotlin (sección 8) + workflow de GitHub Actions que publique el APK.
- [ ] Fijarla como pantalla de inicio (5.5), con FLauncher como respaldo.
- **Aceptación:** al encender el stick se abre el launcher con el aspecto de la maqueta; los juegos de `/ROMs` aparecen con portada y se lanzan en RetroArch; la pestaña Retro Ajustes funciona con el mando; `status.json` llega a Drive.

### Fase 5 – PWA
- [ ] Proyecto Vite + React + TS + PWA, login con Google y capa Drive.
- [ ] Pantallas Colección, Ficha, Subir, Partidas y Stick.
- [ ] Base de datos No-Intro/Redump generada en *build*.
- [ ] Despliegue en GitHub Pages.
- **Aceptación:** desde el móvil, instalada en la pantalla de inicio: subir un `.gba` con nombre raro lo renombra al nombre No-Intro y muestra portada; el interruptor "En el stick" hace que aparezca o desaparezca en la TV tras sincronizar; las partidas se ven, se descargan y se restaura una revisión anterior.

### Fase 6 – Pulido
- [ ] Revisión de rendimiento en el stick (shaders, overlays).
- [ ] `docs/uso.md`: guía de uso diario para el usuario, en español y sin tecnicismos.

---

## 10. Estructura del repositorio

```
retrostick/
├── README.md
├── .gitignore
├── docs/
│   ├── maquetas/            # inicio.html, movil.html, PNG, img/
│   ├── dispositivo.md       # fase 1
│   ├── google-cloud.md
│   ├── foldersync.md
│   ├── mandos.md
│   ├── bios-ps1.md
│   └── uso.md
├── stick/
│   ├── install.sh
│   ├── install.ps1          # si el PC es Windows
│   ├── uninstall-launcher-revert.sh
│   ├── retroarch/
│   │   ├── retroarch.overrides.cfg
│   │   ├── core-options/
│   │   ├── autoconfig/
│   │   └── overlays/
│   └── apks/                # ignorado por git
├── android/                 # RetroStick Launcher (Gradle)
├── pwa/
│   ├── src/
│   │   ├── lib/drive.ts
│   │   ├── lib/auth.ts
│   │   ├── lib/romdb.ts
│   │   ├── lib/covers.ts
│   │   ├── screens/
│   │   └── components/
│   ├── scripts/build-db.ts
│   ├── public/db/           # generado en build
│   └── vite.config.ts
└── .github/workflows/
    ├── pwa-pages.yml
    └── android-apk.yml
```

---

## 11. Diseño visual

Ver `docs/maquetas/`:
- `inicio-stick.png` / `inicio.html`: menú de la TV (1920×1080).
- `pwa-movil.png` / `movil.html`: PWA (Colección, Ficha, Subir).

**Tokens** (los mismos en la PWA y en Retro Ajustes):
| Token | Valor |
|---|---|
| Fondo | `#0d0b1f` con degradados radiales `#3b2f8f` / `#1d1650` |
| Superficie | `rgba(255,255,255,.05–.07)` |
| Texto | `#f2f0ff` · secundario `#bdb6ec` · terciario `#8f88bd` |
| Acento | `#7c6cff` → `#5b4bd6` |
| OK / sincronizado | `#4ade80` |
| Botón principal | fondo `#fff`, texto `#1b1446` |
| Tipografía | Inter (400/600/700/800) |
| Radios | tarjetas 16 px · paneles 20–24 px · pastillas 20 px |

Tema oscuro únicamente (se usa en TV y de noche). En la TV, el foco se marca con un borde blanco de 3–4 px y sombra de acento.

---

## 12. Problemas conocidos y soluciones

| Problema | Solución |
|---|---|
| RetroArch no instala o no encuentra núcleos | Se ha instalado la versión de 64 bits: usar la de 32 bits (armeabi-v7a). |
| Botones del DualSense o Pro Controller cambiados | Actualizar autoconfiguraciones; si persiste, autoconfig propio (6.5). |
| Parpadeos en juegos de GB/GBA | Activar mezcla de fotogramas o interframe blending. |
| Juego de PS1 no arranca | Probar con la BIOS del usuario en `system/`; revisar `.cue`/`.m3u`. |
| Partida "perdida" | Revisar que `savefile_directory` apunta a `/Partidas/saves`; restaurar una revisión desde la PWA. |
| Conflicto de partidas | No jugar la misma partida en dos dispositivos a la vez; la sincronización se queda con la más reciente. |
| Sin acceso a Ajustes tras cambiar el launcher | `adb shell cmd package set-home-activity me.efesser.flauncher/.MainActivity` (vuelve a FLauncher) o pestaña Retro Ajustes → Ajustes de Android. |
| Stick lento | Cerrar o desinstalar apps preinstaladas no usadas; desactivar animaciones en Opciones de desarrollador. |
| Calentamiento en sesiones largas de PS1 | Alargador HDMI para separar el stick de la TV. |
