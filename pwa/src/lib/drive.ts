/** Typed Drive v3 REST layer (README 7.6). Everything happens inside Retro/ (README 4). */
import { accessToken } from './auth'
import { CONSOLES, type ConsoleId } from './consoles'

const API = 'https://www.googleapis.com/drive/v3'
const UPLOAD = 'https://www.googleapis.com/upload/drive/v3'
const FIELDS = 'id,name,size,mimeType,modifiedTime,md5Checksum,parents'
const FOLDER = 'application/vnd.google-apps.folder'

export interface DriveFile {
  id: string
  name: string
  size?: string
  mimeType: string
  modifiedTime: string
  md5Checksum?: string
  parents?: string[]
}

export interface Revision {
  id: string
  modifiedTime: string
  size?: string
}

export class DriveError extends Error {
  constructor(readonly status: number, message: string) {
    super(message)
  }
}

async function request(url: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${await accessToken()}`)
  const res = await fetch(url, { ...init, headers })
  if (!res.ok && res.status !== 308) {
    const text = await res.text().catch(() => '')
    throw new DriveError(res.status, `${res.status} ${text.slice(0, 200)}`)
  }
  return res
}

const json = async <T>(url: string, init?: RequestInit): Promise<T> => (await request(url, init)).json()

export async function fetchAccountEmail(): Promise<string> {
  return (await json<{ user: { emailAddress: string } }>(`${API}/about?fields=user(emailAddress)`)).user.emailAddress
}

export async function listFolder(folderId: string): Promise<DriveFile[]> {
  const out: DriveFile[] = []
  let page: string | undefined
  do {
    const params = new URLSearchParams({
      q: `'${folderId}' in parents and trashed = false`,
      fields: `nextPageToken,files(${FIELDS})`,
      pageSize: '1000',
      spaces: 'drive',
    })
    if (page) params.set('pageToken', page)
    const res = await json<{ files: DriveFile[]; nextPageToken?: string }>(`${API}/files?${params}`)
    out.push(...res.files)
    page = res.nextPageToken
  } while (page)
  return out
}

async function childFolder(parentId: string, name: string): Promise<string> {
  const existing = (await listFolder(parentId)).find((f) => f.mimeType === FOLDER && f.name === name)
  if (existing) return existing.id
  const created = await json<DriveFile>(`${API}/files?fields=id`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, mimeType: FOLDER, parents: [parentId] }),
  })
  return created.id
}

export interface Folders {
  root: string
  coleccion: Record<ConsoleId, string>
  jugando: Record<ConsoleId, string>
  saves: string
  states: string
  stick: string
  bios: string
}

const FOLDERS_KEY = 'retrostick.folders'

/** Finds or creates the Retro/ tree (same one the stick creates); ids cached in localStorage. */
export async function ensureFolders(): Promise<Folders> {
  try {
    const cached = JSON.parse(localStorage.getItem(FOLDERS_KEY) ?? 'null') as Folders | null
    // A console added since the ids were cached (NES, SNES…) has no folder there yet: rebuild the tree.
    const complete = cached && CONSOLES.every((c) => cached.coleccion?.[c.id] && cached.jugando?.[c.id])
    if (cached && complete) {
      const root = await json<{ trashed?: boolean }>(`${API}/files/${cached.root}?fields=id,trashed`)
      if (!root.trashed) return cached
    }
  } catch (e) {
    if (!(e instanceof DriveError)) throw e
  }
  const root = await childFolder('root', 'Retro')
  const [coleccionDir, jugandoDir, partidas, config] = await Promise.all(
    ['Coleccion', 'JugandoAhora', 'Partidas', 'Config'].map((n) => childFolder(root, n)),
  )
  const perConsole = async (parent: string) =>
    Object.fromEntries(await Promise.all(CONSOLES.map(async (c) => [c.id, await childFolder(parent, c.id)]))) as Record<ConsoleId, string>
  const folders: Folders = {
    root,
    coleccion: await perConsole(coleccionDir),
    jugando: await perConsole(jugandoDir),
    saves: await childFolder(partidas, 'saves'),
    states: await childFolder(partidas, 'states'),
    stick: await childFolder(partidas, '.stick'),
    bios: await childFolder(config, 'bios'),
  }
  localStorage.setItem(FOLDERS_KEY, JSON.stringify(folders))
  return folders
}

export function forgetFolders() {
  localStorage.removeItem(FOLDERS_KEY)
}

/** "Put on the stick" / "Remove from the stick" = change the parent (README 3). */
export async function moveFile(id: string, addParent: string, removeParent: string): Promise<void> {
  await request(`${API}/files/${id}?addParents=${addParent}&removeParents=${removeParent}&fields=id`, { method: 'PATCH' })
}

/** Moved to the Drive bin (recoverable for 30 days), never deleted outright. */
export async function trashFile(id: string): Promise<void> {
  await request(`${API}/files/${id}?fields=id`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ trashed: true }),
  })
}

export async function download(id: string): Promise<Blob> {
  return (await request(`${API}/files/${id}?alt=media`)).blob()
}

export async function readJson<T>(id: string): Promise<T> {
  return (await request(`${API}/files/${id}?alt=media`)).json()
}

export async function listRevisions(id: string): Promise<Revision[]> {
  const res = await json<{ revisions?: Revision[] }>(`${API}/files/${id}/revisions?fields=revisions(id,modifiedTime,size)&pageSize=200`)
  return (res.revisions ?? []).sort((a, b) => b.modifiedTime.localeCompare(a.modifiedTime))
}

/** Uploads an old revision as the current content (the stick then downloads it as the newest copy). */
export async function restoreRevision(fileId: string, revisionId: string): Promise<void> {
  const blob = await (await request(`${API}/files/${fileId}/revisions/${revisionId}?alt=media`)).blob()
  await request(`${UPLOAD}/files/${fileId}?uploadType=media&fields=id`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/octet-stream' },
    body: blob,
  })
}

/**
 * Upload through a resumable session (README 7.4).
 * Drive's CORS exposes `Location` but not `Range` (checked 2026-11-03), so a browser cannot learn how many
 * bytes arrived and resume a chunked upload reliably. The whole file goes in one PUT (XHR, for progress;
 * the browser streams it from disk) and a dropped connection retries with a new session, up to 3 times.
 * An unfinished session never creates a file in Drive.
 */
export async function uploadResumable(
  blob: Blob,
  name: string,
  parentId: string,
  onProgress: (sent: number, total: number) => void,
  signal?: AbortSignal,
): Promise<DriveFile> {
  for (let attempt = 1; ; attempt++) {
    try {
      return await uploadOnce(blob, name, parentId, onProgress, signal)
    } catch (e) {
      const permanent = e instanceof DriveError && e.status >= 400 && e.status < 500
      if (signal?.aborted || permanent || attempt >= 3) throw e
      await new Promise((r) => setTimeout(r, 2000 * attempt))
    }
  }
}

/**
 * Opens a Drive resumable upload and returns its session URL. The URL itself authorizes the upload, so
 * the server (api/transfer.ts) can fill it straight from archive.org.
 */
export async function createUploadSession(name: string, parentId: string, size: number, signal?: AbortSignal): Promise<string> {
  const init = await request(`${UPLOAD}/files?uploadType=resumable&fields=${FIELDS}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=UTF-8',
      'X-Upload-Content-Type': 'application/octet-stream',
      'X-Upload-Content-Length': String(size),
    },
    body: JSON.stringify({ name, parents: [parentId] }),
    signal,
  })
  const session = init.headers.get('Location')
  if (!session) throw new Error('Drive no devolvió la sesión de subida')
  return session
}

async function uploadOnce(
  blob: Blob,
  name: string,
  parentId: string,
  onProgress: (sent: number, total: number) => void,
  signal?: AbortSignal,
): Promise<DriveFile> {
  const session = await createUploadSession(name, parentId, blob.size, signal)

  onProgress(0, blob.size)
  return new Promise<DriveFile>((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', session)
    xhr.upload.onprogress = (e) => onProgress(e.loaded, blob.size)
    xhr.onload = () => {
      if (xhr.status === 200 || xhr.status === 201) {
        onProgress(blob.size, blob.size)
        resolve(JSON.parse(xhr.responseText))
      } else {
        reject(new DriveError(xhr.status, `upload ${xhr.status}`))
      }
    }
    xhr.onerror = () => reject(new DriveError(0, 'Conexión interrumpida'))
    signal?.addEventListener('abort', () => xhr.abort())
    xhr.send(blob)
  })
}
