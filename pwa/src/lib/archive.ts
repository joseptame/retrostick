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
}

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

/** The archive (.zip/.7z/.rar) in an item, if any; a ROM set is usually a single big archive. */
export function archiveFileOf(files: ArchiveFile[]): ArchiveFile | null {
  return files.find((f) => /\.(zip|7z|rar)$/i.test(f.name)) ?? null
}

/** Parses the HTML listing of `view_archive.php` into name/size pairs. */
export function parseArchiveListing(html: string): ArchiveEntry[] {
  const out: ArchiveEntry[] = []
  const re = /<tr><td><a\b[^>]*>([^<]*)<\/a>.*?id="size">(\d+)/g
  let m: RegExpExecArray | null
  while ((m = re.exec(html))) {
    const name = decodeEntities(m[1])
    if (name) out.push({ name, size: Number(m[2]) })
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

/** Lists the files of an item: inside its archive if it has one, otherwise its top-level files. */
export async function listItemFiles(
  identifier: string,
): Promise<{ entries: ArchiveEntry[]; archive: string | null; title: string }> {
  const meta = await fetchMetadata(identifier)
  const archive = archiveFileOf(meta.files)
  if (archive) {
    const res = await fetch(archiveListingUrl(identifier, archive.name))
    if (!res.ok) throw new Error(`archive.org ${res.status}`)
    return { entries: parseArchiveListing(await res.text()), archive: archive.name, title: meta.title }
  }
  return { entries: meta.files.map((f) => ({ name: f.name, size: Number(f.size ?? 0) })), archive: null, title: meta.title }
}
