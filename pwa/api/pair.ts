/**
 * Pairs the stick's RetroShop with this account. The phone (logged in: the middleware only lets it through
 * with the login cookie) asks for a pass and leaves it in Drive (Retro/Config/stick/pair-<code>.json), where
 * the stick showing that code picks it up.
 *
 *   POST /api/pair  →  { token: "rs1.<id>.<HMAC>" (the admin) or "rs2.<user>.<id>.<HMAC>" (a user, api/_users.ts) }
 *
 * The middleware checks the HMAC, so passes need no storage; changing AUTH_SECRET revokes them all.
 */
export const config = { runtime: 'edge' }

import { stickToken, whoFromCookie } from './_users.js'

declare const process: { env: Record<string, string | undefined> }

export default async function handler(request: Request): Promise<Response> {
  if (request.method !== 'POST') return new Response('POST', { status: 405 })
  const secret = process.env.AUTH_SECRET
  if (!secret) return new Response('Acceso no configurado', { status: 503 })
  // The pass belongs to whoever is logged in on the phone (the admin's keep the original "rs1" form)
  const who = await whoFromCookie(request)
  if (!who) return new Response('Inicia sesión', { status: 401 })
  const token = await stickToken(who, secret)
  return new Response(JSON.stringify({ token }), { headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' } })
}
