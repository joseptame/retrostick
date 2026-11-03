import { extension } from './consoles'
import { baseName } from './names'

export interface NamedFile {
  name: string
}

export interface Unit<F extends NamedFile> {
  /** The file the player opens (.m3u, .cue, .chd, .gba...) */
  main: F
  /** Files that must move together with it (discs of a .m3u, tracks of a .cue) */
  parts: F[]
}

/**
 * README 7.4: a .m3u moves with its discs and a .cue with its .bin tracks.
 * Matching by name, as Redump sets are named: "Game (Europe).m3u" -> "Game (Europe) (Disc 1).chd",
 * "Game (Europe) (Disc 1).cue" -> "Game (Europe) (Disc 1).bin" / "... (Track 01).bin".
 */
export function groupFiles<F extends NamedFile>(files: F[]): Unit<F>[] {
  const claimed = new Set<F>()
  const units: Unit<F>[] = []
  const take = (owner: F, predicate: (f: F) => boolean) => {
    const parts = files.filter((f) => f !== owner && !claimed.has(f) && predicate(f))
    parts.forEach((p) => claimed.add(p))
    return parts
  }

  for (const m3u of files.filter((f) => extension(f.name) === 'm3u')) {
    const prefix = baseName(m3u.name) + ' (Disc'
    claimed.add(m3u)
    const discs = take(m3u, (f) => baseName(f.name).startsWith(prefix))
    // Tracks of disc .cue files belong to the same unit
    const tracks = discs.filter((d) => extension(d.name) === 'cue')
      .flatMap((cue) => take(cue, (f) => extension(f.name) === 'bin' && baseName(f.name).startsWith(baseName(cue.name))))
    units.push({ main: m3u, parts: [...discs, ...tracks] })
  }
  for (const cue of files.filter((f) => extension(f.name) === 'cue' && !claimed.has(f))) {
    claimed.add(cue)
    units.push({ main: cue, parts: take(cue, (f) => extension(f.name) === 'bin' && baseName(f.name).startsWith(baseName(cue.name))) })
  }
  for (const f of files) {
    if (!claimed.has(f)) units.push({ main: f, parts: [] })
  }
  return units
}
