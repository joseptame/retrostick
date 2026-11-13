// Publishes what a stick downloads by itself to the repository's «stick» release on GitHub (Setup.kt, Updater.kt):
//   --retroarch   stick/apks/RetroArch_ra32_<version>.apk          → RetroArch_ra32.apk
//   --cores       android/cores/build/outputs/apk/debug (fetch-cores.mjs + :cores:assembleDebug) → retrostick-cores.apk
//   --config      stick/dist/retrostick-config.zip (config-pack.mjs) → retrostick-config.zip
//   --launcher    android/app/build/outputs/apk/debug                → launcher.apk + launcher.json
// With no option, all four. setup.json (what the welcome assistant reads) is updated last, after the files it names.
// Needs the GitHub CLI (gh) signed in with access to the repository.
//
//   node stick/publish-stick.mjs [--retroarch] [--cores] [--config] [--launcher]
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const TAG = 'stick'
const root = join(import.meta.dirname, '..')
const out = join(import.meta.dirname, 'dist', 'release')
mkdirSync(out, { recursive: true })

const args = process.argv.slice(2)
const all = args.length === 0
const want = (k) => all || args.includes(`--${k}`)

const gh = (...a) => execFileSync('gh', a, { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
const sha256 = (file) => createHash('sha256').update(readFileSync(file)).digest('hex')
const describe = (file, name, extra) => ({ file: name, sha256: sha256(file), size: readFileSync(file).length, ...extra })

// The release, created the first time
try {
  gh('release', 'view', TAG)
} catch {
  gh('release', 'create', TAG, '--title', 'RetroStick para el stick',
    '--notes', 'Lo que descarga un stick nuevo con el asistente de bienvenida del launcher (RetroArch, núcleos, configuración) y las actualizaciones del launcher. Se actualiza con stick/publish-stick.mjs.')
}

// Current setup.json, to keep the entries not being published now
let setup = {}
try {
  gh('release', 'download', TAG, '--pattern', 'setup.json', '--dir', out, '--clobber')
  setup = JSON.parse(readFileSync(join(out, 'setup.json'), 'utf8'))
} catch { /* first time */ }

const uploads = []
const stage = (src, name) => {
  const dest = join(out, name)
  copyFileSync(src, dest)
  uploads.push(dest)
  return dest
}
const apkMeta = (dir) => JSON.parse(readFileSync(join(dir, 'output-metadata.json'), 'utf8')).elements[0]

if (want('retroarch')) {
  const dir = join(root, 'stick', 'apks')
  const apk = readdirSync(dir).find((f) => /^RetroArch_ra32_[\d.]+\.apk$/.test(f))
  if (!apk) throw new Error('Falta stick/apks/RetroArch_ra32_<versión>.apk (install.ps1 lo descarga)')
  const version = apk.match(/_([\d.]+)\.apk$/)[1]
  setup.retroarch = describe(stage(join(dir, apk), 'RetroArch_ra32.apk'), 'RetroArch_ra32.apk', { version })
}
if (want('cores')) {
  const dir = join(root, 'android', 'cores', 'build', 'outputs', 'apk', 'debug')
  if (!existsSync(join(dir, 'cores-debug.apk'))) throw new Error('Compila antes: node android/cores/fetch-cores.mjs && ./gradlew :cores:assembleDebug')
  const meta = apkMeta(dir)
  setup.cores = describe(stage(join(dir, 'cores-debug.apk'), 'retrostick-cores.apk'), 'retrostick-cores.apk',
    { version: meta.versionName, versionCode: meta.versionCode })
}
if (want('config')) {
  const zip = join(root, 'stick', 'dist', 'retrostick-config.zip')
  if (!existsSync(zip)) throw new Error('Genera antes el paquete: node stick/config-pack.mjs')
  const { version } = JSON.parse(readFileSync(join(root, 'stick', 'dist', 'pack', 'pack.json'), 'utf8'))
  setup.config = describe(stage(zip, 'retrostick-config.zip'), 'retrostick-config.zip', { version })
}
let launcherJson = null
if (want('launcher')) {
  const dir = join(root, 'android', 'app', 'build', 'outputs', 'apk', 'debug')
  const meta = apkMeta(dir)
  const apk = stage(join(dir, 'app-debug.apk'), 'launcher.apk')
  launcherJson = join(out, 'launcher.json')
  writeFileSync(launcherJson, JSON.stringify({
    versionCode: meta.versionCode, versionName: meta.versionName, apk: 'launcher.apk', sha256: sha256(apk), size: readFileSync(apk).length,
  }, null, 2))
}

for (const f of uploads) {
  console.log(`Subiendo ${f.split(/[\\/]/).pop()}…`)
  gh('release', 'upload', TAG, f, '--clobber')
}
// The manifests last: a stick only downloads what they announce
if (launcherJson) gh('release', 'upload', TAG, launcherJson, '--clobber')
if (setup.retroarch && setup.cores && setup.config) {
  writeFileSync(join(out, 'setup.json'), JSON.stringify(setup, null, 2))
  gh('release', 'upload', TAG, join(out, 'setup.json'), '--clobber')
}
console.log(JSON.stringify(setup, null, 2))
if (launcherJson) console.log(readFileSync(launcherJson, 'utf8'))
