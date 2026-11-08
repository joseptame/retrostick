/**
 * Pairs the stick's RetroShop with this account. The phone (logged in: the middleware only lets it through
 * with the login cookie) asks for a pass and leaves it in Drive (Retro/Config/stick/pair-<code>.json), where
 * the stick showing that code picks it up.
 *
 *   POST /api/pair  →  { token: "rs1.<id>.<HMAC(AUTH_SECRET, 'stick:' + id)>" }
 *
 * The middleware checks the HMAC, so passes need no storage; changing AUTH_SECRET revokes them all.
 */
export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

async function sign(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== 'POST') return new Response('POST', { status: 405 })
  const secret = process.env.AUTH_SECRET
  if (!secret) return new Response('Acceso no configurado', { status: 503 })
  const id = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join('')
  const token = `rs1.${id}.${await sign(`stick:${id}`, secret)}`
  return new Response(JSON.stringify({ token }), { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } })
}
