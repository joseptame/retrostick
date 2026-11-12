<#
.SYNOPSIS
  RetroStick installer for the Xiaomi Mi TV Stick. Idempotent: safe to run again.
.EXAMPLE
  .\stick\install.ps1 -Stick 192.168.1.50
#>
param(
  [Parameter(Mandatory = $true)][string]$Stick,
  [string]$RetroArchVersion = '1.22.2'
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$Serial = if ($Stick -match ':') { $Stick } else { "${Stick}:5555" }
$Sd = '/storage/emulated/0'
$RaPackage = 'com.retroarch.ra32'
$RaApk = Join-Path $Here "apks\RetroArch_ra32_$RetroArchVersion.apk"
$RaApkUrl = "https://buildbot.libretro.com/stable/$RetroArchVersion/android/RetroArch_ra32.apk"
$RaCfg = "$Sd/Android/data/$RaPackage/files/retroarch.cfg"
$Overrides = Join-Path $Here 'retroarch\retroarch.overrides.cfg'
$Tmp = Join-Path ([IO.Path]::GetTempPath()) 'retrostick'

function Step([string]$msg) { Write-Host "`n==> $msg" -ForegroundColor Cyan }
function Fail([string]$msg) { Write-Host "ERROR: $msg" -ForegroundColor Red; exit 1 }
$AdbExe = (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $AdbExe) { Fail 'adb no esta en el PATH (Android SDK platform-tools).' }
function Invoke-Adb { & $AdbExe -s $Serial @args; if ($LASTEXITCODE -ne 0) { Fail "adb $($args -join ' ') ha fallado" } }
function Sh([string]$cmd) { ((& $AdbExe -s $Serial shell $cmd) | Out-String).Trim() }

New-Item -ItemType Directory -Force $Tmp, (Split-Path $RaApk) | Out-Null

# 1. Connection and architecture
Step "Conectando con $Serial"
& $AdbExe connect $Serial | Out-Null
$state = ((& $AdbExe -s $Serial get-state 2>$null) | Out-String).Trim()
if ($state -ne 'device') { Fail "El stick no esta listo (estado: '$state'). Activa la depuracion USB y acepta el aviso en la TV." }
$abi = Sh 'getprop ro.product.cpu.abilist'
$release = Sh 'getprop ro.build.version.release'
Write-Host "ABI: $abi | Android $release"
if ($abi -notmatch 'armeabi-v7a') { Fail "ABI inesperada ($abi): se esperaba armeabi-v7a." }
if ($release -ne '9') { Write-Host "AVISO: se esperaba Android 9 y es $release." -ForegroundColor Yellow }

# 2. RetroArch 32-bit
Step "RetroArch $RetroArchVersion (32 bits)"
$installed = Sh "dumpsys package $RaPackage | grep -m1 versionName"
if ($installed -match "versionName=$([regex]::Escape($RetroArchVersion))") {
  Write-Host "Ya instalado ($installed)."
} else {
  if (-not (Test-Path $RaApk)) {
    Write-Host "Descargando $RaApkUrl"
    Invoke-WebRequest -UseBasicParsing -Uri $RaApkUrl -OutFile $RaApk
  }
  Invoke-Adb install -r $RaApk
}

# Retro Ajustes (phase 4): installed only once the APK has been built
$ajustes = Get-ChildItem (Join-Path $Here '..\android\app\build\outputs\apk\release\*.apk') -ErrorAction SilentlyContinue | Select-Object -First 1
if ($ajustes) { Step 'Retro Ajustes'; Invoke-Adb install -r $ajustes.FullName }

# The stick may sleep (30 min) but must not switch the TV off through HDMI-CEC
Sh 'settings put global hdmi_control_auto_device_off_enabled 0' | Out-Null

# 3. Folders
Step 'Creando carpetas'
$dirs = @('ROMs/gb', 'ROMs/gbc', 'ROMs/gba', 'ROMs/psx', 'Partidas/saves', 'Partidas/states', 'Partidas/.stick',
  'RetroArch/system', 'RetroArch/autoconfig', 'RetroArch/overlays', 'RetroArch/config') | ForEach-Object { "$Sd/$_" }
Sh "mkdir -p $($dirs -join ' ')" | Out-Null

# 4. retroarch.cfg: backup + merge overrides
Step 'Configurando RetroArch'
if ((Sh "ls $RaCfg 2>/dev/null") -ne $RaCfg) {
  Write-Host 'Primer arranque de RetroArch para que genere su configuracion. Acepta el permiso de almacenamiento en la TV.'
  Sh "monkey -p $RaPackage -c android.intent.category.LAUNCHER 1" | Out-Null
  for ($i = 0; $i -lt 60 -and (Sh "ls $RaCfg 2>/dev/null") -ne $RaCfg; $i++) { Start-Sleep -Seconds 2 }
  if ((Sh "ls $RaCfg 2>/dev/null") -ne $RaCfg) { Fail 'RetroArch no ha creado retroarch.cfg. Abrelo en la TV, acepta el permiso y repite.' }
}
# RetroArch rewrites its cfg on exit, so it must be closed before editing
Sh "am force-stop $RaPackage" | Out-Null
Sh "[ -f $RaCfg.orig ] || cp $RaCfg $RaCfg.orig; cp $RaCfg $RaCfg.bak" | Out-Null
Write-Host "Copias de seguridad: retroarch.cfg.orig (la primera) y retroarch.cfg.bak (la anterior)."

$local = Join-Path $Tmp 'retroarch.cfg'
Invoke-Adb pull $RaCfg $local | Out-Null
$lines = [Collections.Generic.List[string]](Get-Content -LiteralPath $local -Encoding UTF8)
$wanted = [ordered]@{}
foreach ($l in Get-Content -LiteralPath $Overrides -Encoding UTF8) {
  if ($l -match '^\s*([A-Za-z0-9_]+)\s*=\s*(".*")\s*$') { $wanted[$Matches[1]] = $Matches[2] }
}
foreach ($key in $wanted.Keys) {
  $line = "$key = $($wanted[$key])"
  $idx = $lines.FindIndex({ param($x) $x -match "^$([regex]::Escape($key))\s*=" })
  if ($idx -ge 0) { $lines[$idx] = $line } else { $lines.Add($line) }
}
[IO.File]::WriteAllText($local, (($lines -join "`n") + "`n"), (New-Object Text.UTF8Encoding $false))
Invoke-Adb push $local $RaCfg | Out-Null
Write-Host "$($wanted.Count) claves aplicadas en $RaCfg"

# 5. Core options, autoconfig and overlays from the repo
Step 'Opciones de nucleo, mandos y marcos'
foreach ($opt in Get-ChildItem (Join-Path $Here 'retroarch\core-options') -Recurse -Filter *.opt -ErrorAction SilentlyContinue) {
  $core = $opt.Directory.Name
  Sh "mkdir -p '$Sd/RetroArch/config/$core'" | Out-Null
  Invoke-Adb push $opt.FullName "$Sd/RetroArch/config/$core/$($opt.Name)" | Out-Null
  Write-Host "  $core/$($opt.Name)"
}
# Per-core remaps (config/remaps/<Core>/<Core>.rmp) and overrides (config/<Core>/<Core>.cfg)
foreach ($rmp in Get-ChildItem (Join-Path $Here 'retroarch\remaps') -Recurse -Filter *.rmp -ErrorAction SilentlyContinue) {
  $core = $rmp.Directory.Name
  Sh "mkdir -p '$Sd/RetroArch/config/remaps/$core'" | Out-Null
  Invoke-Adb push $rmp.FullName "$Sd/RetroArch/config/remaps/$core/$($rmp.Name)" | Out-Null
  Write-Host "  remaps/$core/$($rmp.Name)"
}
foreach ($ovr in Get-ChildItem (Join-Path $Here 'retroarch\overrides') -Recurse -Filter *.cfg -ErrorAction SilentlyContinue) {
  $core = $ovr.Directory.Name
  Sh "mkdir -p '$Sd/RetroArch/config/$core'" | Out-Null
  Invoke-Adb push $ovr.FullName "$Sd/RetroArch/config/$core/$($ovr.Name)" | Out-Null
  Write-Host "  $core/$($ovr.Name)"
}
# Controller profiles keep their driver subfolder (RetroArch reads autoconfig/android/ first).
# Official profiles for the same controller are moved to autoconfig/disabled/, otherwise RetroArch
# would pick one of them at random.
$autoRoot = Join-Path $Here 'retroarch\autoconfig'
$helper = '/data/local/tmp/disable-duplicate-autoconfig.sh'
Invoke-Adb push (Join-Path $Here 'retroarch\disable-duplicate-autoconfig.sh') $helper | Out-Null
foreach ($cfg in Get-ChildItem $autoRoot -Recurse -Filter *.cfg -ErrorAction SilentlyContinue) {
  $sub = $cfg.Directory.FullName.Substring($autoRoot.Length).TrimStart('\').Replace('\', '/')
  $dir = if ($sub) { "$Sd/RetroArch/autoconfig/$sub" } else { "$Sd/RetroArch/autoconfig" }
  Sh "mkdir -p '$dir'" | Out-Null
  Invoke-Adb push $cfg.FullName "$dir/$($cfg.Name)" | Out-Null
  Write-Host "  autoconfig/$(if ($sub) { "$sub/" })$($cfg.Name)"
  $text = Get-Content -LiteralPath $cfg.FullName -Raw
  if ($text -match '(?m)^input_vendor_id = "(\d+)"' -and ($vid = $Matches[1]) -and
      $text -match '(?m)^input_product_id = "(\d+)"' -and ($prodId = $Matches[1])) {
    $out = Sh "sh $helper $dir $vid $prodId"
    if ($out) { Write-Host $out }
  }
}
foreach ($item in Get-ChildItem (Join-Path $Here 'retroarch\overlays') -Exclude .gitkeep -ErrorAction SilentlyContinue) {
  Invoke-Adb push $item.FullName "$Sd/RetroArch/overlays/" | Out-Null
  Write-Host "  overlays/$($item.Name)"
}

# 6. Pending manual steps
Step 'Listo. Pasos manuales pendientes en la TV (RetroArch):'
Write-Host @"
  1. Menu principal > Actualizador en linea > Descargar nucleos:
     FCEUmm, Snes9x 2010, Gambatte, mGBA, Genesis Plus GX, PCSX ReARMed y
     Mupen64Plus-Next (Nintendo 64: la version GLES2, mupen64plus_next_gles2),
     y FinalBurn Neo (Neo Geo).
  2. Actualizador en linea > Actualizar archivos de informacion de nucleos.
  3. Actualizador en linea > Actualizar perfiles de mando (OBLIGATORIO: la carpeta
     de autoconfig se ha movido a $Sd/RetroArch/autoconfig). Despues, vuelve a
     ejecutar este script: retira los perfiles oficiales que chocan con los del repo.
  4. (Opcional) Copia tu BIOS de PS1 a $Sd/RetroArch/system.
Revertir la configuracion: adb -s $Serial shell cp $RaCfg.orig $RaCfg
"@
