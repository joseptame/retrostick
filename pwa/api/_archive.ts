/**
 * archive.org downloads from the server, shared by api/transfer.ts and api/download.ts. Restricted
 * ("loggedin") items need the user's S3 keys: IA_ACCESS_KEY and IA_SECRET_KEY (archive.org/account/s3.php),
 * sent as "Authorization: LOW access:secret" on every hop of the redirect.
 */
declare const process: { env: Record<string, string | undefined> }

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
export async function fetchArchiveRange(id: string, file: string, start: number, end: number, signal: AbortSignal): Promise<Response> {
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
