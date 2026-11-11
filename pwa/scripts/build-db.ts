/**
 * Builds public/db/<console>.json from libretro-database (No-Intro / Redump DATs) and the
 * libretro-thumbnails box art index (README 7.4 / 7.5).
 *
 *   npm run build-db
 *
 * Output per console: { names: [...], sha1: {sha1: nameIndex}, crc: {crc32: nameIndex}, boxarts: [...] }
 * The files are committed so builds (Vercel) don't depend on GitHub rate limits.
 */
import { mkdirSync, writeFileSync } from 'node:fs'

const DB = 'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat'
const THUMBS = 'https://api.github.com/repos/libretro-thumbnails'

const consoles = [
  { id: 'gb', dat: `${DB}/no-intro/Nintendo%20-%20Game%20Boy.dat`, thumbs: 'Nintendo_-_Game_Boy' },
  { id: 'gbc', dat: `${DB}/no-intro/Nintendo%20-%20Game%20Boy%20Color.dat`, thumbs: 'Nintendo_-_Game_Boy_Color' },
  { id: 'gba', dat: `${DB}/no-intro/Nintendo%20-%20Game%20Boy%20Advance.dat`, thumbs: 'Nintendo_-_Game_Boy_Advance' },
  { id: 'nes', dat: `${DB}/no-intro/Nintendo%20-%20Nintendo%20Entertainment%20System.dat`, thumbs: 'Nintendo_-_Nintendo_Entertainment_System' },
  { id: 'snes', dat: `${DB}/no-intro/Nintendo%20-%20Super%20Nintendo%20Entertainment%20System.dat`, thumbs: 'Nintendo_-_Super_Nintendo_Entertainment_System' },
  { id: 'md', dat: `${DB}/no-intro/Sega%20-%20Mega%20Drive%20-%20Genesis.dat`, thumbs: 'Sega_-_Mega_Drive_-_Genesis' },
  { id: 'sms', dat: `${DB}/no-intro/Sega%20-%20Master%20System%20-%20Mark%20III.dat`, thumbs: 'Sega_-_Master_System_-_Mark_III' },
  { id: 'n64', dat: `${DB}/no-intro/Nintendo%20-%20Nintendo%2064.dat`, thumbs: 'Nintendo_-_Nintendo_64' },
  // Redump: discs are identified by name only (hashing a CD image in the browser is too slow).
  { id: 'psx', dat: `${DB}/redump/Sony%20-%20PlayStation.dat`, thumbs: 'Sony_-_PlayStation' },
]

const headers: Record<string, string> = { 'User-Agent': 'retrostick-build-db' }
if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`

async function text(url: string): Promise<string> {
  const res = await fetch(url, { headers })
  if (!res.ok) throw new Error(`${res.status} ${url}`)
  return res.text()
}

async function json(url: string): Promise<any> {
  return JSON.parse(await text(url))
}

/** clrmamepro DAT: game ( name "..." rom ( name "..." size N crc XXXXXXXX sha1 ... ) ) */
function parseDat(dat: string) {
  const games: { name: string; crc: string[]; sha1: string[] }[] = []
  for (const block of dat.split(/\ngame \(/).slice(1)) {
    const name = /^\s*name "((?:[^"\\]|\\.)*)"/m.exec(block)?.[1]
    if (!name) continue
    const crc = [...block.matchAll(/\bcrc ([0-9A-Fa-f]{8})\b/g)].map((m) => m[1].toLowerCase())
    const sha1 = [...block.matchAll(/\bsha1 ([0-9A-Fa-f]{40})\b/g)].map((m) => m[1].toLowerCase())
    games.push({ name, crc, sha1 })
  }
  return games
}

async function boxarts(repo: string): Promise<string[]> {
  const root = await json(`${THUMBS}/${repo}/git/trees/master`)
  const dir = root.tree.find((e: any) => e.path === 'Named_Boxarts')
  if (!dir) return []
  const tree = await json(`${THUMBS}/${repo}/git/trees/${dir.sha}`)
  return tree.tree.map((e: any) => e.path as string).filter((p: string) => p.endsWith('.png'))
    .map((p: string) => p.slice(0, -4)).sort()
}

mkdirSync('public/db', { recursive: true })
// `npm run build-db -- nes snes` rebuilds only those consoles.
const only = process.argv.slice(2)
for (const c of consoles) {
  if (only.length && !only.includes(c.id)) continue
  const games = parseDat(await text(c.dat))
  const names = games.map((g) => g.name)
  const sha1: Record<string, number> = {}
  const crc: Record<string, number> = {}
  games.forEach((g, i) => {
    // Cartridges only: one ROM per game. Multi-track discs are matched by name.
    if (c.id !== 'psx') {
      g.sha1.forEach((h) => (sha1[h] = i))
      g.crc.forEach((h) => (crc[h] = i))
    }
  })
  const out = { generated: new Date().toISOString().slice(0, 10), names, sha1, crc, boxarts: await boxarts(c.thumbs) }
  writeFileSync(`public/db/${c.id}.json`, JSON.stringify(out))
  console.log(`${c.id}: ${names.length} juegos, ${Object.keys(sha1).length} sha1, ${out.boxarts.length} portadas`)
}
