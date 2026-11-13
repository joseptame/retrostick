// Builds RetroStick's configuration pack from a working stick (read-only: adb pull), for new sticks to set
// themselves up (the launcher's welcome assistant, Setup.kt). Contents, relative to /sdcard:
//   RetroArch/config/<Core>/        core options (.opt), per-core and per-console overrides (.cfg), remaps (.rmp)
//                                   — not the per-game files (RetroAchievements switches, saved settings of one game)
//   RetroArch/autoconfig/           controller profiles, incl. RetroStick's DualSense / Pro and the disabled officials
//   RetroArch/overlays/retrostick/  the console frames
//   retroarch-keys.cfg              the keys of retroarch.cfg that differ from RetroArch's own defaults
//                                   (diff against retroarch.cfg.orig, saved by install.ps1), minus personal ones
//   pack.json                       { version, created }
// Nothing personal: no RetroAchievements account, no Drive or web sessions, no saves, BIOS or games.
//
//   node stick/config-pack.mjs [adb serial]      → stick/dist/retrostick-config.zip
import { execFileSync } from 'node:child_process'
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { join, relative } from 'node:path'

const serial = process.argv[2] ?? '192.168.1.106:5555'
const here = import.meta.dirname
const work = join(here, 'dist', 'pack')
const pulled = join(here, 'dist', 'pulled')
const RA_CFG = '/sdcard/Android/data/com.retroarch.ra32/files/retroarch.cfg'

const CONSOLES = ['nes', 'snes', 'gb', 'gbc', 'gba', 'md', 'sms', 'n64', 'neogeo', 'psx']
// Keys never copied: accounts and anything tied to one person or one stick
const PERSONAL = /^(cheevos_(username|password|token)|netplay_nickname|.*_stream_key|webdav_.*|discord_.*|.*_password)$/

const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], { stdio: ['ignore', 'pipe', 'inherit'] })

rmSync(join(here, 'dist'), { recursive: true, force: true })
mkdirSync(pulled, { recursive: true })
for (const dir of ['RetroArch/config', 'RetroArch/autoconfig', 'RetroArch/overlays/retrostick']) {
  mkdirSync(join(pulled, dir, '..'), { recursive: true })
  adb('pull', `/sdcard/${dir}`, join(pulled, dir, '..'))
}
adb('pull', RA_CFG, join(pulled, 'retroarch.cfg'))
adb('pull', `${RA_CFG}.orig`, join(pulled, 'retroarch.cfg.orig'))

// Config: only what belongs to a core or a console, not to one game
const files = (dir) => readdirSync(dir).flatMap((n) => statSync(join(dir, n)).isDirectory() ? files(join(dir, n)) : [join(dir, n)])
const configRoot = join(pulled, 'RetroArch/config')
let kept = 0
for (const f of files(configRoot)) {
  const rel = relative(configRoot, f).split('\\').join('/')
  const parts = rel.split('/')
  const coreDir = parts[0] === 'remaps' ? parts[1] : parts[0]
  const base = parts.at(-1).replace(/\.(cfg|opt|rmp)$/, '')
  if (!(base === coreDir || CONSOLES.includes(base))) {
    console.log(`  (fuera, es de un juego) ${rel}`)
    continue
  }
  cpSync(f, join(work, 'RetroArch/config', rel))
  kept++
}
cpSync(join(pulled, 'RetroArch/autoconfig'), join(work, 'RetroArch/autoconfig'), { recursive: true })
cpSync(join(pulled, 'RetroArch/overlays/retrostick'), join(work, 'RetroArch/overlays/retrostick'), { recursive: true })

// retroarch.cfg: what this stick changed from RetroArch's defaults
const load = (p) => Object.fromEntries(readFileSync(p, 'utf8').split(/\r?\n/)
  .map((l) => /^(\S+) = "(.*)"$/.exec(l)).filter(Boolean).map((m) => [m[1], m[2]]))
const before = load(join(pulled, 'retroarch.cfg.orig'))
const now = load(join(pulled, 'retroarch.cfg'))
const keys = Object.entries(now).filter(([k, v]) => before[k] !== v && !PERSONAL.test(k))
// Achievements stay off until the new stick signs in to its own account (RetroAchievements.kt turns them on)
const lines = keys.map(([k, v]) => `${k} = "${k === 'cheevos_enable' ? 'false' : v}"`)
writeFileSync(join(work, 'retroarch-keys.cfg'), `# RetroStick: retroarch.cfg keys (merged by the launcher)\n${lines.join('\n')}\n`)

const version = new Date().toISOString().slice(0, 16).replace(/[-:T]/g, '')
writeFileSync(join(work, 'pack.json'), JSON.stringify({ version, created: new Date().toISOString() }, null, 2))

const zip = join(here, 'dist', 'retrostick-config.zip')
const tar = process.platform === 'win32' ? 'C:/Windows/System32/tar.exe' : 'bsdtar'
execFileSync(tar, ['-a', '-c', '-f', zip, '-C', work, '.'])
console.log(`${kept} archivos de configuración, ${keys.length} claves de retroarch.cfg, versión ${version}`)
console.log(`${zip} (${(statSync(zip).size / 1024).toFixed(0)} KB)`)
if (!existsSync(zip)) process.exit(1)
