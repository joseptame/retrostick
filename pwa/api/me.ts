/**
 * Who is logged in, and changing one's own password (users from the database; the admin's lives in Vercel).
 *
 *   GET  /api/me                              → { name, admin }
 *   POST /api/me  { current, password }       → new password; the session continues (new cookie), others end
 */
import { checkPassword, getUser, hashPassword, json, putUser, setCookie, userCookie, whoFromCookie } from './_users.js'

export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

export default async function handler(request: Request): Promise<Response> {
  const who = await whoFromCookie(request)
  if (!who) return json({ error: 'Inicia sesión' }, 401)
  if (request.method === 'GET') return json(who)
  if (request.method !== 'POST') return json({ error: 'Método no admitido' }, 405)
  if (who.admin) return json({ error: 'La contraseña del administrador se cambia en Vercel (AUTH_PASSWORD)' }, 400)
  const body = (await request.json().catch(() => ({}))) as { current?: string; password?: string }
  if (!body.password || body.password.length < 8) return json({ error: 'La contraseña nueva necesita al menos 8 caracteres' }, 400)
  const user = await getUser(who.name, true)
  if (!user || !(await checkPassword(user, body.current ?? ''))) return json({ error: 'La contraseña actual no es correcta' }, 400)
  const updated = { ...user, ...(await hashPassword(body.password)), v: user.v + 1 }
  await putUser(updated)
  return json({ ok: true }, 200, { 'set-cookie': setCookie(await userCookie(updated, process.env.AUTH_SECRET ?? '')) })
}
