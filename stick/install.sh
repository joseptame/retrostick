#!/usr/bin/env bash
# RetroStick installer for the Xiaomi Mi TV Stick. Idempotent: safe to run again.
# Usage: ./stick/install.sh <IP_STICK>[:5555] [retroarch_version]
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: keep /storage/... paths as they are

[ $# -ge 1 ] || { echo "Uso: $0 <IP_STICK> [version_retroarch]"; exit 1; }
SERIAL="$1"; [[ "$SERIAL" == *:* ]] || SERIAL="$SERIAL:5555"
RA_VERSION="${2:-1.22.2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
SD=/storage/emulated/0
RA_PACKAGE=com.retroarch.ra32
RA_APK="$HERE/apks/RetroArch_ra32_$RA_VERSION.apk"
RA_APK_URL="https://buildbot.libretro.com/stable/$RA_VERSION/android/RetroArch_ra32.apk"
RA_CFG="$SD/Android/data/$RA_PACKAGE/files/retroarch.cfg"
OVERRIDES="$HERE/retroarch/retroarch.overrides.cfg"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

step() { printf '\n\033[36m==> %s\033[0m\n' "$1"; }
fail() { printf '\033[31mERROR: %s\033[0m\n' "$1"; exit 1; }
adbs() { adb -s "$SERIAL" "$@"; }
sh_() { adb -s "$SERIAL" shell "$1" | tr -d '\r'; }
mkdir -p "$HERE/apks"

# 1. Connection and architecture
step "Conectando con $SERIAL"
command -v adb >/dev/null || fail "adb no está en el PATH (Android SDK platform-tools)."
adb connect "$SERIAL" >/dev/null || true
state="$(adbs get-state 2>/dev/null | tr -d '\r' || true)"
[ "$state" = device ] || fail "El stick no está listo (estado: '$state'). Activa la depuración USB y acepta el aviso en la TV."
abi="$(sh_ 'getprop ro.product.cpu.abilist')"; release="$(sh_ 'getprop ro.build.version.release')"
echo "ABI: $abi | Android $release"
[[ "$abi" == *armeabi-v7a* ]] || fail "ABI inesperada ($abi): se esperaba armeabi-v7a."
[ "$release" = 9 ] || echo "AVISO: se esperaba Android 9 y es $release."

# 2. RetroArch 32-bit
step "RetroArch $RA_VERSION (32 bits)"
installed="$(sh_ "dumpsys package $RA_PACKAGE | grep -m1 versionName" || true)"
if [[ "$installed" == *"versionName=$RA_VERSION"* ]]; then
  echo "Ya instalado (${installed// /})."
else
  [ -f "$RA_APK" ] || { echo "Descargando $RA_APK_URL"; curl -fL -o "$RA_APK" "$RA_APK_URL"; }
  adbs install -r "$RA_APK"
fi

# Retro Ajustes (phase 4): installed only once the APK has been built
ajustes="$(ls "$HERE"/../android/app/build/outputs/apk/release/*.apk 2>/dev/null | head -1 || true)"
[ -z "$ajustes" ] || { step "Retro Ajustes"; adbs install -r "$ajustes"; }

# The stick may sleep (30 min) but must not switch the TV off through HDMI-CEC
sh_ "settings put global hdmi_control_auto_device_off_enabled 0" >/dev/null

# 3. Folders
step "Creando carpetas"
sh_ "mkdir -p $SD/ROMs/gb $SD/ROMs/gbc $SD/ROMs/gba $SD/ROMs/psx \
  $SD/Partidas/saves $SD/Partidas/states $SD/Partidas/.stick \
  $SD/RetroArch/system $SD/RetroArch/autoconfig $SD/RetroArch/overlays $SD/RetroArch/config" >/dev/null

# 4. retroarch.cfg: backup + merge overrides
step "Configurando RetroArch"
has_cfg() { [ "$(sh_ "ls $RA_CFG 2>/dev/null")" = "$RA_CFG" ]; }
if ! has_cfg; then
  echo "Primer arranque de RetroArch para que genere su configuración. Acepta el permiso de almacenamiento en la TV."
  sh_ "monkey -p $RA_PACKAGE -c android.intent.category.LAUNCHER 1" >/dev/null
  for _ in $(seq 60); do has_cfg && break; sleep 2; done
  has_cfg || fail "RetroArch no ha creado retroarch.cfg. Ábrelo en la TV, acepta el permiso y repite."
fi
# RetroArch rewrites its cfg on exit, so it must be closed before editing
sh_ "am force-stop $RA_PACKAGE" >/dev/null
sh_ "[ -f $RA_CFG.orig ] || cp $RA_CFG $RA_CFG.orig; cp $RA_CFG $RA_CFG.bak" >/dev/null
echo "Copias de seguridad: retroarch.cfg.orig (la primera) y retroarch.cfg.bak (la anterior)."

adbs pull "$RA_CFG" "$TMP/retroarch.cfg" >/dev/null
tr -d '\r' < "$OVERRIDES" | grep -E '^[A-Za-z0-9_]+ *= *".*" *$' > "$TMP/overrides" || true
awk '
  NR == FNR { k = $0; sub(/ *=.*/, "", k); v = $0; sub(/^[^=]*= */, "", v); sub(/ *$/, "", v)
              want[k] = k " = " v; order[++n] = k; next }
  { k = $0; sub(/ *=.*/, "", k); if (k in want) { print want[k]; done[k] = 1 } else print }
  END { for (i = 1; i <= n; i++) if (!(order[i] in done)) print want[order[i]] }
' "$TMP/overrides" "$TMP/retroarch.cfg" > "$TMP/retroarch.new.cfg"
adbs push "$TMP/retroarch.new.cfg" "$RA_CFG" >/dev/null
echo "$(wc -l < "$TMP/overrides" | tr -d ' ') claves aplicadas en $RA_CFG"

# 5. Core options, autoconfig and overlays from the repo
step "Opciones de núcleo, mandos y marcos"
for opt in "$HERE"/retroarch/core-options/*/*.opt; do
  [ -f "$opt" ] || continue
  core="$(basename "$(dirname "$opt")")"
  sh_ "mkdir -p '$SD/RetroArch/config/$core'" >/dev/null
  adbs push "$opt" "$SD/RetroArch/config/$core/$(basename "$opt")" >/dev/null && echo "  $core/$(basename "$opt")"
done
# Per-core remaps (config/remaps/<Core>/<Core>.rmp) and overrides (config/<Core>/<Core>.cfg)
for rmp in "$HERE"/retroarch/remaps/*/*.rmp; do
  [ -f "$rmp" ] || continue
  core="$(basename "$(dirname "$rmp")")"
  sh_ "mkdir -p '$SD/RetroArch/config/remaps/$core'" >/dev/null
  adbs push "$rmp" "$SD/RetroArch/config/remaps/$core/$(basename "$rmp")" >/dev/null && echo "  remaps/$core/$(basename "$rmp")"
done
for ovr in "$HERE"/retroarch/overrides/*/*.cfg; do
  [ -f "$ovr" ] || continue
  core="$(basename "$(dirname "$ovr")")"
  sh_ "mkdir -p '$SD/RetroArch/config/$core'" >/dev/null
  adbs push "$ovr" "$SD/RetroArch/config/$core/$(basename "$ovr")" >/dev/null && echo "  $core/$(basename "$ovr")"
done
# Controller profiles keep their driver subfolder (RetroArch reads autoconfig/android/ first).
# Official profiles for the same controller are moved to autoconfig/disabled/, otherwise RetroArch
# would pick one of them at random.
helper=/data/local/tmp/disable-duplicate-autoconfig.sh
adbs push "$HERE/retroarch/disable-duplicate-autoconfig.sh" "$helper" >/dev/null
find "$HERE/retroarch/autoconfig" -name '*.cfg' | while IFS= read -r cfg; do
  sub="$(dirname "${cfg#"$HERE/retroarch/autoconfig"}")"; sub="${sub#/}"; [ "$sub" = "." ] && sub=""
  dir="$SD/RetroArch/autoconfig${sub:+/$sub}"
  sh_ "mkdir -p '$dir'" >/dev/null
  adbs push "$cfg" "$dir/$(basename "$cfg")" >/dev/null && echo "  autoconfig/${sub:+$sub/}$(basename "$cfg")"
  vid="$(tr -d '\r' < "$cfg" | sed -n 's/^input_vendor_id = "\([0-9]*\)"/\1/p')"
  pid="$(tr -d '\r' < "$cfg" | sed -n 's/^input_product_id = "\([0-9]*\)"/\1/p')"
  [ -n "$vid" ] && [ -n "$pid" ] && sh_ "sh $helper $dir $vid $pid"
done
for item in "$HERE"/retroarch/overlays/*; do
  [ -e "$item" ] || continue
  adbs push "$item" "$SD/RetroArch/overlays/" >/dev/null && echo "  overlays/$(basename "$item")"
done

# 6. Pending manual steps
step "Listo. Pasos manuales pendientes en la TV (RetroArch):"
cat <<EOF
  1. Menú principal > Actualizador en línea > Descargar núcleos:
     FCEUmm, Snes9x 2010, Gambatte, mGBA, Genesis Plus GX, PCSX ReARMed y
     Mupen64Plus-Next (Nintendo 64: la versión GLES2, mupen64plus_next_gles2).
  2. Actualizador en línea > Actualizar archivos de información de núcleos.
  3. Actualizador en línea > Actualizar perfiles de mando (OBLIGATORIO: la carpeta
     de autoconfig se ha movido a $SD/RetroArch/autoconfig). Después, vuelve a
     ejecutar este script: retira los perfiles oficiales que chocan con los del repo.
  4. (Opcional) Copia tu BIOS de PS1 a $SD/RetroArch/system.
Revertir la configuración: adb -s $SERIAL shell cp $RA_CFG.orig $RA_CFG
EOF
