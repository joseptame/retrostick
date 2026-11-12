/**
 * Vercel Function: streams an archive.org file.
 * archive.org does not send CORS headers on its file servers, so the PWA can't `fetch` the bytes
 * directly. This proxy is same-origin, and streaming has no 4.5 MB response-body limit (Vercel docs).
 * Called only for the identifiers/files the PWA picked from archive.org's own public API.
 *
 * With a `Range: bytes=a-b` header (RetroShop on the stick, PS1 discs of restricted items) it returns that
 * piece (206), fetched with the user's archive.org keys (api/_archive.ts).
 */
import { fetchArchiveRange } from './_archive.js'
import { whoIs } from './_users.js'

// Node.js, not edge: Vercel's edge adds a "Forwarded" header to outgoing requests and archive.org answers
// those with 400 (same as api/shop.ts).
export const config = { runtime: 'nodejs', maxDuration: 300 }

/** Biggest piece served per request (the stick asks for 8 MiB). */
const MAX_RANGE = 32 * 1024 * 1024
const RANGE_TIMEOUT = 150_000

export async function GET(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const id = url.searchParams.get('id')
  const file = url.searchParams.get('file')
  if (!id || !file || id.length > 200 || file.length > 400) {
    return new Response('Parámetros inválidos', { status: 400 })
  }

  const range = /^bytes=(\d+)-(\d+)$/.exec(request.headers.get('range') ?? '')
  if (range) {
    // Restricted items go out with the admin's archive.org keys: only for the admin and their stick
    const who = await whoIs(request)
    if (!who?.admin) return new Response('Esta colección solo está disponible para el administrador', { status: 403 })
    const start = Number(range[1])
    const end = Number(range[2]) + 1
    if (!(end > start && end - start <= MAX_RANGE)) return new Response('Rango inválido', { status: 416 })
    const upstream = await fetchArchiveRange(id, file, start, end, AbortSignal.timeout(RANGE_TIMEOUT))
    if (upstream.status !== 206 || !upstream.body) {
      return new Response(`archive.org devolvió ${upstream.status}`, { status: upstream.status === 416 ? 416 : 502 })
    }
    const headers = new Headers({ 'Content-Type': 'application/octet-stream', 'Cache-Control': 'no-store' })
    for (const h of ['content-length', 'content-range']) {
      const v = upstream.headers.get(h)
      if (v) headers.set(h, v)
    }
    return new Response(upstream.body, { status: 206, headers })
  }

  const target = `https://archive.org/download/${encodeURIComponent(id)}/${encodeURIComponent(file)}`
  const upstream = await fetch(target, { redirect: 'follow' })
  if (!upstream.ok || !upstream.body) {
    return new Response(`archive.org devolvió ${upstream.status}`, { status: 502 })
  }

  const headers = new Headers({
    'Content-Type': upstream.headers.get('content-type') ?? 'application/octet-stream',
    'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(file)}`,
    'Cache-Control': 'no-store',
  })
  const length = upstream.headers.get('content-length')
  if (length) headers.set('Content-Length', length)
  return new Response(upstream.body, { status: 200, headers })
}
