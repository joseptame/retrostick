import type { ConsoleId } from './consoles.js'

/** archive.org collections opened in Buscar, most recent first (per-device convenience, localStorage). */
export interface RecentCollection {
  id: string
  title: string
  count: number
}

const KEY = 'rs.recentCollections'
const MAX = 6

export function loadRecents(): RecentCollection[] {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(KEY) ?? '[]')
    if (!Array.isArray(v)) return []
    return v
      .filter((x): x is RecentCollection => !!x && typeof x.id === 'string' && x.id.length > 0)
      .map((x) => ({ id: x.id, title: typeof x.title === 'string' ? x.title : '', count: Number(x.count) || 0 }))
      .slice(0, MAX)
  } catch {
    return []
  }
}

/** Puts `r` first (replacing an older entry with the same id) and returns the new list. */
export function saveRecent(r: RecentCollection): RecentCollection[] {
  const list = [r, ...loadRecents().filter((x) => x.id !== r.id)].slice(0, MAX)
  try {
    localStorage.setItem(KEY, JSON.stringify(list))
  } catch {
    /* storage unavailable: the list just isn't remembered */
  }
  return list
}

export function removeRecent(id: string): RecentCollection[] {
  const list = loadRecents().filter((x) => x.id !== id)
  try {
    localStorage.setItem(KEY, JSON.stringify(list))
  } catch {
    /* ignore */
  }
  return list
}

/** archive.org collection each console explores in Buscar, until the user picks another one. */
export const DEFAULT_COLLECTIONS: Partial<Record<ConsoleId, string>> = {
  nes: 'No-Intro_NES',
  snes: 'bahamut-lagoon-japan-espanol',
  gb: 'gameboy_202401', gbc: 'gbc_20240311', gba: 'gba-pal', md: 'nointro.md', sms: 'nointro.ms-mkiii',
  n64: 'recalbox-nointro-n64-1g1r-fr-retroninjasamurai',
  neogeo: 'cylums-neo-geo-rom-collection', psx: 'chd_psx_eur' }
const BY_CONSOLE = 'rs.collectionByConsole'

function byConsole(): Partial<Record<ConsoleId, string>> {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(BY_CONSOLE) ?? '{}')
    return v && typeof v === 'object' ? (v as Partial<Record<ConsoleId, string>>) : {}
  } catch {
    return {}
  }
}

/** The collection to show for a console: the one picked last for it, else the built-in default. */
export function collectionFor(c: ConsoleId): string | null {
  return byConsole()[c] ?? DEFAULT_COLLECTIONS[c] ?? null
}

export function setCollectionFor(c: ConsoleId, id: string) {
  try {
    localStorage.setItem(BY_CONSOLE, JSON.stringify({ ...byConsole(), [c]: id }))
  } catch {
    /* not remembered */
  }
}
