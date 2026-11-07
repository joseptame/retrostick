/** Uploads in progress and recent ones, shown in Subir ("Subidas") and on the tab bar. */
import type { ConsoleId } from './consoles'

export type UploadStatus = 'downloading' | 'uploading' | 'done' | 'error'

export interface UploadItem {
  id: string
  /** Identifies what is uploaded (archive.org file path, local file id…): a new upload replaces an old row. */
  key: string
  title: string
  console: ConsoleId
  /** Name used to find the box art */
  coverName: string
  total: number
  sent: number
  status: UploadStatus
  error?: string
  /** bytes/s, smoothed */
  speed: number
  toStick: boolean
  /** "3 discos", "renombrado a …" */
  detail?: string
  startedAt: number
  finishedAt?: number
  /** Starts the same upload again (failed rows) */
  retry?: () => void
  /** internal: last progress sample */
  lastAt: number
  lastSent: number
}

export interface UploadsApi {
  start(u: Pick<UploadItem, 'key' | 'title' | 'console' | 'coverName' | 'total' | 'toStick'> & Partial<Pick<UploadItem, 'status' | 'detail' | 'retry'>>): string
  /** `total` corrects the size once known (a ROM taken out of its .zip) */
  progress(id: string, sent: number, status?: UploadStatus, total?: number): void
  finish(id: string, error?: string): void
  /** Removes finished and failed rows */
  clear(): void
}

let nextId = 0
export const newUploadId = () => `u${Date.now().toString(36)}${nextId++}`

export function isActive(u: UploadItem) {
  return u.status === 'downloading' || u.status === 'uploading'
}

/** New progress sample: updates the smoothed speed every half second or so. */
export function withProgress(u: UploadItem, sent: number, status: UploadStatus | undefined, now: number): UploadItem {
  const next = { ...u, sent, status: status ?? u.status }
  const dt = now - u.lastAt
  if (dt >= 500 && sent >= u.lastSent) {
    const instant = ((sent - u.lastSent) / dt) * 1000
    next.speed = u.speed ? u.speed * 0.6 + instant * 0.4 : instant
    next.lastAt = now
    next.lastSent = sent
  }
  return next
}

/** "quedan ~2 min" from the smoothed speed; null while unknown. */
export function eta(u: UploadItem): string | null {
  if (!u.speed || u.sent >= u.total) return null
  const s = (u.total - u.sent) / u.speed
  if (s < 60) return 'quedan unos segundos'
  if (s < 3600) return `quedan ~${Math.round(s / 60)} min`
  return `quedan ~${(s / 3600).toFixed(1)} h`
}
