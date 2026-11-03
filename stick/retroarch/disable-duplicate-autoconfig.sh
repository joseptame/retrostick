#!/system/bin/sh
# Runs on the stick (pushed by install.ps1 / install.sh).
# Usage: disable-duplicate-autoconfig.sh <autoconfig_dir> <vendor_id> <product_id>
# RetroArch picks an arbitrary file when several autoconfigs match the same name and vid/pid, so every
# file for that controller except RetroStick's own is moved to ../disabled (reversible).
dir="$1"
vid="$2"
pid="$3"
mkdir -p "$dir/../disabled"
for f in "$dir"/*.cfg; do
  case "$(basename "$f")" in *RetroStick*) continue ;; esac
  if grep -q "^input_vendor_id = \"$vid\"" "$f" && grep -q "^input_product_id = \"$pid\"" "$f"; then
    mv "$f" "$dir/../disabled/" && echo "  desactivado: $(basename "$f")"
  fi
done
