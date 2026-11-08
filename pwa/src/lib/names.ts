/** No-Intro / Redump names, titles, covers and save matching. Same rules as the TV launcher. */

/** Drops any folder: ROM sets often keep their files in a folder inside the .zip ("GBC/Aladdin (E).gbc"). */
export const fileOnly = (path: string) => path.replace(/^.*[\\/]/, '')

export function baseName(fileName: string): string {
  const file = fileOnly(fileName)
  const dot = file.lastIndexOf('.')
  return dot <= 0 ? file : file.slice(0, dot)
}

/** "Legend of Zelda, The - Oracle of Ages (Europe) (En,Fr)" -> "The Legend of Zelda - Oracle of Ages" */
export function displayTitle(name: string): string {
  const title = fileOnly(name)
    .replace(/\.(gbc?|gba|nes|sfc|smc|chd|cue|bin|m3u|pbp|iso|img|zip|7z)$/i, '')
    .replace(/\s*[([].*$/, '')
    .trim()
  const m = /^(.+?), (The|A|An|El|La|Los|Las|Le|Les|Die|Der|Das)( - .*)?$/.exec(title)
  return m ? `${m[2]} ${m[1]}${m[3] ?? ''}` : title
}

const REGIONS: Record<string, string> = {
  Europe: 'Europa',
  USA: 'EE. UU.',
  Japan: 'Japón',
  Spain: 'España',
  World: 'Mundo',
  'USA, Europe': 'EE. UU. y Europa',
}

/** GoodTools region letters ("(E)", "(UE)", "(S)") as No-Intro region names. */
const GOODTOOLS: Record<string, string> = {
  E: 'Europe', U: 'USA', J: 'Japan', S: 'Spain', W: 'World', F: 'France', G: 'Germany', I: 'Italy',
  A: 'Australia', K: 'Korea', B: 'Brazil', C: 'China', H: 'Netherlands',
}

/** The region group of a name as No-Intro region names: "(USA, Europe)" or GoodTools "(UE)" → ["USA", "Europe"]. */
function regionsOf(name: string): string[] {
  const group = /\(([^)]*)\)/.exec(fileOnly(name))?.[1]
  if (!group) return []
  const words = group.split(',').map((r) => r.trim())
  // "(USA)" is a No-Intro region, not GoodTools U + S + A: whole region names win.
  if (words.every((w) => w in REGION_CODES)) return words
  if (/^[EUJSWFGIAKBCH]{1,4}$/.test(group)) return [...group].map((c) => GOODTOOLS[c])
  return words
}

export function region(name: string): string | null {
  const list = regionsOf(name)
  if (!list.length) return null
  const joined = list.join(', ')
  return REGIONS[joined] ?? list.map((r) => REGIONS[r] ?? r).join(' y ')
}

const REGION_CODES: Record<string, string> = {
  Europe: 'EU', USA: 'US', Japan: 'JP', Spain: 'ES', World: 'WORLD', Germany: 'DE', France: 'FR',
  Italy: 'IT', Australia: 'AU', Korea: 'KR', Brazil: 'BR', Asia: 'ASIA', Canada: 'CA', 'United Kingdom': 'UK',
  China: 'CN', Netherlands: 'NL',
}

/** Short region label for badges: "(USA, Europe)" → "US · EU"; null when the name has no known region. */
export function regionCode(name: string): string | null {
  const codes = regionsOf(name).map((r) => REGION_CODES[r]).filter(Boolean)
  return codes.length ? codes.join(' · ') : null
}

/** libretro-thumbnails replaces these characters with "_" (README 7.5). */
export const safeThumbName = (name: string) => name.replace(/[&*/:`<>?\\|"]/g, '_')

const EUROPEAN = /\((?:[^)]*[ ,])?(Europe|Spain)[,)]/
const SPANISH = /\((?:[^)]*[ ,])?Spain[,)]/
const UNRELEASED = /\((Beta|Proto|Demo|Sample|Kiosk)/i
const titleKey = (name: string) => name.replace(/\s*\(.*$/, '').toLowerCase()

/**
 * The user prefers European box art whenever it exists:
 * exact European name > Spanish release > other European > exact name > any other region.
 */
export function bestBoxart(name: string, boxarts: string[]): string | null {
  const safe = safeThumbName(fileOnly(name))
  const key = titleKey(safe)
  const rank = (n: string) => {
    const euro = EUROPEAN.test(n)
    if (n === safe && euro) return 0
    if (SPANISH.test(n)) return 1
    if (euro) return 2
    if (n === safe) return 3
    return 4
  }
  const pick = (match: (n: string) => boolean) => {
    let best: string | null = null
    for (const n of boxarts) {
      if (!match(n) || (n !== safe && UNRELEASED.test(n))) continue
      if (best === null || rank(n) < rank(best) || (rank(n) === rank(best) && n.length < best.length)) best = n
    }
    return best
  }
  const exact = pick((n) => titleKey(n) === key)
  if (exact) return exact
  // Other ROM sets (GoodTools…) spell titles differently: "Ghosts 'N Goblins" / "Ghosts'n Goblins",
  // "Disney's Tarzan" / "Tarzan". Compare loose keys, then let one end the other.
  const loose = looseKey(fileOnly(name))
  if (!loose) return null
  const keys = looseKeysOf(boxarts)
  const same = pick((n) => keys.get(n) === loose)
  if (same) return same
  if (loose.length < 6) return null
  return pick((n) => {
    const k = keys.get(n)!
    return k.length >= 6 && (k.endsWith(loose) || loose.endsWith(k))
  })
}

/** looseKey of every box art name, computed once per list. */
const boxartKeys = new WeakMap<string[], Map<string, string>>()
function looseKeysOf(boxarts: string[]): Map<string, string> {
  let m = boxartKeys.get(boxarts)
  if (!m) {
    m = new Map(boxarts.map((n) => [n, looseKey(n)]))
    boxartKeys.set(boxarts, m)
  }
  return m
}

export function coverUrl(thumbsRepo: string, boxartName: string): string {
  return `https://raw.githubusercontent.com/libretro-thumbnails/${thumbsRepo}/master/Named_Boxarts/${encodeURIComponent(boxartName)}.png`
}

export type SaveKind = 'srm' | 'state' | 'auto'

/** <name>.srm, <name>.state, <name>.state1..9, <name>.state.auto (RetroArch, README 7.4). */
export function saveOf(fileName: string): { game: string; kind: SaveKind; slot?: number } | null {
  let m = /^(.*)\.srm$/.exec(fileName)
  if (m) return { game: m[1], kind: 'srm' }
  m = /^(.*)\.state\.auto$/.exec(fileName)
  if (m) return { game: m[1], kind: 'auto' }
  m = /^(.*)\.state(\d*)$/.exec(fileName)
  if (m) return { game: m[1], kind: 'state', slot: m[2] ? Number(m[2]) : 0 }
  return null
}

export function saveLabel(fileName: string): string {
  const s = saveOf(fileName)
  if (!s) return fileName
  if (s.kind === 'srm') return 'Partida del juego'
  if (s.kind === 'auto') return 'Estado guardado (auto)'
  return `Guardado rápido · posición ${s.slot}`
}

/** Loose key to match a dumped file name against database names when there is no hash match. */
export function looseKey(name: string): string {
  return displayTitle(name)
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/^the |, the\b/g, '')
    .replace(/[^a-z0-9]+/g, '')
}

/**
 * Demos, samplers and magazine cover discs: hidden in Buscar (a PS1 set carries hundreds of them, and a
 * game's demo looked like a duplicate of the game).
 */
export function isDemo(name: string): boolean {
  const n = fileOnly(name)
  return (
    /\((Demo|Sample|Kiosk|Promo|Trial)\b[^)]*\)/i.test(n) ||
    /\b(Euro Demo|Demo Disc|Demo CD|Interactive CD Sampler|PlayStation Magazine|PlayStation Power|PlayStation Plus|Essential PlayStation|Interactive Demo)\b/i.test(n)
  )
}

/**
 * How suitable a release is when a set carries several of one game (lower is better): Spanish first
 * (the user's language), then a finished release over a beta/prototype, then the original over
 * re-releases and revisions.
 */
function variantRank(name: string): number {
  const n = fileOnly(name)
  const groups = [...n.matchAll(/\(([^)]*)\)/g)].map((m) => m[1])
  const spanish = regionsOf(n).includes('Spain') || groups.some((g) => /(^|,)\s*Es\s*(,|$)/.test(g))
  let rank = spanish ? 0 : 100
  if (groups.some((g) => /^(Beta|Proto|Prototype|Alpha)\b/i.test(g))) rank += 50
  if (groups.some((g) => /Platinum|Classics?|Greatest Hits|Best|Value|Budget|Collection|Re-?release/i.test(g))) rank += 5
  if (groups.some((g) => /^Rev\b/i.test(g))) rank += 1
  return rank
}

/** One entry per game: the best release of each title (see variantRank), in the original order. */
export function bestVariants<T extends { name: string }>(entries: T[]): T[] {
  const best = new Map<string, T>()
  for (const e of entries) {
    const key = looseKey(e.name)
    const cur = best.get(key)
    if (!cur || variantRank(e.name) < variantRank(cur.name) || (variantRank(e.name) === variantRank(cur.name) && e.name.length < cur.name.length)) {
      best.set(key, e)
    }
  }
  const keep = new Set(best.values())
  return entries.filter((e) => keep.has(e))
}
