/**
 * Vercel Edge Function: copies one chunk of an archive.org file straight into a Google Drive resumable
 * upload, so big PS1 discs (hundreds of MB) never pass through the phone.
 *
 *   GET /api/transfer?id=<item>&file=<path>&session=<Drive session URL>&start=<byte>&end=<byte>&total=<size>
 *
 * The PWA opens the resumable session with its own Drive token and calls this once per chunk
 * (start inclusive, end exclusive, multiple of 256 KiB except the last). The answer is JSON:
 * { status: 308 } while Drive waits for more, { status: 200|201, file } when the file is complete.
 *
 * Restricted ("loggedin") archive.org items need the user's S3 keys: IA_ACCESS_KEY and IA_SECRET_KEY
 * (archive.org/account/s3.php), sent as "Authorization: LOW access:secret" on every hop of the redirect.
 *
 * The response starts streaming right away (a space every few seconds) and ends with the JSON, so a slow
 * chunk is not cut by the edge's "first byte within 25 s" rule.
 */
export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

/** Biggest chunk accepted (the PWA asks for 8 MiB). */
const MAX_CHUNK = 32 * 1024 * 1024
/**
 * archive.org can be slow (~150 KB/s) or leave a connection hanging: give up on a chunk after this
 * (the PWA retries). Below the edge's 300 s streaming limit.
 */
const ARCHIVE_TIMEOUT = 150_000

function bad(message: string, status = 400): Response {
  return new Response(JSON.stringify({ error: message }), { status, headers: { 'content-type': 'application/json; charset=utf-8' } })
}

/** Follows archive.org's redirects by hand: the auth header must go with every hop (fetch drops it cross-origin). */
async function get(url: string, headers: Record<string, string>, signal: AbortSignal): Promise<Response> {
  for (let hop = 0; hop < 5; hop++) {
    const res = await fetch(url, { headers, redirect: 'manual', signal })
    const location = res.headers.get('location')
    if (res.status >= 300 && res.status < 400 && location) {
      url = new URL(location, url).toString()
      continue
    }
    return res
  }
  throw new Error('archive.org: demasiadas redirecciones')
}

/** The item's two storage servers and folder ("ia601801.us.archive.org", "/21/items/foo"), cached per item. */
const mirrors = new Map<string, Promise<string[]>>()
function mirrorsOf(id: string): Promise<string[]> {
  let p = mirrors.get(id)
  if (!p) {
    p = fetch(`https://archive.org/metadata/${encodeURIComponent(id)}`)
      .then((r) => r.json())
      .then((m: { d1?: string; d2?: string; dir?: string; workable_servers?: string[] }) =>
        m.dir ? [...new Set([m.d1, m.d2, ...(m.workable_servers ?? [])])].filter(Boolean).map((h) => `https://${h}${m.dir}`) : [],
      )
      .catch(() => [])
    mirrors.set(id, p)
  }
  return p
}

/**
 * A byte range of an item's file: through archive.org's download redirect, and if that data node fails
 * (5xx), straight from the item's storage servers.
 */
async function fetchArchiveRange(id: string, file: string, start: number, end: number, signal: AbortSignal): Promise<Response> {
  const headers: Record<string, string> = { Range: `bytes=${start}-${end - 1}` }
  const access = process.env.IA_ACCESS_KEY
  const secret = process.env.IA_SECRET_KEY
  if (access && secret) headers.Authorization = `LOW ${access}:${secret}`
  const path = file.split('/').map(encodeURIComponent).join('/')
  let res = await get(`https://archive.org/download/${encodeURIComponent(id)}/${path}`, headers, signal)
  if (res.status < 500) return res
  for (const base of await mirrorsOf(id)) {
    console.log(`[transfer] archive.org ${res.status}: probando ${new URL(base).host}`)
    res = await get(`${base}/${path}`, headers, signal)
    if (res.status < 500) return res
  }
  return res
}

export default async function handler(request: Request): Promise<Response> {
  const p = new URL(request.url).searchParams
  const id = p.get('id') ?? ''
  const file = p.get('file') ?? ''
  const session = p.get('session') ?? ''
  const start = Number(p.get('start'))
  const end = Number(p.get('end'))
  const total = Number(p.get('total'))

  if (!id || !file || id.length > 200 || file.length > 400) return bad('Parámetros inválidos')
  if (!(start >= 0 && end > start && end <= total && end - start <= MAX_CHUNK)) return bad('Rango inválido')
  // Only Google Drive upload sessions: this endpoint must not PUT data anywhere else.
  let target: URL
  try {
    target = new URL(session)
  } catch {
    return bad('Sesión inválida')
  }
  if (target.protocol !== 'https:' || target.hostname !== 'www.googleapis.com' || !target.pathname.startsWith('/upload/drive/v3/files')) {
    return bad('Sesión inválida')
  }

  const encoder = new TextEncoder()
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const keepAlive = setInterval(() => controller.enqueue(encoder.encode(' ')), 5000)
      const finish = (body: unknown) => {
        clearInterval(keepAlive)
        controller.enqueue(encoder.encode(JSON.stringify(body)))
        controller.close()
      }
      const t0 = Date.now()
      const tag = `[transfer] ${file.replace(/^.*\//, '')} ${(start / 1048576).toFixed(0)}-${(end / 1048576).toFixed(0)}/${(total / 1048576).toFixed(0)} MB`
      console.log(`${tag} empieza`)
      try {
        const src = await fetchArchiveRange(id, file, start, end, AbortSignal.timeout(ARCHIVE_TIMEOUT))
        console.log(`${tag} archive.org ${src.status} en ${Date.now() - t0} ms`)
        if (src.status === 401 || src.status === 403) {
          return finish({ error: 'archive.org pide iniciar sesión: faltan o no valen IA_ACCESS_KEY / IA_SECRET_KEY' })
        }
        if (src.status !== 206 && !(src.status === 200 && start === 0 && end === total)) {
          return finish({ error: `archive.org devolvió ${src.status}` })
        }
        const data = new Uint8Array(await src.arrayBuffer()) // bounded by ARCHIVE_TIMEOUT (same signal)
        if (data.byteLength !== end - start) return finish({ error: `archive.org devolvió ${data.byteLength} bytes en vez de ${end - start}` })

        console.log(`${tag} descargado ${data.byteLength} bytes en ${Date.now() - t0} ms`)
        const put = await fetch(target.toString(), {
          method: 'PUT',
          headers: { 'Content-Range': `bytes ${start}-${end - 1}/${total}`, 'Content-Length': String(data.byteLength) },
          body: data,
        })
        console.log(`${tag} Drive ${put.status} en ${Date.now() - t0} ms`)
        if (put.status === 308) return finish({ status: 308, range: put.headers.get('range') })
        if (put.status === 200 || put.status === 201) return finish({ status: put.status, file: await put.json() })
        return finish({ error: `Drive devolvió ${put.status}`, status: put.status })
      } catch (e) {
        console.log(`${tag} error: ${e instanceof Error ? e.message : String(e)}`)
        finish({ error: e instanceof Error ? e.message : String(e) })
      }
    },
  })
  return new Response(stream, { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } })
}
