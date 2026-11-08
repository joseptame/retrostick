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
 *             d?: [{ f, s }] discs, r?: rating 0..10, g?: genre indexes, y?: year }]
 * }
 *
 * Only reachable with the stick's pass (x-stick-token, see api/pair.ts and middleware.ts). Cached per console for a few hours.
 */
import { configureArchive, groupDiscs, listItemFiles, type ArchiveEntry } from '../src/lib/archive'
import { consoleForFile, CONSOLES, isCartridge, type ConsoleId } from '../src/lib/consoles'
import { configureData } from '../src/lib/data'
import { loadLookup } from '../src/lib/igdbdb'
import { baseName, bestVariants, displayTitle, isDemo, looseKey } from '../src/lib/names'
import { DEFAULT_COLLECTIONS } from '../src/lib/recents'
import { loadDb } from '../src/lib/romdb'

export const config = { runtime: 'edge' }

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
    isCartridge(id) ? namesIndex(id) : Promise.resolve(null),
  ])
  const list: ArchiveEntry[] = bestVariants(groupDiscs(entries).filter((e) => consoleForFile(e.name) === id && !isDemo(e.name)))
  const genres: string[] = []
  const genreIndex = (g: string) => {
    let i = genres.indexOf(g)
    if (i < 0) i = genres.push(g) - 1
    return i
  }
  const games: ShopGame[] = list.map((e) => {
    const base = baseName(e.name)
    const info = lookup(base)
    const game: ShopGame = {
      t: displayTitle(e.name),
      f: e.name,
      s: e.size,
      // Already a No-Intro name (a "Rev 1"…): keep it; otherwise the best release of the same title.
      n: !names || names.exact.has(base) ? base : (names.byKey.get(looseKey(base)) ?? base),
    }
    if (e.pack) game.p = e.pack
    if (e.discs) game.d = e.discs.map((d) => ({ f: d.name, s: d.size }))
    if (info?.rating != null) game.r = info.rating
    if (info?.genres.length) game.g = info.genres.map(genreIndex)
    if (info?.year) game.y = info.year
    return game
  })
  games.sort((a, b) => (b.r ?? -1) - (a.r ?? -1) || a.t.localeCompare(b.t, 'es'))
  return JSON.stringify({ console: id, collection, title, restricted, archive, genres, games })
}

export default async function handler(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const id = url.searchParams.get('console') as ConsoleId | null
  if (!id || !CONSOLES.some((c) => c.id === id)) return new Response('Consola desconocida', { status: 400 })
  const collection = url.searchParams.get('collection') || DEFAULT_COLLECTIONS[id]
  if (!collection || collection.length > 200) return new Response('Sin colección', { status: 404 })

  // The data files sit behind the login too: fetch them from this deployment with the stick's key.
  configureData(url.origin, { 'x-stick-token': request.headers.get('x-stick-token') ?? '' })
  // archive.org answers 400 to anonymous requests from Vercel's edge: say who is asking.
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
