/**
 * The admin manages the users of the app (api/_users.ts, Upstash).
 *
 *   GET    /api/users                          → [{ name, created }]
 *   POST   /api/users  { name, password }      → new user
 *   PATCH  /api/users  { name, password }      → new password (their sessions on phones end; the stick keeps working)
 *   DELETE /api/users?name=…                   → user gone (their sessions and stick end)
 *
 *   GET / POST / DELETE /api/users?invite      → the invitation code: { code } / a new one / none (no sign-ups)
 * With an invitation code, anyone who has it creates their own account from the login page (middleware.ts, /__signup).
 */
import {
  dbConfigured, deleteUser, getUser, hashPassword, json, listUsers, normalizeName, putUser, redis, USER_NAME, whoFromCookie,
} from './_users.js'

/** 8 characters without look-alikes (no 0/O, 1/I/L), easy to dictate. */
function newCode(): string {
  const abc = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
  return [...crypto.getRandomValues(new Uint8Array(8))].map((b) => abc[b % abc.length]).join('')
}

export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

export default async function handler(request: Request): Promise<Response> {
  const who = await whoFromCookie(request)
  if (!who?.admin) return json({ error: 'Solo el administrador gestiona los usuarios' }, 403)
  if (!dbConfigured()) return json({ error: 'Falta la base de datos (UPSTASH_REDIS_REST_URL / _TOKEN en Vercel)' }, 503)

  if (new URL(request.url).searchParams.has('invite')) {
    if (request.method === 'POST') {
      const code = newCode()
      await redis('SET', 'invite', code)
      return json({ code })
    }
    if (request.method === 'DELETE') {
      await redis('DEL', 'invite')
      return json({ code: null })
    }
    return json({ code: (await redis<string | null>('GET', 'invite')) ?? null })
  }

  if (request.method === 'GET') {
    return json((await listUsers()).map((u) => ({ name: u.name, created: u.created })))
  }
  if (request.method === 'DELETE') {
    const name = normalizeName(new URL(request.url).searchParams.get('name') ?? '')
    if (!(await getUser(name, true))) return json({ error: 'No existe ese usuario' }, 404)
    await deleteUser(name)
    return json({ ok: true })
  }
  if (request.method === 'POST' || request.method === 'PATCH') {
    const body = (await request.json().catch(() => ({}))) as { name?: string; password?: string }
    const name = normalizeName(body.name ?? '')
    if (!USER_NAME.test(name)) return json({ error: 'El usuario: de 2 a 32 letras, números, puntos, guiones o _ (sin espacios)' }, 400)
    if (name === normalizeName(process.env.AUTH_USER ?? '')) return json({ error: 'Ese nombre es el del administrador' }, 400)
    if (!body.password || body.password.length < 8) return json({ error: 'La contraseña necesita al menos 8 caracteres' }, 400)
    const existing = await getUser(name, true)
    if (request.method === 'POST' && existing) return json({ error: 'Ya existe ese usuario' }, 409)
    if (request.method === 'PATCH' && !existing) return json({ error: 'No existe ese usuario' }, 404)
    await putUser({
      name, ...(await hashPassword(body.password)),
      created: existing?.created ?? Date.now(),
      // A new password ends the user's sessions in the browser (the stick's pass only ends with the user)
      v: (existing?.v ?? 0) + 1,
    })
    return json({ ok: true })
  }
  return json({ error: 'Método no admitido' }, 405)
}
