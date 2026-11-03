/** No-Intro / Redump names, titles, covers and save matching. Same rules as the TV launcher. */

export function baseName(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot <= 0 ? fileName : fileName.slice(0, dot)
}

/** "Legend of Zelda, The - Oracle of Ages (Europe) (En,Fr)" -> "The Legend of Zelda - Oracle of Ages" */
export function displayTitle(name: string): string {
  const title = name.replace(/\s*[([].*$/, '').trim()
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

export function region(name: string): string | null {
  const group = /\(([^)]*)\)/.exec(name)?.[1]
  return group ? REGIONS[group] ?? group : null
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
  const safe = safeThumbName(name)
  const key = titleKey(safe)
  const rank = (n: string) => {
    const euro = EUROPEAN.test(n)
    if (n === safe && euro) return 0
    if (SPANISH.test(n)) return 1
    if (euro) return 2
    if (n === safe) return 3
    return 4
  }
  let best: string | null = null
  for (const n of boxarts) {
    if (titleKey(n) !== key || (n !== safe && UNRELEASED.test(n))) continue
    if (best === null || rank(n) < rank(best) || (rank(n) === rank(best) && n.length < best.length)) best = n
  }
  return best
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
