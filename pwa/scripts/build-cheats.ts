/**
 * Builds public/db/cheats-<console>.json: the cheat files libretro-database has for each console (cht/<System>/,
 * the ones RetroArch's own «Online Updater → Cheats» brings), by name without .cht. The stick matches a game
 * against them (Cheats.kt) and downloads only that game's files from GitHub.
 *
 *   npx tsx scripts/build-cheats.ts [console…]
 *
 * GITHUB_TOKEN (environment or pwa/.env.local) is optional: without it GitHub allows 60 requests an hour, plenty.
 * Output: { generated, folder, files: [name…] }
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'

const REPO = 'libretro/libretro-database'
// Neo Geo is left out: FinalBurn Neo has its own cheat files, not these
const CONSOLES: Record<string, string> = {
  nes: 'Nintendo - Nintendo Entertainment System',
  snes: 'Nintendo - Super Nintendo Entertainment System',
  gb: 'Nintendo - Game Boy',
  gbc: 'Nintendo - Game Boy Color',
  gba: 'Nintendo - Game Boy Advance',
  md: 'Sega - Mega Drive - Genesis',
  sms: 'Sega - Master System - Mark III',
  n64: 'Nintendo - Nintendo 64',
  psx: 'Sony - PlayStation',
}

function env(name: string): string | undefined {
  if (process.env[name]) return process.env[name]
  if (existsSync('.env.local')) {
    const line = readFileSync('.env.local', 'utf8').split(/\r?\n/).find((l) => l.startsWith(`${name}=`))
    if (line) return line.slice(name.length + 1).trim().replace(/^"|"$/g, '')
  }
}
const token = env('GITHUB_TOKEN')
const headers: Record<string, string> = { Accept: 'application/vnd.github+json', ...(token ? { Authorization: `Bearer ${token}` } : {}) }

const only = process.argv.slice(2)
for (const [consoleId, folder] of Object.entries(CONSOLES)) {
  if (only.length && !only.includes(consoleId)) continue
  const res = await fetch(`https://api.github.com/repos/${REPO}/git/trees/${encodeURIComponent(`master:cht/${folder}`)}`, { headers })
  if (!res.ok) throw new Error(`${consoleId}: GitHub ${res.status} ${await res.text()}`)
  const tree = (await res.json()) as { truncated: boolean; tree: { path: string; type: string }[] }
  if (tree.truncated) throw new Error(`${consoleId}: listado incompleto`)
  const files = tree.tree
    .filter((e) => e.type === 'blob' && e.path.toLowerCase().endsWith('.cht'))
    .map((e) => e.path.slice(0, -4))
    .sort((a, b) => a.localeCompare(b, 'en'))
  writeFileSync(`public/db/cheats-${consoleId}.json`, JSON.stringify({ generated: new Date().toISOString(), folder, files }))
  console.log(`${consoleId}: ${files.length} archivos de trucos`)
}
