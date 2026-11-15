#!/usr/bin/env node
/**
 * Turns the PlayStation games in .bin/.cue on a stick into CHD (one file per disc, 30–50 % smaller, same game).
 *
 *   node stick/psx-to-chd.mjs [--device 192.168.1.106:5555] [--dry-run]
 *
 * For each ROMs/psx/<X>.cue on the stick: copies the .cue and its tracks to the PC, makes <X>.chd with chdman,
 * puts it on the stick under the same name (so saves, states and cheats still match), points any .m3u at the .chd
 * and deletes the .cue and its .bin. The launcher then does the same in Drive on its next sync (DriveSync
 * replaceWithChd: the old files go to Drive's bin, the CHD is uploaded), so it never downloads them again.
 *
 * Needs adb, and chdman (MAME's tool): CHDMAN=<path>, or chdman in the PATH, or stick/tools/chdman.exe.
 */
import { spawnSync } from 'node:child_process'
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const args = process.argv.slice(2)
const device = args.includes('--device') ? args[args.indexOf('--device') + 1] : '192.168.1.106:5555'
const dryRun = args.includes('--dry-run')
const DIR = '/storage/emulated/0/ROMs/psx'
const here = dirname(fileURLToPath(import.meta.url))

/** A path quoted for the stick's shell (names carry spaces, brackets and apostrophes). */
const q = (s) => `'${s.replace(/'/g, `'\\''`)}'`

function run(cmd, cmdArgs, opts = {}) {
  const r = spawnSync(cmd, cmdArgs, { encoding: opts.binary ? 'buffer' : 'utf8', maxBuffer: 1 << 30, env: { ...process.env, MSYS_NO_PATHCONV: '1' } })
  if (r.status !== 0) throw new Error(`${cmd} ${cmdArgs.slice(0, 3).join(' ')}…: ${(r.stderr || r.stdout || '').toString().trim()}`)
  return r.stdout
}
const adb = (...a) => run('adb', ['-s', device, ...a])
const shell = (command) => adb('shell', command)

function chdman() {
  for (const c of [process.env.CHDMAN, join(here, 'tools', 'chdman.exe'), join(here, 'tools', 'chdman')]) if (c && existsSync(c)) return c
  if (!spawnSync('chdman', ['--help']).error) return 'chdman'
  throw new Error('Falta chdman: ponlo en stick/tools/chdman.exe (viene con MAME, mamedev.org) o indica CHDMAN=<ruta>.')
}

run('adb', ['connect', device])
const files = shell(`ls -1 ${q(DIR)} 2>/dev/null`).split('\n').map((s) => s.trim()).filter(Boolean)
const cues = files.filter((f) => /\.cue$/i.test(f))
if (!cues.length) {
  console.log('No hay juegos de PlayStation en .bin/.cue en el stick: nada que convertir.')
  process.exit(0)
}
console.log(`${cues.length} disco(s) en .bin/.cue:\n  ${cues.join('\n  ')}`)
if (dryRun) process.exit(0)

const tool = chdman()
const work = mkdtempSync(join(tmpdir(), 'psx-chd-'))
let done = 0
try {
  for (const cue of cues) {
    const base = cue.replace(/\.cue$/i, '')
    const chd = `${base}.chd`
    console.log(`\n· ${base}`)
    const cueText = shell(`cat ${q(`${DIR}/${cue}`)}`)
    const tracks = [...cueText.matchAll(/^\s*FILE\s+"(.+?)"/gim)].map((m) => m[1])
    if (!tracks.length || tracks.some((t) => !files.includes(t))) {
      console.log('  faltan pistas en el stick: lo dejo como está')
      continue
    }
    // To the PC, convert, check
    for (const f of [cue, ...tracks]) adb('pull', `${DIR}/${f}`, join(work, f))
    run(tool, ['createcd', '-i', join(work, cue), '-o', join(work, chd), '-f'])
    const size = statSync(join(work, chd)).size
    const before = [cue, ...tracks].reduce((n, f) => n + statSync(join(work, f)).size, 0)
    // Back to the stick: under a temporary name, then renamed, so a cut leaves nothing half-written
    adb('push', join(work, chd), `${DIR}/.${chd}.part`)
    const onStick = Number(shell(`stat -c %s ${q(`${DIR}/.${chd}.part`)}`).trim())
    if (onStick !== size) throw new Error(`${chd}: en el stick ocupa ${onStick} y debería ${size}`)
    shell(`mv ${q(`${DIR}/.${chd}.part`)} ${q(`${DIR}/${chd}`)}`)
    // Disc lists that named the .cue now name the .chd
    for (const m3u of files.filter((f) => /\.m3u$/i.test(f))) {
      const text = shell(`cat ${q(`${DIR}/${m3u}`)}`)
      if (!text.split('\n').some((l) => l.trim() === cue)) continue
      const local = join(work, m3u)
      writeFileSync(local, text.split('\n').map((l) => (l.trim() === cue ? chd : l.replace(/\r$/, ''))).join('\n'))
      adb('push', local, `${DIR}/${m3u}`)
    }
    for (const f of [cue, ...tracks]) shell(`rm -f ${q(`${DIR}/${f}`)}`)
    for (const f of [cue, ...tracks]) rmSync(join(work, f), { force: true })
    rmSync(join(work, chd), { force: true })
    console.log(`  hecho: ${(before / 1e6).toFixed(0)} MB → ${(size / 1e6).toFixed(0)} MB`)
    done++
  }
} finally {
  rmSync(work, { recursive: true, force: true })
}
console.log(`\n${done} disco(s) convertidos. RetroStick los cambia también en Drive en su próxima sincronización.`)
