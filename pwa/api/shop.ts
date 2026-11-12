/**
 * RetroShop catalog for the stick (Retro Ajustes → RetroShop): the games of a console's archive.org
 * collection, as Buscar shows them (no demos, one release per title, discs joined), with the No-Intro name to
 * save each one under and its IGDB genres, press rating and year.
 *
 *   GET /api/shop?console=snes[&collection=<archive.org id>]
 *
 * {
 *   console, collection, title, restricted, archive,   // archive: the .zip holding the ROMs, if any
 *   genres: string[],
 *   games: [{ t: title, f: file, s: size, n: name to save as (no extension), p?: per-game archive,
 *             d?: [{ f, s }] discs, r?: rating 0..10, g?: genre indexes, y?: year,
 *             c?: box art URL (resized WebP, chosen like the webapp's covers) }]
 * }
 *
 * Only reachable with the stick's pass (x-stick-token, see api/pair.ts and middleware.ts). Cached per console for a few hours.
 */
import { configureArchive, groupDiscs, listItemFiles, type ArchiveEntry } from '../src/lib/archive.js'
import { consoleForFile, CONSOLES, isCartridge, isRomsetConsole, type ConsoleId } from '../src/lib/consoles.js'
import { configureData, fetchData } from '../src/lib/data.js'
import { loadLookup } from '../src/lib/igdbdb.js'
import { baseName, bestVariants, coverUrl, displayTitle, isDemo, looseKey, safeThumbName } from '../src/lib/names.js'
import { DEFAULT_COLLECTIONS } from '../src/lib/recents.js'
import { genreEs } from '../src/lib/genres.js'
import { coverFor, loadDb, romsetName, romsetTitle } from '../src/lib/romdb.js'

// Node.js, not edge: Vercel's edge adds a "Forwarded" header to outgoing requests and archive.org answers
// those with 400. (Imports carry .js: Node resolves ES modules by exact file name.)
export const config = { runtime: 'nodejs', maxDuration: 60 }

const CACHE_MS = 6 * 3600_000

interface ShopGame {
  t: string
  f: string
  s: number
  n: string
  p?: string
  d?: { f: string; s: number }[]
  r?: number
  g?: number[]
  y?: number
  /** Players on one console when two or more (scripts/build-players.ts): RetroShop's «Para dos» filter */
  m?: number
  c?: string
}

const cache = new Map<string, { at: number; body: Promise<string> }>()

/** No-Intro name per loose title: European release first, then the shortest name (as guessByName). */
async function namesIndex(id: ConsoleId): Promise<{ exact: Set<string>; byKey: Map<string, string> }> {
  const db = await loadDb(id)
  const euro = (n: string) => (/\((?:[^)]*[ ,])?(Europe|Spain)[,)]/.test(n) ? 0 : 1)
  const index = new Map<string, string>()
  for (const n of db.names) {
    if (/\((Beta|Proto|Demo|Sample)/i.test(n)) continue
    const key = looseKey(n)
    const cur = index.get(key)
    if (!cur || euro(n) - euro(cur) < 0 || (euro(n) === euro(cur) && n.length < cur.length)) index.set(key, n)
  }
  return { exact: new Set(db.names), byKey: index }
}

async function build(id: ConsoleId, collection: string): Promise<string> {
  const [{ entries, archive, title, restricted }, lookup, names] = await Promise.all([
    listItemFiles(collection, id),
    loadLookup(id),
    isCartridge(id) && !isRomsetConsole(id) ? namesIndex(id) : Promise.resolve(null),
  ])
  const romsets = isRomsetConsole(id)
  const list: ArchiveEntry[] = romsets
    ? entries
    : bestVariants(groupDiscs(entries).filter((e) => consoleForFile(e.name) === id && !isDemo(e.name)))
  // Neo Geo: each romset's long name ("mslug" → "Metal Slug - Super Vehicle-001"), for the title, ficha and box art
  const allNames = romsets ? await Promise.all(list.map((e) => romsetName(id, baseName(e.name)))) : []
  // Only the romsets FinalBurn Neo knows as Neo Geo games (others wouldn't start)
  if (romsets) {
    const known = list.map((e, i) => [e, allNames[i]] as const).filter(([, n]) => n != null)
    list.splice(0, list.length, ...known.map(([e]) => e))
  }
  const longNames = romsets ? allNames.filter((n) => n != null) : []
  const genres: string[] = []
  const genreIndex = (g: string) => {
    let i = genres.indexOf(g)
    if (i < 0) i = genres.push(g) - 1
    return i
  }
  const games: ShopGame[] = list.map((e, i) => {
    const base = baseName(e.name)
    const long = longNames[i] ?? null
    // Neo Geo: the file keeps its short name (n), the title comes from the long one ("A _ B (NGM-…)" → "A")
    const title = long ? romsetTitle(long) : displayTitle(e.name)
    const info = long ? (lookup(long) ?? lookup(title) ?? lookup(title.split(' - ')[0])) : lookup(base)
    const game: ShopGame = {
      t: title,
      f: e.name,
      s: e.size,
      // Already a No-Intro name (a "Rev 1"…): keep it; otherwise the best release of the same title.
      n: !names || names.exact.has(base) ? base : (names.byKey.get(looseKey(base)) ?? base),
    }
    if (e.pack) game.p = e.pack
    if (e.discs) game.d = e.discs.map((d) => ({ f: d.name, s: d.size }))
    if (info?.rating != null) game.r = info.rating
    // Spanish names, as in the webapp ("Racing" -> "Carreras")
    if (info?.genres.length) game.g = info.genres.map((g) => genreIndex(genreEs(g)))
    if (info?.year) game.y = info.year
    return game
  })
  // Box art picked here (same choice as the webapp), so the stick needn't look up libretro's index itself.
  const covers = await Promise.all(list.map((e, i) => coverFor(id, longNames[i] ?? e.name).catch(() => null)))
  // No box art (pirate, homebrew…): libretro often still has the title screen under the exact No-Intro name.
  const thumbs = CONSOLES.find((c) => c.id === id)!.thumbs
  covers.forEach((c, i) => {
    games[i].c = c ?? coverUrl(thumbs, safeThumbName(longNames[i] ?? baseName(list[i].name)), 'Named_Titles')
  })
  const players = await fetchData<{ games: Record<string, number> }>(`db/players-${id}.json`).catch(() => null)
  if (players) for (const g of games) {
    const p = players.games[looseKey(g.t)] ?? players.games[looseKey(displayTitle(g.n))]
    if (p) g.m = p
  }
  games.sort((a, b) => (b.r ?? -1) - (a.r ?? -1) || a.t.localeCompare(b.t, 'es'))
  return JSON.stringify({ console: id, collection, title, restricted, archive, genres, games })
}

export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const id = url.searchParams.get('console') as ConsoleId | null
  if (!id || !CONSOLES.some((c) => c.id === id)) return new Response('Consola desconocida', { status: 400 })
  const collection = url.searchParams.get('collection') || DEFAULT_COLLECTIONS[id]
  if (!collection || collection.length > 200) return new Response('Sin colección', { status: 404 })

  // The data files sit behind the login too: fetch them from this deployment with the stick's key.
  configureData(url.origin, { 'x-stick-token': request.headers.get('x-stick-token') ?? '' })
  // Say who is asking (archive.org asks API clients to identify themselves).
  configureArchive({ 'User-Agent': 'RetroStick/1.0 (+https://retrostick.vercel.app)', Accept: 'application/json, text/html' })
  const key = `${id}/${collection}`
  let hit = cache.get(key)
  if (!hit || Date.now() - hit.at > CACHE_MS) {
    hit = { at: Date.now(), body: build(id, collection) }
    hit.body.catch(() => cache.delete(key))
    cache.set(key, hit)
  }
  try {
    return new Response(await hit.body, {
      headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'private, max-age=3600' },
    })
  } catch (e) {
    return new Response(e instanceof Error ? e.message : String(e), { status: 502 })
  }
}
