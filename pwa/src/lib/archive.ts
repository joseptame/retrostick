/**
 * archive.org public API. Metadata and the archive "view" endpoints are CORS-enabled, so the PWA can
 * browse a ROM set and download single files from inside a .zip/.7z without a proxy. Top-level files
 * (not inside an archive) are NOT CORS-enabled: those go through `proxyPath` (`api/download.ts`).
 */

export interface ArchiveFile {
  name: string
  size?: string
  format?: string
}

export interface ArchiveMetadata {
  identifier: string
  title: string
  files: ArchiveFile[]
}

export interface ArchiveEntry {
  name: string
  size: number
  /**
   * Sets with one archive per game ("ActRaiser (Europe).zip"): that archive. `name` is then the ROM name
   * it is expected to hold ("ActRaiser (Europe).sfc"), the real one is read from the archive on upload.
   */
  pack?: string
  /** A multi-disc game: its discs in order (`name`/`size` are the first disc's / the total). */
  discs?: ArchiveEntry[]
}

const DISC = /\s*\(Disc (\d+)\)/i

/**
 * Joins the discs of a game ("Riven (Europe) (Disc 1).chd", "(Disc 2)"…) into one entry, so Buscar shows
 * one card and uploads every disc plus a .m3u. Single files pass through unchanged.
 */
export function groupDiscs(entries: ArchiveEntry[]): ArchiveEntry[] {
  const groups = new Map<string, ArchiveEntry[]>()
  const out: (ArchiveEntry | string)[] = []
  for (const e of entries) {
    if (!DISC.test(e.name)) {
      out.push(e)
      continue
    }
    const key = e.name.replace(DISC, '')
    if (!groups.has(key)) {
      groups.set(key, [])
      out.push(key)
    }
    groups.get(key)!.push(e)
  }
  return out.map((x) => {
    if (typeof x !== 'string') return x
    const discs = groups.get(x)!.sort((a, b) => Number(DISC.exec(a.name)![1]) - Number(DISC.exec(b.name)![1]))
    if (discs.length === 1) return discs[0]
    return { name: discs[0].name, size: discs.reduce((n, d) => n + d.size, 0), discs }
  })
}

/** "Riven (Europe) (Disc 1).chd" → "Riven (Europe).m3u": the playlist that ties a game's discs together. */
export function m3uName(discName: string): string {
  return discName.replace(/^.*\//, '').replace(DISC, '').replace(/\.[^.]+$/, '') + '.m3u'
}

import { consoleForFile, ROM_EXTENSION, type ConsoleId } from './consoles'

const METADATA = 'https://archive.org/metadata'
const DOWNLOAD = 'https://archive.org/download'

/** Encodes each path segment but keeps "/" as a separator (archive.org uses it to select a file inside an archive). */
function encodePath(path: string): string {
  return path.split('/').map(encodeURIComponent).join('/')
}

export async function fetchMetadata(identifier: string): Promise<ArchiveMetadata> {
  const res = await fetch(`${METADATA}/${encodeURIComponent(identifier)}`)
  if (!res.ok) throw new Error(`archive.org ${res.status}`)
  const json = (await res.json()) as { metadata?: { title?: string; identifier?: string }; files?: ArchiveFile[] }
  return {
    identifier: json.metadata?.identifier ?? identifier,
    title: json.metadata?.title ?? identifier,
    files: json.files ?? [],
  }
}

/** Direct link to a top-level file (fine for a native browser download, blocked by CORS for `fetch`). */
export function downloadUrl(identifier: string, file: string): string {
  return `${DOWNLOAD}/${encodeURIComponent(identifier)}/${encodePath(file)}`
}

/** URL that redirects to archive.org's HTML listing of the files inside an archive (CORS-enabled). */
export function archiveListingUrl(identifier: string, archiveName: string): string {
  return `${DOWNLOAD}/${encodeURIComponent(identifier)}/${encodePath(archiveName)}/`
}

/** URL that extracts and serves a single file from inside an archive (CORS-enabled). */
export function archiveFileUrl(identifier: string, archiveName: string, innerName: string): string {
  return `${DOWNLOAD}/${encodeURIComponent(identifier)}/${encodePath(archiveName)}/${encodePath(innerName)}`
}

/** Same-origin proxy path for top-level files, which archive.org doesn't CORS-enable (api/download.ts). */
export function proxyPath(identifier: string, file: string): string {
  return `/api/download?id=${encodeURIComponent(identifier)}&file=${encodeURIComponent(file)}`
}

/** Archives of box art, screenshots or manuals that sometimes sit next to the ROM set. */
const NOT_ROMS = /\b(imgs?|images?|covers?|box ?arts?|arts?|screens(hots)?|snaps?|manuals?|scans?|thumbnails?)\b/i

/** The archive (.zip/.7z/.rar) holding the ROMs, if any: not an image pack, and the biggest one. */
export function archiveFileOf(files: ArchiveFile[]): ArchiveFile | null {
  const archives = files.filter((f) => /\.(zip|7z|rar)$/i.test(f.name))
  const roms = archives.filter((f) => !NOT_ROMS.test(f.name))
  const pool = roms.length ? roms : archives
  return pool.sort((a, b) => Number(b.size ?? 0) - Number(a.size ?? 0))[0] ?? null
}

/** macOS leftovers in archives made on a Mac: "__MACOSX/…" and "._Foo.gb" resource forks (not ROMs). */
export function isJunk(path: string): boolean {
  return /(^|\/)__MACOSX\//.test(path) || /(^|\/)\._/.test(path) || /(^|\/)\.DS_Store$/.test(path)
}

/** The ROM inside a per-game archive (`entry.pack`): read from its listing, the biggest ROM file. */
export async function packedRom(identifier: string, pack: string): Promise<ArchiveEntry> {
  const res = await fetch(archiveListingUrl(identifier, pack))
  if (!res.ok) throw new Error(`archive.org ${res.status}`)
  const rom = parseArchiveListing(await res.text())
    .filter((e) => consoleForFile(e.name))
    .sort((a, b) => b.size - a.size)[0]
  if (!rom) throw new Error(`${pack} no contiene ninguna ROM`)
  return rom
}

/** Parses the HTML listing of `view_archive.php` into name/size pairs. */
export function parseArchiveListing(html: string): ArchiveEntry[] {
  const out: ArchiveEntry[] = []
  const re = /<tr><td><a\b[^>]*>([^<]*)<\/a>.*?id="size">(\d+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(html))) {
    const name = decodeEntities(m[1])
    if (name && !isJunk(name)) out.push({ name, size: Number(m[2]) })
  }
  return out
}

function decodeEntities(s: string): string {
  return s
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#0*39;/g, "'")
    .replace(/&apos;/g, "'")
}

/** Fewest archives that make an item a "one archive per game" set (No-Intro_NES…), not one big ROM pack. */
const MIN_PACKS = 5

/**
 * Lists the files of an item: inside its archive if it has one, otherwise its top-level files.
 * `console` names the ROMs of a set with one archive per game (whose names carry no ROM extension).
 */
export async function listItemFiles(
  identifier: string,
  console?: ConsoleId,
): Promise<{ entries: ArchiveEntry[]; archive: string | null; title: string }> {
  const meta = await fetchMetadata(identifier)
  const ext = console && ROM_EXTENSION[console]
  const packs = meta.files.filter((f) => /\.(zip|7z)$/i.test(f.name) && !NOT_ROMS.test(f.name) && !isJunk(f.name))
  const loose = meta.files.filter((f) => consoleForFile(f.name) && !isJunk(f.name))
  if (ext && packs.length >= MIN_PACKS && packs.length > loose.length) {
    const entries: ArchiveEntry[] = [
      ...loose.map((f) => ({ name: f.name, size: Number(f.size ?? 0) })),
      ...packs.map((f) => ({ name: f.name.replace(/\.[^./]+$/, '.' + ext), size: Number(f.size ?? 0), pack: f.name })),
    ]
    return { entries, archive: null, title: meta.title }
  }
  // Loose ROMs win over an archive: a PS1 set keeps its .chd files at the top and may carry an unrelated
  // .zip (subchannel data…).
  const looseRoms = meta.files.some((f) => consoleForFile(f.name) && !isJunk(f.name))
  const archive = looseRoms ? null : archiveFileOf(meta.files)
  if (archive) {
    const res = await fetch(archiveListingUrl(identifier, archive.name))
    if (!res.ok) throw new Error(`archive.org ${res.status}`)
    return { entries: parseArchiveListing(await res.text()), archive: archive.name, title: meta.title }
  }
  const entries = meta.files.filter((f) => !isJunk(f.name)).map((f) => ({ name: f.name, size: Number(f.size ?? 0) }))
  return { entries, archive: null, title: meta.title }
}
