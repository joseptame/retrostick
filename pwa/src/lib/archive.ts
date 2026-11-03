/**
 * archive.org public JSON API. The search and metadata endpoints are CORS-enabled, but the file
 * download (ia*.us.archive.org) is NOT, so a browser can't fetch the bytes directly. The PWA
 * downloads through the same-origin proxy `api/download.ts`; `downloadUrl` is the direct link for
 * the "open in archive.org" fallback.
 */

export interface ArchiveItem {
  identifier: string
  title: string
  mediatype: string
}

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

const SEARCH = 'https://archive.org/advancedsearch.php'
const METADATA = 'https://archive.org/metadata'

export async function searchArchive(query: string, rows = 24): Promise<ArchiveItem[]> {
  const params = new URLSearchParams({ q: query, rows: String(rows), output: 'json' })
  params.append('fl[]', 'identifier')
  params.append('fl[]', 'title')
  params.append('fl[]', 'mediatype')
  const res = await fetch(`${SEARCH}?${params}`)
  if (!res.ok) throw new Error(`archive.org ${res.status}`)
  const json = (await res.json()) as { response?: { docs?: ArchiveItem[] } }
  return json.response?.docs ?? []
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

/** Direct download link. Blocked by CORS for `fetch`, but fine for a native browser download. */
export function downloadUrl(identifier: string, file: string): string {
  return `https://archive.org/download/${encodeURIComponent(identifier)}/${encodeURIComponent(file)}`
}

/** Same-origin proxy path that streams the file from archive.org (api/download.ts). */
export function proxyPath(identifier: string, file: string): string {
  return `/api/download?id=${encodeURIComponent(identifier)}&file=${encodeURIComponent(file)}`
}
