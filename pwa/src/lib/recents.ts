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
