/**
 * Copies a (big) top-level archive.org file into Google Drive without downloading it to the phone:
 * the PWA opens a Drive resumable session and api/transfer.ts fills it chunk by chunk from archive.org.
 */
import { createUploadSession, type DriveFile } from './drive'

/** 8 MiB: a multiple of 256 KiB (Drive's rule for every chunk but the last); fits the time limits even when archive.org is slow. */
const CHUNK = 8 * 1024 * 1024
const RETRIES = 3
/** Per chunk: the server gives up on archive.org after 150 s; this covers that plus the Drive upload. */
const CHUNK_TIMEOUT = 240_000

interface TransferAnswer {
  status?: number
  /** "bytes=0-16777215": what Drive has stored so far */
  range?: string | null
  file?: DriveFile
  error?: string
}

export async function transferFromArchive(opts: {
  identifier: string
  /** Path inside the item ("CHD-PSX-EUR/Game (Europe).chd") */
  file: string
  size: number
  /** Name in Drive */
  name: string
  parentId: string
  onProgress: (sent: number, total: number) => void
  signal?: AbortSignal
}): Promise<DriveFile> {
  const { identifier, file, size, name, parentId, onProgress, signal } = opts
  const session = await createUploadSession(name, parentId, size, signal)
  let start = 0
  onProgress(0, size)
  while (start < size) {
    const end = Math.min(start + CHUNK, size)
    const params = new URLSearchParams({ id: identifier, file, session, start: String(start), end: String(end), total: String(size) })
    let answer: TransferAnswer | null = null
    for (let attempt = 1; ; attempt++) {
      try {
        // A stuck chunk must not freeze the upload at 0 %: give up and retry.
        const timeout = AbortSignal.timeout(CHUNK_TIMEOUT)
        const res = await fetch(`/api/transfer?${params}`, { signal: signal ? AbortSignal.any([signal, timeout]) : timeout })
        answer = JSON.parse((await res.text()).trim()) as TransferAnswer
        if (!answer.error) break
        // Drive's 4xx (expired session…) won't fix itself; archive.org hiccups (5xx) may.
        if ((answer.status && answer.status >= 400 && answer.status < 500) || /iniciar sesión/.test(answer.error)) throw new Error(answer.error)
        throw Object.assign(new Error(answer.error), { retry: true })
      } catch (e) {
        const timedOut = e instanceof DOMException && e.name === 'TimeoutError'
        const retryable = (e as { retry?: boolean }).retry || e instanceof TypeError || timedOut
        if (signal?.aborted || attempt >= RETRIES || !retryable) {
          throw timedOut ? new Error('archive.org no responde; inténtalo más tarde') : e
        }
        await new Promise((r) => setTimeout(r, 2000 * attempt))
      }
    }
    if (answer.status === 200 || answer.status === 201) {
      onProgress(size, size)
      return answer.file!
    }
    // 308: continue after the last byte Drive confirms (normally `end`).
    const confirmed = /bytes=0-(\d+)/.exec(answer.range ?? '')
    start = confirmed ? Number(confirmed[1]) + 1 : end
    onProgress(start, size)
  }
  throw new Error('Drive no confirmó el final de la subida')
}
