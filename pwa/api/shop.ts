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
 *             c?: box art URL (resized WebP, chosen like the webapp's covers),
 *             i?: archive.org item to download it from instead of the collection (see EXTRAS) }]
 * }
 *
 * &extras=1 (launcher 1.28.0 on): also the console's EXTRAS, which older launchers couldn't download.
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
import { ES_ITEM, ES_TITLES, LC_ITEM, TR_ITEM } from '../src/lib/psx-es.js'

// Node.js, not edge: Vercel's edge adds a "Forwarded" header to outgoing requests and archive.org answers
// those with 400. (Imports carry .js: Node resolves ES modules by exact file name.)
export const config = { runtime: 'nodejs', maxDuration: 60 }

const CACHE_MS = 6 * 3600_000

/**
 * A piece downloaded from its own archive.org item (launcher 1.28.0 on): i item, f file, s size, a name to save it
 * under (else its own), z: a zip whose files (a .cue and its .bin) are all saved.
 */
interface Part {
  i: string
  f: string
  s: number
  a?: string
  z?: 1
}

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
  /** archive.org item holding it, when not the collection's (EXTRAS) */
  i?: string
  /** PlayStation in Spanish: what to download instead of f / d (psx-es.ts) */
  x?: Part[]
  /**
   * Fan versions offered besides it (psx-es.ts): k «voz» voices and texts, «txt» texts only, «mej» the Spanish
   * release with its texts corrected (v: voices redone too); total size, its pieces
   */
  alt?: { k: string; v?: 1; s: number; x: Part[] }[]
}

/**
 * Single games the console's default collection lacks (gba-pal has only European releases), each from its own
 * archive.org item: added one by one when asked for, with the No-Intro name and the size of the ROM inside.
 */
const EXTRAS: Partial<Record<ConsoleId, { item: string; entry: ArchiveEntry }[]>> = {
  gba: [
    {
      item: 'theentiregameboyadvancecollection',
      entry: { name: "Dragon Ball Z - Buu's Fury (USA).gba", size: 8388608, pack: "Dragon Ball Z - Buu's Fury (USA).zip" },
    },
  ],
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

async function build(id: ConsoleId, collection: string, withExtras: boolean): Promise<string> {
  const [{ entries, archive, title, restricted }, lookup, names] = await Promise.all([
    listItemFiles(collection, id),
    loadLookup(id),
    isCartridge(id) && !isRomsetConsole(id) ? namesIndex(id) : Promise.resolve(null),
  ])
  const romsets = isRomsetConsole(id)
  const list: ArchiveEntry[] = romsets
    ? entries
    : bestVariants(groupDiscs(entries).filter((e) => consoleForFile(e.name) === id && !isDemo(e.name)))
  // Extras only on the console's own collection, and only when the collection doesn't have that game already
  const extraItem = new Map<ArchiveEntry, string>()
  if (withExtras && collection === DEFAULT_COLLECTIONS[id]) {
    const have = new Set(list.map((e) => looseKey(baseName(e.name))))
    for (const x of EXTRAS[id] ?? []) {
      if (have.has(looseKey(baseName(x.entry.name)))) continue
      list.push(x.entry)
      extraItem.set(x.entry, x.item)
    }
  }
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
    const item = extraItem.get(e)
    if (item) game.i = item
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
  if (id === 'psx' && withExtras && collection === DEFAULT_COLLECTIONS[id]) {
    await spanishPsx(games, lookup, genreIndex, players?.games ?? {}).catch((e) => console.error('PS1 en español', e))
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
  const withExtras = url.searchParams.get('extras') === '1'
  const key = `${id}/${collection}/${withExtras ? 'x' : ''}`
  let hit = cache.get(key)
  if (!hit || Date.now() - hit.at > CACHE_MS) {
    hit = { at: Date.now(), body: build(id, collection, withExtras) }
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

// ---- PlayStation in Spanish (psx-es.ts, public/db/psx-es.json) ----

const fileName = (p: string) => p.slice(p.lastIndexOf('/') + 1)

interface PsxRelease {
  t: string
  r: string
  /** eur: RetroShop's own collection; lc / tr / zip: an item of their own (zip: .cue + .bin to unzip) */
  src: 'eur' | 'lc' | 'tr' | 'zip'
  d: [string, number][]
}

interface PsxAlt {
  t: string
  k: 'voz' | 'txt' | 'mej'
  v?: 1
  r: string
  n: number
  d: [string, number][]
  z?: 1
}

const SRC_ITEM = { lc: LC_ITEM, tr: TR_ITEM, zip: ES_ITEM } as const

/**
 * The European PlayStation list with the Spanish releases instead of the same games (the Spain-only ones added),
 * as CHD wherever there is one, and the fan versions offered besides each game.
 */
async function spanishPsx(
  games: ShopGame[],
  lookup: (name: string) => { genres: string[]; rating: number | null; year: number | null } | null,
  genreIndex: (g: string) => number,
  players: Record<string, number>,
) {
  const db = await fetchData<{ es: PsxRelease[]; alt: PsxAlt[] }>('db/psx-es.json')
  const byKey = new Map(games.map((g) => [looseKey(g.t), g]))
  const spanish = new Set<ShopGame>()
  const sizeOf = (d: [string, number][]) => d.reduce((n, [, s]) => n + s, 0)

  // 1. Spanish releases. Spain's last: when two map to one European game, Spain's stays.
  const es = [...db.es].sort((a, b) => Number(/\(Spain/.test(a.r)) - Number(/\(Spain/.test(b.r)))
  const covers = await Promise.all(es.map((e) => coverFor('psx', e.r).catch(() => null)))
  es.forEach(({ t, r, src, d }, n) => {
    // Where it comes from: the collection's own CHD (as any other game), or pieces from another item
    const how: Partial<ShopGame> = src === 'eur'
      ? { f: d[0][0], s: sizeOf(d), ...(d.length > 1 ? { d: d.map(([f, s]) => ({ f, s })) } : {}) }
      : { f: d[0][0], s: sizeOf(d), i: SRC_ITEM[src], x: d.map(([f, s]) => ({ i: SRC_ITEM[src], f, s, ...(src === 'zip' ? { z: 1 as const } : {}) })) }
    const euro = byKey.get(looseKey(ES_TITLES[t] ?? t))
    if (euro) {
      // Same game: its IGDB data and players stay, the rest is the Spanish release
      for (const k of ['p', 'd', 'i', 'x'] as const) delete euro[k]
      Object.assign(euro, { t, n: r }, how)
      if (covers[n]) euro.c = covers[n]!
      spanish.add(euro)
      return
    }
    const game = { t, n: r, ...how } as ShopGame
    const info = lookup(r)
    if (info?.rating != null) game.r = info.rating
    if (info?.genres.length) game.g = info.genres.map((g) => genreIndex(genreEs(g)))
    if (info?.year) game.y = info.year
    if (players[looseKey(t)]) game.m = players[looseKey(t)]
    game.c = covers[n] ?? coverUrl(CONSOLES.find((c) => c.id === 'psx')!.thumbs, safeThumbName(r), 'Named_Titles')
    games.push(game)
    byKey.set(looseKey(t), game)
    spanish.add(game)
  })

  // 2. Fan versions: corrected Spanish releases on the Spanish games, translations on the others
  for (const a of db.alt) {
    const g = byKey.get(looseKey(a.t))
    if (!g || (a.k === 'mej') !== spanish.has(g)) continue
    // Saved under the game's own discs' names (a .cue becomes a .chd): the stick sees the same game
    const names = g.d ? g.d.map((x) => fileName(x.f))
      : g.x ? g.x.map((p) => fileName(p.f).replace(/\.zip$/i, '.chd'))
      : [fileName(g.f)]
    if (names.length !== a.n) continue
    // A zip holding every disc: saved under the inner names, the stick renames a single one
    const x: Part[] = a.z
      ? [{ i: TR_ITEM, f: a.d[0][0], s: a.d[0][1], z: 1, ...(a.n === 1 ? { a: names[0] } : {}) }]
      : a.d.map(([f, s], i) => ({ i: TR_ITEM, f, s, a: names[i] }))
    ;(g.alt ??= []).push({ k: a.k, ...(a.v ? { v: 1 as const } : {}), s: sizeOf(a.d), x })
  }
  // Voices and texts first, then texts only
  for (const g of games) g.alt?.sort((a, b) => ['mej', 'voz', 'txt'].indexOf(a.k) - ['mej', 'voz', 'txt'].indexOf(b.k))
}
