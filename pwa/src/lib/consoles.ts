export type ConsoleId = 'nes' | 'snes' | 'gb' | 'gbc' | 'gba' | 'psx'

export interface ConsoleInfo {
  id: ConsoleId
  name: string
  /** Filter chip label */
  short: string
  /** libretro-thumbnails repository (README 7.5) */
  thumbs: string
}

export const CONSOLES: ConsoleInfo[] = [
  { id: 'nes', name: 'NES', short: 'NES', thumbs: 'Nintendo_-_Nintendo_Entertainment_System' },
  { id: 'snes', name: 'Super Nintendo', short: 'SNES', thumbs: 'Nintendo_-_Super_Nintendo_Entertainment_System' },
  { id: 'gb', name: 'Game Boy', short: 'GB', thumbs: 'Nintendo_-_Game_Boy' },
  { id: 'gbc', name: 'Game Boy Color', short: 'GBC', thumbs: 'Nintendo_-_Game_Boy_Color' },
  { id: 'gba', name: 'Game Boy Advance', short: 'GBA', thumbs: 'Nintendo_-_Game_Boy_Advance' },
  { id: 'psx', name: 'PlayStation', short: 'PS1', thumbs: 'Sony_-_PlayStation' },
]

export const consoleById = (id: ConsoleId): ConsoleInfo => CONSOLES.find((c) => c.id === id)!

const BY_EXTENSION: Record<string, ConsoleId> = {
  nes: 'nes',
  sfc: 'snes',
  smc: 'snes',
  gb: 'gb',
  gbc: 'gbc',
  gba: 'gba',
  chd: 'psx',
  cue: 'psx',
  bin: 'psx',
  m3u: 'psx',
  pbp: 'psx',
}

export const UPLOAD_ACCEPT = Object.keys(BY_EXTENSION).map((e) => '.' + e).join(',')

export function extension(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase()
}

/** Usual ROM extension of a console (names a ROM listed only by its per-game .zip). */
export const ROM_EXTENSION: Partial<Record<ConsoleId, string>> = { nes: 'nes', snes: 'sfc', gb: 'gb', gbc: 'gbc', gba: 'gba' }

/** README 7.4: console from the extension; .bin/.chd/.cue/.m3u/.pbp are PlayStation. */
export function consoleForFile(fileName: string): ConsoleId | null {
  return BY_EXTENSION[extension(fileName)] ?? null
}

/** Cartridges are identified by hash; discs only by name (hashing a CD in the browser is too slow). */
export const isCartridge = (id: ConsoleId) => id !== 'psx'
