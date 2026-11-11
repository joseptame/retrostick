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
- **Buscar juegos (archive.org):** la pestaña "Buscar" sirve para localizar **copias de seguridad de juegos que el usuario ya posee en formato físico** y subirlas a su Drive. No se usa para descargar juegos que no se tienen: eso puede infringir derechos de autor, y la responsabilidad es del usuario. El código solo consulta la API pública de archive.org y descarga el archivo que el usuario elige explícitamente.
- Las **portadas** se cargan en tiempo de ejecución desde `libretro-thumbnails` y no se guardan en el repo. Las de `docs/maquetas/img/` son solo para las maquetas.
- **Idioma:** la interfaz de la PWA y del launcher va en **español**. El código y los comentarios pueden ir en inglés.
- **Secretos:** nada se escribe en el código ni se sube al repo.
  - Cliente OAuth de TV (stick y PWA): `android/local.properties` en el PC y `VITE_GOOGLE_CLIENT_ID` /
    `VITE_GOOGLE_CLIENT_SECRET` en Vercel. El secreto de este tipo de cliente no es confidencial (va dentro del APK y de la web).
  - Acceso a la PWA: `AUTH_USER`, `AUTH_PASSWORD` y `AUTH_SECRET`, variables secretas de Vercel.

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
    └── bios/           ← BIOS del usuario (gb_bios.bin, gbc_bios.bin, gba_bios.bin, scph5502.bin, bios_E.sms, bios_MD.bin), subidas desde la app (Stick → Pantallas de inicio); el stick las copia a RetroArch/system
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
| NES | FCEUmm | `/ROMs/nes` |
| Super Nintendo | Snes9x 2010 (más ligero que Snes9x actual; el S805Y no mueve bien el actual). Sin RetroAchievements: no admiten este núcleo (sus juegos desbloquean «Warning: Unsupported Core») | `/ROMs/snes` |
| Game Boy | Gambatte | `/ROMs/gb` |
| Game Boy Color | Gambatte | `/ROMs/gbc` |
| Game Boy Advance | mGBA (alternativa: gpSP si algún juego va lento) | `/ROMs/gba` |
| Mega Drive | Genesis Plus GX (se instala desde el Actualizador en línea de RetroArch) | `/ROMs/md` |
| Master System | Genesis Plus GX | `/ROMs/sms` |
| Nintendo 64 | Mupen64Plus-Next, versión GLES2 (`mupen64plus_next_gles2`, se instala desde el Actualizador en línea). Con el renderizado en un hilo aparte va a velocidad completa en el stick (Mario Kart 64 a 60 FPS, Super Mario 64 a 50, comprobado el 2026-11-11); ajustes en `stick/retroarch/core-options/Mupen64Plus-Next/` | `/ROMs/n64` (`.z64`, también `.n64` / `.v64`) |
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
savestate_auto_load = "false"   # desde el título; «Continuar…» en Detalles lo activa para esa partida
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
- BIOS: usa la del usuario (`system/scph5502.bin`, verificado con una SCPH-7502 v4.1 E el 2026-11-09) si es una
  versión conocida; si no, la HLE (8.2d). Documentar en `docs/bios-ps1.md` cómo extraerla de una PS1 propia
  (FreePSXBoot + volcador), **sin enlaces a descargas de BIOS**.

### 6.4 Overlays y marcos
Marcos propios que imitan cada consola (diseño en `docs/maquetas/marcos.html`; de ahí salen los PNG de
`stick/retroarch/overlays/retrostick/`, 1920×1080 con un hueco transparente donde va el juego).

| Consola | Juego en pantalla | Override por carpeta |
|---|---|---|
| Game Boy / Color | 960×864 (×6, píxeles perfectos) | `config/Gambatte/gb.cfg`, `gbc.cfg` |
| Game Boy Advance | 1440×960 (×6, píxeles perfectos) | `config/mGBA/gba.cfg` |
| NES / Super Nintendo / Mega Drive / Master System / Nintendo 64 / PlayStation | 1440×1080 (toda la altura, 4:3) | `config/FCEUmm/nes.cfg`, `config/Snes9x 2010/snes.cfg`, `config/Genesis Plus GX/{md,sms}.cfg`, `config/Mupen64Plus-Next/n64.cfg`, `config/PCSX-ReARMed/psx.cfg` |

- Son *overrides* de carpeta de contenido: RetroArch carga `config/<Núcleo>/<nombre de la carpeta>.cfg` después del
  del núcleo, así GB y GBC (los dos con Gambatte) llevan marcos distintos.
- Tamaño fijo con `aspect_ratio_index = "23"` (personalizado). En RetroArch 1.22 `custom_viewport_x/y` son
  desplazamientos **desde el centro**, por eso valen 0 (comprobado con capturas en el stick el 2026-11-08).
- Cada marco tiene dos overlays (con marco / vacío). **Clic en el panel táctil** del DualSense
  (`input_overlay_next_btn = "106"`) cambia entre ellos. No se usa Select + R1: con un botón de atajos todos los atajos
  pedirían Select, y R1/L1 ya son avance rápido y guardado en GB/GBC.
- Para cambiar un marco: editar `marcos.html`, exportar los PNG y copiarlos con `install.ps1`.

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
- Inicio de sesión **con código** (`google.com/device`), con el **mismo cliente OAuth de TV que el stick**: con
  `drive.file` ambos ven los mismos archivos. Google permite CORS en `oauth2.googleapis.com/device/code` y `/token`
  (comprobado). El secreto de ese tipo de cliente no es confidencial y va en la web (variables
  `VITE_GOOGLE_CLIENT_ID` y `VITE_GOOGLE_CLIENT_SECRET`).
- **Drive API v3** por REST (`fetch`, sin `gapi`).
- Estilos: CSS propio con tokens (sección 11), sin librerías de componentes pesadas
- Despliegue: **Vercel** (cuenta del usuario), en la raíz del dominio. La página principal y la política de privacidad
  siguen en GitHub Pages (`site/`) porque están dadas de alta en la pantalla de consentimiento de Google.

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
Barra inferior: **Colección · Buscar · Subir · Partidas · Stick**.

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

**Buscar juegos**
- Explorador de una colección de archive.org por consola (GB/GBC/GBA/PS1), con portadas. Se escribe el
  **identificador** del elemento (ej. `gba-pal`, la colección PAL de Game Boy Advance) y se listan sus juegos.
- Usa la API pública de archive.org (`metadata/<id>` para localizar el `.zip`/`.7z` y el listado HTML de
  `view_archive.php` para leer los archivos que contiene), todo con CORS habilitado.
- Los nombres de los archivos ya vienen en formato No-Intro/Redump, así que las portadas de
  `libretro-thumbnails` funcionan directamente; hay un filtro por nombre en la propia pantalla.
- **Descarga:** los archivos dentro de un `.zip`/`.7z` se sirven a través de `view_archive.php`, que **sí
  envía cabeceras CORS**, así que el navegador los descarga directamente. Para elementos con archivos sueltos
  (no empaquetados, que archive.org **no** CORS habilita) se usa el proxy `api/download.ts` (Edge Function de
  Vercel, *streaming* sin el límite de 4,5 MB). Cada juego tiene además un enlace "Abrir" que descarga con el
  navegador.
- La identificación, el renombrado No-Intro y la subida reanudable son los mismos que en "Subir copias".
- **Ficha del juego:** al pulsar un juego se abre su ficha con descripción, tráiler embebido y capturas (IGDB,
  sección 7.7), y los botones de subir a Drive y abrir en archive.org.

**Subir copias**
- Selector de archivos múltiple (`.gb .gbc .gba .chd .cue .bin .m3u .pbp`; `.zip` opcional).
- Detección de consola por extensión. Para `.bin`/`.chd`/`.cue` → psx.
- **Identificación del juego (cartuchos):** calcular **CRC32 y SHA-1** en el navegador (Web Crypto para SHA-1, CRC32 propio o librería ligera) y buscarlo en los DAT **No-Intro** de `libretro-database` (`metadat/no-intro/Nintendo - Game Boy*.dat`).
  - `pwa/scripts/build-db.ts` (`npm run build-db`) genera un JSON compacto por consola en `pwa/public/db/` con los
    nombres, `{sha1|crc: nombre}` y el índice de portadas de libretro-thumbnails. **Se guardan en el repo** para que
    la compilación en Vercel no dependa de la red ni de los límites de la API de GitHub; se regeneran a mano.
  - Si no hay coincidencia: búsqueda aproximada por nombre de archivo y, si tampoco, el usuario escribe el nombre a mano.
- **PS1:** identificación por nombre de archivo frente a los DAT de **Redump** (`metadat/redump/Sony - PlayStation.dat`), o por la etiqueta del disco si es fácil. Si no hay coincidencia, nombre manual.
- Muestra "nombre original tachado → nombre No-Intro", chip de consola, chip "✓ Reconocido" o "⚠ Revisar" y barra de progreso.
- **Subida en sesión reanudable** (`uploadType=resumable`). Drive expone `Location` por CORS pero **no `Range`**
  (comprobado), así que el navegador no puede saber cuánto llegó y reanudar por trozos: el archivo va en un solo PUT
  (XHR, con progreso) y, si se corta, se reintenta con una sesión nueva (3 intentos).
- Interruptor **"Enviar también al stick"**: sube directamente a `JugandoAhora/<c>` en vez de a `Coleccion/<c>`.
- Evitar duplicados: si ya existe un archivo con el mismo nombre o SHA-1, preguntar.

**Partidas**
- Lista de todas las partidas (saves y states) ordenadas por fecha, con portada del juego, y las mismas acciones que en la ficha.

**Stick**
- Datos de `status.json`: última sincronización, espacio libre, mando conectado y versión de Retro Ajustes.
- Si `status.json` tiene más de 24 h: aviso "El stick no se ha sincronizado desde…".
- **RetroShop:** escanear el QR de la tele para vincular la tienda del stick.
- **RetroAchievements:** iniciar sesión con usuario y contraseña (`api/ra.ts?login=1` los cambia por el token de
  RetroAchievements; la contraseña no se guarda en ningún sitio) → `Retro/Config/ra.json` `{user, token}`, que el stick
  copia a RetroArch al sincronizar. También para volver a entrar tras cambiar la contraseña, cambiar de cuenta o
  desactivar los logros (borra `ra.json`).
- **Pantallas de inicio:** una fila por consola para subir la BIOS propia del usuario a `Retro/Config/bios` con el
  nombre que espera el emulador; avisa si no es una versión conocida (MD5 de Drive). Ver 8.2d.

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

### 7.7 Ficha del juego en "Buscar" (IGDB)
- Al pulsar un juego en la pestaña Buscar se abre su ficha: descripción, **tráiler embebido** y capturas, sacados
  de la API de **IGDB** (Twitch).
- La consulta y el token OAuth se hacen en `pwa/api/igdb.ts` (Edge Function de Vercel), así el `Client Secret`
  de IGDB **nunca** va en el navegador. El cliente llama a `/api/igdb?title=…`.
- **Descripción en inglés** (IGDB no traduce): se muestra `summary` (o `storyline` si no hay). El tráiler es un
  vídeo de YouTube embebido (`videos[0].video_id`); las capturas vienen de `screenshots[]`.

**Configuración (una vez, gratis):**
1. En `dev.twitch.tv/console/apps` → **Registra tu aplicación**. Nombre libre (ej. "RetroStick"), categoría "Otro",
   OAuth redirect URL: `http://localhost` (no se usa).
2. Copia el **Client ID** y el **Client Secret**.
3. En Vercel (proyecto retrostick) → *Settings → Environment Variables* → añadir:
   - `IGDB_CLIENT_ID` = el Client ID
   - `IGDB_CLIENT_SECRET` = el Client Secret
   (sin prefijo `VITE_`: son variables del servidor). Redeploy.
- Sin estas variables, la ficha muestra "IGDB no está configurado" y solo se ve la portada y los botones de subir.
- **Ports:** IGDB junta las versiones de un juego en una sola entrada (Impossible Mission: C64, Master System…). Si
  la versión de la consola pedida salió más de medio año después que la original (`release_dates`), el año es el
  de esa consola y las capturas son las de su propia versión (`Named_Titles` y `Named_Snaps` de libretro-thumbnails,
  buscadas por título en el índice de su servidor), sin el tráiler del original. Si IGDB no tiene capturas, también
  se usan las de libretro. El launcher guarda las fichas con versión (`v`): al subirla, las pide de nuevo una vez.

---

## 8. RetroStick Launcher (Android TV)

### 8.1 Stack
- **Kotlin**, una sola Activity, **Views nativas sin Leanback ni Compose** (lo más ligero para 1 GB de RAM y Mali-450; el diseño es propio).
- Paquete `com.retrostick.launcher`. `minSdk 26` (fuentes como recurso), `targetSdk 28`. Se instala por sideload, así que se puede apuntar a 28 y usar almacenamiento heredado en Android 9.
- `AndroidManifest`: categorías `HOME` + `DEFAULT` (pantalla de inicio), `LEANBACK_LAUNCHER` + `LAUNCHER`, banner de TV (320×180), `uses-feature android.software.leanback required=false`, `touchscreen required=false`.
- Compilación en local con `./gradlew assembleDebug` (JDK 17). Sin GitHub Actions (descartado, ver fase 4).

### 8.2 Pantalla de inicio (maqueta `docs/maquetas/inicio.html`)
- **Cabecera:** marca, mando conectado (`InputDevice` con `SOURCE_GAMEPAD`), estado de sincronización (hora del último cambio en `/Partidas`, o "sin sincronizar"), Wi-Fi y hora.
- **Pestañas de consola** (GB, GBC, GBA, PS1) con su logo y el número de juegos en `/ROMs/<c>`. La última es **Retro Ajustes**. Se cambia con L1/R1 o con izquierda/derecha.
- **Juego seleccionado:** portada grande, nombre (No-Intro sin la parte entre paréntesis), consola y región.
  - *Última partida:* fecha del `.srm`/`.state.auto` en `/Partidas`.
  - *Tiempo jugado:* registro de tiempo de RetroArch (`content_runtime_log`). ⚠️ VERIFICAR la ruta y el formato en la 1.22.2.
  - *Logros:* conseguidos / total en RetroAchievements; «No válida» si la ROM no es una versión que admitan sus
    logros; sin columna en Super Nintendo, en Drive o sin cuenta (ver 8.2c).
  - *Partida guardada:* "✓ Copia en Drive" si todas sus partidas están subidas tal como están en el stick; "Pendiente de subir" si no; "En el stick" sin Drive conectado.
  - Botón **Jugar**: el juego empieza siempre desde el título (`savestate_auto_load = "false"`). Para seguir, □ Detalles (pantalla completa, `DetailsActivity`: Jugar con tres tarjetas con la captura de cada guardado —`savestate_thumbnail_enable`—, progreso, partidas, Lo he terminado, Manual y Borrar) →
    *Donde lo dejaste* (`.state.auto`, se guarda al cerrar) o *Guardado rápido*
    (`.state`, Create+L1 en partida; el launcher lo copia a `.state.auto` y activa la carga automática solo para esa
    partida).
- **Estante** con las portadas de la consola, marca "Jugando" en el último jugado y tarjeta final "+ Añade juegos desde la app del móvil".
- **Ayudas de botones** abajo, con los símbolos del mando que se está usando (por *vendor id*: Sony, Nintendo, Microsoft u otros con el esquema A/B/X/Y de Android):
  PlayStation ✕ Jugar · ○ Atrás · △ Favorito · □ Detalles · L1/R1 · Xbox A/B/Y/X (colores) · LB/RB · Switch A/B/X/Y · L/R (con mando Nintendo, confirmar = botón derecho; ⚠️ VERIFICAR al emparejar el Pro Controller) · sin mando: OK Jugar · ↑ Consolas.
- **Vistas:** carátulas (estante) o lista, con Options o la tarjeta «Vista del inicio». En carátulas, ▲ desde el
  estante lleva a la fila de tráiler y capturas; en la lista, ▶.
- **Lanzar un juego:** intent a `com.retroarch.ra32/com.retroarch.browser.retroactivity.RetroActivityFuture` con `ROM`, `LIBRETRO` y `CONFIGFILE` (verificado, ver `docs/dispositivo.md`).
  Antes, el launcher escribe en `retroarch.cfg` la carga automática (según Continuar…), los atajos (Create + L1 /
  R1 / R2, `docs/mandos.md`), la cuenta de RetroAchievements y un arranque limpio (sin la franja de carga ni los
  avisos de sesión, reasignaciones, configuración o mando), y en los `.opt` de cada núcleo la pantalla de inicio.
- **Portadas:** libretro-thumbnails (misma regla que la PWA, 7.5), con caché en disco y tarjeta de degradado si no hay portada o no hay red.

### 8.2b Pestaña Retro Ajustes
Tarjetas grandes navegables con el mando, en 5 columnas (3 filas en la pantalla, sin desplazarse), con los
emojis como iconos (decisión del usuario):

| Botón | Acción |
|---|---|
| 🎮 **Emparejar mando** | `com.google.android.intent.action.CONNECT_INPUT` (verificado: `AddAccessoryActivity`). `ACTION_BLUETOOTH_SETTINGS` **no existe** en este stick. |
| 🔄 **Sincronizar ahora** | Conecta Google Drive (código) o lanza la sincronización. |
| 📶 **Wi-Fi** | `Settings.ACTION_WIFI_SETTINGS` |
| ⚙️ **Ajustes de Android** | `Settings.ACTION_SETTINGS` (salida de emergencia si se sustituyó el launcher) |
| 💾 **Espacio** | Muestra el espacio libre (interno + USB si hay) y lo que ocupa `/ROMs` |
| 🕹️ **Mandos conectados** | Lista de `InputDevice` con fuente `SOURCE_GAMEPAD` |
| 👾 **RetroArch** | Abre RetroArch (su menú y ajustes) |
| 📱 **Otras apps** | Abre FLauncher |
| ☁️ **Cuenta de Drive** | Muestra el correo conectado; permite desconectar. |
| 🛒 **RetroShop** | La tienda (descargar juegos con el mando) |
| ⬆️ **Actualizar launcher** | Ver 8.4 |
| 🔊 **Sonidos** | Volumen de los sonidos del menú |
| 🗂️ **Vista del inicio** | Carátulas o lista (también con Options) |
| ✨ **Pantallas de inicio** | Cuántas BIOS hay; activarlas o desactivarlas (8.2d) |
| 🏆 **Mis logros** | Perfil de RetroAchievements (8.2c) |

### 8.2c Logros (RetroAchievements)
- Cuenta: `Retro/Config/ra.json` (desde la app del móvil, 7.4), escrita en `retroarch.cfg` antes de cada partida
  (modo normal, no *hardcore*: el guardado rápido y el avance rápido siguen funcionando). El aviso de logro en
  partida lleva insignia y sonido.
- **R2** en el inicio: los logros del juego al estilo PS5 (progreso, Todos / Conseguidos / Por conseguir, insignia,
  cómo se consigue, puntos, rareza y fecha), traducidos al español (`api/ra.ts`). La ROM se identifica por su hash.
- **Versión no admitida:** si el hash de la ROM no es de una versión que admitan los logros del juego (se encontró
  por el título), la ficha dice «No válida» y el launcher escribe un *override* del juego
  (`RetroArch/config/<núcleo>/<juego>.cfg`, `cheevos_enable = "false"`): sin el aviso «Unsupported Game Version» ni
  logros que fallen. Los logros de aviso de 0 puntos («Unsupported…») no cuentan en las listas.
- **Super Nintendo sin logros:** RetroAchievements no admite Snes9x 2010 (y el S805Y no mueve el Snes9x actual).
- **Mis logros** (Retro Ajustes): avatar, puntos y resumen; *Juegos* (cada juego con logros jugado, con su
  progreso) y *Recientes* (últimos 30 días, agrupados por juego), con L1/R1. ✕ sobre un juego abre su lista de
  logros por su id de RetroAchievements, aunque ya no esté en el stick (`api/ra.ts?profile=1`, `?id=`).

### 8.2d Pantallas de inicio (BIOS del usuario)
- Cada consola muestra su pantalla de arranque con su BIOS: `gb_bios.bin`, `gbc_bios.bin`, `gba_bios.bin`,
  `scph5502.bin` (cualquier BIOS de PS1 conocida, con ese nombre), `bios_E.sms` y `bios_MD.bin`. NES y Super
  Nintendo no tenían.
- Llegan de `Retro/Config/bios` a `RetroArch/system` en cada sincronización (las quitadas de Drive se borran).
- **Solo se usan las versiones conocidas** (MD5 en `Bios.kt` y `pwa/src/lib/bios.ts`): una BIOS de PS1 no reconocida
  dejaba los juegos en negro. Si no hay una conocida, el núcleo arranca como antes (PS1 con su BIOS HLE).
- Opciones que escribe el launcher: `gambatte_gb_bootloader`, `mgba_use_bios` / `mgba_skip_bios`,
  `pcsx_rearmed_bios` (`auto` / `HLE`) y `pcsx_rearmed_show_bios_bootlogo`, `genesis_plus_gx_bios`.

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

### 8.4 Actualizaciones del launcher
El stick busca versiones nuevas en Drive (`Retro/Config/launcher`: `launcher.json` + `launcher.apk`) en cada sincronización y, si la hay, pregunta «Actualización del launcher · Instalar / Ahora no». También desde Ajustes → Actualizar launcher. Android siempre pide confirmar la instalación; la primera vez hay que permitir a RetroStick instalar apps.

Publicar una versión (desde `android/`): subir `versionCode` en `app/build.gradle.kts` y

```sh
./gradlew assembleDebug && node publish-update.mjs
```

Usa el cliente OAuth del stick (`local.properties`); la primera vez muestra un código para aprobar en google.com/device y guarda el token en `android/.publish-token.json` (fuera de git). El APK nunca se publica en internet: lleva dentro el cliente OAuth y el repositorio es público.

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
- [ ] **Pendiente:** PS1 (opciones de PCSX ReARMed y prueba) cuando haya un juego.
- [x] Marcos por consola con tamaño fijo del juego (README 6.4), 2026-11-08.

### Fase 3 – Stick: Google Drive ✅ (cerrada el 2026-11-03)
- [x] Proyecto de Google Cloud y cliente OAuth de TV (`docs/google-cloud.md`). Se usa un proyecto existente, en modo
  *En producción* desde el 2026-11-03 (página principal y política de privacidad en GitHub Pages, `site/`); el stick
  se reconectó después de publicar, así que su conexión ya no caduca cada 7 días.
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
- [x] App Kotlin (sección 8).
- [ ] ~~Workflow de GitHub Actions que publique el APK~~: **descartado** (2026-11-03). El repo es público, el APK
  llevaría el cliente OAuth y se firmaría con otra clave que la del PC. Se compila e instala desde el PC
  (`android/`, `./gradlew assembleDebug`, JDK 17).
- [ ] Fijarla como pantalla de inicio (5.5), con FLauncher como respaldo.
- **Aceptación:** al encender el stick se abre el launcher con el aspecto de la maqueta; los juegos de `/ROMs` aparecen con portada y se lanzan en RetroArch; la pestaña Retro Ajustes funciona con el mando; `status.json` llega a Drive.

### Fase 5 – PWA
- [x] Proyecto Vite + React + TS + PWA, login con código (mismo cliente que el stick) y capa Drive.
- [x] Pantallas Colección, Ficha, Subir, Partidas y Stick.
- [x] Base de datos No-Intro/Redump (`npm run build-db`, guardada en `pwa/public/db/`).
- [x] Despliegue en **Vercel**: <https://retrostick.vercel.app>. **Automático** con cada push a `main` que cambie
  `pwa/` (proyecto conectado al repo, Root Directory `pwa`, `pwa/vercel.json` ignora los demás cambios). Variables
  `VITE_GOOGLE_CLIENT_ID` y `VITE_GOOGLE_CLIENT_SECRET` en el proyecto de Vercel.
- [x] **Acceso con usuario y contraseña** comprobado en el servidor (`pwa/middleware.ts`, Routing Middleware de
  Vercel). Credenciales en variables secretas de Vercel (`AUTH_USER`, `AUTH_PASSWORD`, `AUTH_SECRET`), nunca en el
  repo. Cookie firmada `HttpOnly` de un año; `/__logout` (botón "Cerrar acceso" en la pestaña Stick) la borra.
- [ ] Probar en el móvil (acceso, login de Google, interruptor, subir, partidas, restaurar).
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
│   ├── api/download.ts      # proxy Vercel para descargar de archive.org (CORS)
│   ├── api/igdb.ts          # proxy Vercel para IGDB (ficha del juego, 7.7)
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
