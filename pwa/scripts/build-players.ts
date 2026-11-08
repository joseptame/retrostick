/**
 * Builds public/db/players-<console>.json: the games of each console that two or more people can play on the
 * same console (IGDB: offline multiplayer / co-op / split screen), as the loose keys the app matches titles
 * with (looseKey), from their names and alternative names. RetroShop's «Para jugar a dos» filter reads them.
 *
 *   npx tsx scripts/build-players.ts [console…]
 *
 * Needs IGDB_CLIENT_ID and IGDB_CLIENT_SECRET (environment or pwa/.env.local).
 * Output: { generated, games: { [looseKey]: maxPlayers } }
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { looseKey } from '../src/lib/names.ts'

const API = 'https://api.igdb.com/v4'
const PAGE = 500
const CONSOLES: Record<string, number[]> = { nes: [18, 99], snes: [19, 58], gb: [33], gbc: [22], gba: [24], md: [29], sms: [64], psx: [7] }

function env(name: string): string {
  if (process.env[name]) return process.env[name]!
  if (existsSync('.env.local')) {
    const line = readFileSync('.env.local', 'utf8').split(/\r?\n/).find((l) => l.startsWith(`${name}=`))
    if (line) return line.slice(name.length + 1).trim().replace(/^"|"$/g, '')
  }
  throw new Error(`Falta ${name}`)
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const id = env('IGDB_CLIENT_ID')
const secret = env('IGDB_CLIENT_SECRET')
const tok = await fetch(`https://id.twitch.tv/oauth2/token?client_id=${id}&client_secret=${secret}&grant_type=client_credentials`, { method: 'POST' })
const token = ((await tok.json()) as { access_token: string }).access_token

async function igdb<T>(body: string): Promise<T> {
  await sleep(300)
  const res = await fetch(`${API}/games`, { method: 'POST', headers: { 'Client-ID': id, Authorization: `Bearer ${token}` }, body })
  if (!res.ok) throw new Error(`igdb ${res.status}: ${await res.text()}`)
  return res.json() as Promise<T>
}

interface Row {
  name: string
  alternative_names?: { name: string }[]
  game_modes?: number[]
  multiplayer_modes?: { platform?: number; offlinemax?: number; offlinecoopmax?: number }[]
}

const only = process.argv.slice(2)
for (const [consoleId, platforms] of Object.entries(CONSOLES)) {
  if (only.length && !only.includes(consoleId)) continue
  const games: Record<string, number> = {}
  for (let offset = 0; ; offset += PAGE) {
    // 2 multiplayer, 3 co-operative, 4 split screen
    const rows = await igdb<Row[]>(
      `fields name,alternative_names.name,game_modes,multiplayer_modes.platform,multiplayer_modes.offlinemax,multiplayer_modes.offlinecoopmax;` +
        ` where platforms = (${platforms.join(',')}) & game_modes = (2,3,4); limit ${PAGE}; offset ${offset};`,
    )
    for (const g of rows) {
      const modes = (g.multiplayer_modes ?? []).filter((m) => !m.platform || platforms.includes(m.platform))
      const offline = Math.max(0, ...modes.map((m) => Math.max(m.offlinemax ?? 0, m.offlinecoopmax ?? 0)))
      // Known local players; otherwise split screen / co-op / multiplayer without details: assume two
      const unknown = modes.every((m) => m.offlinemax == null && m.offlinecoopmax == null)
      const players = offline > 1 ? offline : (g.game_modes ?? []).includes(4) || unknown ? 2 : 0
      if (players < 2) continue
      for (const n of [g.name, ...(g.alternative_names ?? []).map((a) => a.name)]) {
        const k = looseKey(n)
        if (k) games[k] = Math.max(games[k] ?? 0, players)
      }
    }
    if (rows.length < PAGE) break
  }
  writeFileSync(`public/db/players-${consoleId}.json`, JSON.stringify({ generated: new Date().toISOString(), games }))
  console.log(consoleId, Object.keys(games).length)
}
