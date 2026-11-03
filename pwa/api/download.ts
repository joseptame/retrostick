/**
 * Vercel Edge Function: streams an archive.org file to the browser.
 * archive.org does not send CORS headers on its file servers, so the PWA can't `fetch` the bytes
 * directly. This proxy is same-origin, and streaming has no 4.5 MB response-body limit (Vercel docs).
 * Called only for the identifiers/files the PWA picked from archive.org's own public API.
 */
export const config = { runtime: 'edge' }

export default async function handler(request: Request): Promise<Response> {
  const url = new URL(request.url)
  const id = url.searchParams.get('id')
  const file = url.searchParams.get('file')
  if (!id || !file || id.length > 200 || file.length > 400) {
    return new Response('Parámetros inválidos', { status: 400 })
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
