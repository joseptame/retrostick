/** Game identification against the No-Intro / Redump database built by scripts/build-db.ts. */
import { consoleById, type ConsoleId } from './consoles'
import { crc32, sha1 } from './hash'
import { baseName, bestBoxart, coverUrl, looseKey } from './names'

interface Db {
  names: string[]
  sha1: Record<string, number>
  crc: Record<string, number>
  boxarts: string[]
}

const cache = new Map<ConsoleId, Promise<Db>>()

export function loadDb(id: ConsoleId): Promise<Db> {
  let db = cache.get(id)
  if (!db) {
    db = fetch(`${import.meta.env.BASE_URL}db/${id}.json`).then((r) => {
      if (!r.ok) throw new Error(`db ${id}: ${r.status}`)
      return r.json()
    })
    db.catch(() => cache.delete(id))
    cache.set(id, db)
  }
  return db
}

export interface Identification {
  name: string
  /** sha1/crc = exact dump match; name = guessed from the file name */
  by: 'sha1' | 'crc' | 'name'
}

/** README 7.4: SHA-1 first, CRC32 as a fallback, then the file name. */
export async function identify(id: ConsoleId, fileName: string, data: Uint8Array | null): Promise<Identification | null> {
  const db = await loadDb(id)
  if (data) {
    const s = db.sha1[await sha1(data)]
    if (s !== undefined) return { name: db.names[s], by: 'sha1' }
    const c = db.crc[crc32(data)]
    if (c !== undefined) return { name: db.names[c], by: 'crc' }
  }
  const guess = guessByName(db.names, baseName(fileName))
  return guess ? { name: guess, by: 'name' } : null
}

/** Same title ignoring case/accents/punctuation; European, then shortest (fewest tags) name first. */
export function guessByName(names: string[], fileBase: string): string | null {
  const key = looseKey(fileBase)
  if (!key) return null
  const candidates = names.filter((n) => looseKey(n) === key && !/\((Beta|Proto|Demo|Sample)/i.test(n))
  const euro = (n: string) => (/\((?:[^)]*[ ,])?(Europe|Spain)[,)]/.test(n) ? 0 : 1)
  candidates.sort((a, b) => euro(a) - euro(b) || a.length - b.length)
  return candidates[0] ?? null
}

/** Cover URL with the European preference (README 7.5), or null when the repo has none. */
export async function coverFor(id: ConsoleId, name: string): Promise<string | null> {
  const db = await loadDb(id)
  const boxart = bestBoxart(name, db.boxarts)
  return boxart ? coverUrl(consoleById(id).thumbs, boxart) : null
}
