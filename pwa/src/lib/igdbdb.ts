/**
 * Genres, rating and year from IGDB, prebuilt per console by `npm run build-igdb` into
 * public/db/igdb-<console>.json, so Buscar can filter and sort without API calls.
 */
import type { ConsoleId } from './consoles.js'
import { fetchData } from './data.js'
import { looseKey } from './names.js'
import { officialName } from './romdb.js'

export interface IgdbDb {
  generated: string
  genres: string[]
  /** looseKey → [genre indexes, rating 0..10 | null, year | null] */
  games: Record<string, [number[], number | null, number | null]>
}

export interface Brief {
  genres: string[]
  rating: number | null
  year: number | null
}

/** Shortest key allowed for a partial (suffix) match, so short names don't match by accident. */
const MIN_PARTIAL = 10

/**
 * Lookup from a game's file or display name to its IGDB data. Tries the exact loose key, then without a
 * trailing "version" ("Pokemon - Emerald Version" → "Pokémon Emerald"), then a key that ends with the
 * other ("007 - Everything or Nothing" ↔ "James Bond 007: Everything or Nothing").
 */
export function makeLookup(db: IgdbDb): (name: string) => Brief | null {
  const keys = Object.keys(db.games)
  const memo = new Map<string, Brief | null>()
  const brief = (k: string): Brief => {
    const [g, rating, year] = db.games[k]
    return { genres: g.map((i) => db.genres[i]).filter(Boolean), rating, year }
  }
  return (name) => {
    const key = looseKey(name)
    if (memo.has(key)) return memo.get(key)!
    let found: string | undefined
    if (db.games[key]) found = key
    else {
      // "Pokemon - Emerald Version" → "Pokémon Emerald"; "Pokemon - Edicion Plata" → "Pokémon Plata"
      const variants = [key.replace(/version$/, ''), key.replace(/edicion(es)?/g, ''), key.replace(/version/g, '')]
      const bare = variants.find((v) => v !== key && db.games[v])
      if (bare) found = bare
      else if (key.length >= MIN_PARTIAL) {
        let best = 0
        for (const k of keys) {
          if (k.length < MIN_PARTIAL) continue
          if ((k.endsWith(key) || key.endsWith(k)) && Math.min(k.length, key.length) > best) {
            best = Math.min(k.length, key.length)
            found = k
          }
        }
      }
    }
    const result = found ? brief(found) : null
    memo.set(key, result)
    return result
  }
}

/**
 * Lookup for a console's games. Game Boy Color sets often include original Game Boy games (Pokémon
 * Red/Blue/Yellow…), so for GBC the Game Boy data fills in what the GBC data lacks.
 */
export async function loadLookup(id: ConsoleId): Promise<(name: string) => Brief | null> {
  const own = makeLookup(await loadIgdbDb(id))
  const gb = id === 'gbc' ? makeLookup(await loadIgdbDb('gb')) : null
  const best = (...found: (Brief | null)[]) => found.find((x) => x?.rating != null) ?? found.find(Boolean) ?? null
  const one = (name: string) => (gb ? best(own(name), gb(name)) : own(name))
  // Files whose name was mapped by hand to a No-Intro name (box art overrides) also try that name.
  return (name) => {
    const r = one(name)
    if (r?.rating != null) return r
    const alias = officialName(name, id)
    return alias ? best(r, one(alias)) : r
  }
}

const cache = new Map<ConsoleId, Promise<IgdbDb>>()

/** Loads (once) the prebuilt IGDB data of a console. */
export function loadIgdbDb(id: ConsoleId): Promise<IgdbDb> {
  let p = cache.get(id)
  if (!p) {
    p = fetchData<IgdbDb>(`db/igdb-${id}.json`)
    p.catch(() => cache.delete(id))
    cache.set(id, p)
  }
  return p
}

export { genreEs } from './genres.js'
