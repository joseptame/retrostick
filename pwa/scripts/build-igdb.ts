/**
 * Builds public/db/igdb-<console>.json: genres and year (IGDB) and press score (Wikipedia's reviews
 * table: Metacritic / GameRankings / critics; else IGDB's critic rating) of every game of each console,
 * keyed by the same loose name the app uses to match titles (looseKey), in English, Spanish and alias
 * forms (Wikidata). Buscar's genre and rating filters read these files: instant, no API calls at runtime.
 *
 *   npm run build-igdb
 *
 * Needs IGDB_CLIENT_ID and IGDB_CLIENT_SECRET (from the environment or pwa/.env.local).
 * Output: { generated, genres: [name...], games: { [looseKey]: [genreIndexes, rating|null, year|null] } }
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { looseKey } from '../src/lib/names.ts'
import { articleScore, pressFor, wikitexts } from './press.ts'

const API = 'https://api.igdb.com/v4'
const PAGE = 500
/** IGDB platform ids */
const CONSOLES = [
  { id: 'gb', platform: 33 },
  { id: 'gbc', platform: 22 },
  { id: 'gba', platform: 24 },
  { id: 'nes', platform: 18 },
  { id: 'snes', platform: 19 },
  { id: 'psx', platform: 7 },
]

function env(name: string): string {
  if (process.env[name]) return process.env[name]!
  if (existsSync('.env.local')) {
    const line = readFileSync('.env.local', 'utf8')
      .split(/\r?\n/)
      .find((l) => l.startsWith(`${name}=`))
    if (line) return line.slice(name.length + 1).trim().replace(/^"|"$/g, '')
  }
  throw new Error(`Falta ${name} (variable de entorno o .env.local)`)
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

const id = env('IGDB_CLIENT_ID')
const secret = env('IGDB_CLIENT_SECRET')
const tokenRes = await fetch(
  `https://id.twitch.tv/oauth2/token?client_id=${id}&client_secret=${secret}&grant_type=client_credentials`,
  { method: 'POST' },
)
if (!tokenRes.ok) throw new Error(`twitch oauth ${tokenRes.status}`)
const token = ((await tokenRes.json()) as { access_token: string }).access_token

/** IGDB allows 4 requests per second. */
async function igdb<T>(endpoint: string, body: string): Promise<T> {
  await sleep(300)
  const res = await fetch(`${API}/${endpoint}`, {
    method: 'POST',
    headers: { 'Client-ID': id, Authorization: `Bearer ${token}`, Accept: 'application/json' },
    body,
  })
  if (!res.ok) throw new Error(`igdb ${endpoint} ${res.status}: ${await res.text()}`)
  return res.json() as Promise<T>
}

interface Game {
  name: string
  alternative_names?: { name: string }[]
  genres?: number[]
  platforms?: number[]
  websites?: { url: string }[]
  /** Critics' score aggregated by IGDB (press), 0..100 */
  aggregated_rating?: number
  first_release_date?: number
}

const genreList = await igdb<{ id: number; name: string }[]>('genres', 'fields name; limit 100;')
const genreName = new Map(genreList.map((g) => [g.id, g.name]))

mkdirSync('public/db', { recursive: true })
// `npm run build-igdb -- nes snes` rebuilds only those consoles.
const only = process.argv.slice(2)
for (const c of CONSOLES) {
  if (only.length && !only.includes(c.id)) continue
  const games: Game[] = []
  for (let offset = 0; ; offset += PAGE) {
    const page = await igdb<Game[]>(
      'games',
      `fields name,alternative_names.name,genres,aggregated_rating,first_release_date,platforms,websites.url; where platforms = (${c.platform}); sort id asc; limit ${PAGE}; offset ${offset};`,
    )
    games.push(...page)
    if (page.length < PAGE) break
  }

  const genres: string[] = []
  const genreIndex = new Map<string, number>()
  const out: Record<string, [number[], number | null, number | null]> = {}
  for (const g of games) {
    const names = (g.genres ?? []).map((x) => genreName.get(x)).filter((x): x is string => !!x)
    const idx = names.map((n) => {
      if (!genreIndex.has(n)) {
        genreIndex.set(n, genres.length)
        genres.push(n)
      }
      return genreIndex.get(n)!
    })
    const score = g.aggregated_rating
    const entry: [number[], number | null, number | null] = [
      idx,
      score != null ? Math.round(score) / 10 : null,
      g.first_release_date ? new Date(g.first_release_date * 1000).getUTCFullYear() : null,
    ]
    // The main name wins over an alternative name of another game.
    const main = looseKey(g.name)
    if (main && (!out[main] || out[main][1] == null)) out[main] = entry
    for (const alt of g.alternative_names ?? []) {
      const k = looseKey(alt.name)
      if (k && !out[k]) out[k] = entry
    }
  }

  // Press scores and English/Spanish names from Wikidata + Wikipedia.
  const press = await pressFor(c.id)
  let scored = 0
  /** Entries that already got their press score: another game sharing an alias must not overwrite it. */
  const pressed = new Set<(typeof out)[string]>()
  const link = (names: string[], score: number | null) => {
    const keys = [...new Set(names.map(looseKey).filter(Boolean))]
    // names[0] is the English label: the game's own entry; aliases may belong to other games of a series.
    let entry = out[keys[0]] && !pressed.has(out[keys[0]]) ? out[keys[0]] : keys.map((k) => out[k]).find((e) => e && !pressed.has(e))
    if (score != null) {
      entry = entry ? entry : [[], null, null]
      entry[1] = score
      pressed.add(entry)
      scored++
    } else if (!entry) return
    for (const k of keys) if (!out[k] || (out[k][1] == null && !pressed.has(out[k]))) out[k] = entry
  }
  for (const g of press.games) link(g.names, g.score)
  for (const names of press.noScore) link(names, null)

  // Games Wikidata doesn't list for this console but IGDB links to an English Wikipedia article:
  // read those articles too (same platform rules).
  const extra = new Map<string, Game>()
  for (const g of games) {
    const entry = out[looseKey(g.name)]
    if (!entry || entry[1] != null) continue
    const url = g.websites?.map((w) => w.url).find((u) => /^https?:\/\/en\.wikipedia\.org\/wiki\//.test(u))
    if (!url) continue
    const title = decodeURIComponent(url.split('/wiki/')[1]).replace(/_/g, ' ').replace(/#.*$/, '')
    if (!press.articles.has(title)) extra.set(title, g)
  }
  const texts = await wikitexts([...extra.keys()])
  for (const [title, g] of extra) {
    const score = articleScore(texts.get(title) ?? '', c.id, (g.platforms?.length ?? 0) === 1)
    if (score != null) link([g.name, title.replace(/\s*\([^)]*\)\s*$/, '')], score)
  }

  writeFileSync(`public/db/igdb-${c.id}.json`, JSON.stringify({ generated: new Date().toISOString().slice(0, 10), genres, games: out }))
  console.log(`${c.id}: ${games.length} juegos de IGDB, ${scored} notas de prensa de Wikipedia, ${Object.keys(out).length} nombres, ${genres.length} géneros`)
}
