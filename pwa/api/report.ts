/**
 * Reports from sticks and TVs (Retro Ajustes → Enviar informe, Report.kt): what the device is, how its setup went,
 * which pads it sees and its recent errors, so the admin can tell what is wrong without having it at hand.
 * Kept in Upstash for 30 days, the latest 100.
 *
 *   POST   /api/report  { device, text }   any stick (its pass) or signed-in user → { id }
 *   GET    /api/report                     admin → [{ id, at, user, device }], newest first
 *   GET    /api/report?id=…                admin → { id, at, user, device, text }
 *   DELETE /api/report?id=…                admin
 *
 *   report:<id>  JSON record (expires after 30 days)     reports  list of ids, newest first
 */
import { dbConfigured, json, redis, whoIs } from './_users.js'

export const config = { runtime: 'edge' }

const KEEP_DAYS = 30
const KEEP = 100
const MAX_TEXT = 200_000
const ID = /^[0-9a-z]{6,40}$/

interface Report {
  id: string
  at: number
  user: string
  device: string
  text: string
}

export default async function handler(request: Request): Promise<Response> {
  const who = await whoIs(request)
  if (!who) return json({ error: 'Sin sesión' }, 401)
  if (!dbConfigured()) return json({ error: 'Falta la base de datos (UPSTASH_REDIS_REST_URL / _TOKEN en Vercel)' }, 503)
  const url = new URL(request.url)
  const id = url.searchParams.get('id')

  if (request.method === 'POST') {
    const body = (await request.json().catch(() => null)) as { device?: unknown; text?: unknown } | null
    if (typeof body?.text !== 'string' || !body.text.trim()) return json({ error: 'Informe vacío' }, 400)
    const report: Report = {
      id: Date.now().toString(36) + Math.random().toString(36).slice(2, 8),
      at: Date.now(),
      user: who.name,
      device: String(body.device ?? '').slice(0, 120),
      text: body.text.slice(0, MAX_TEXT),
    }
    await redis('SET', `report:${report.id}`, JSON.stringify(report), 'EX', KEEP_DAYS * 86_400)
    await redis('LPUSH', 'reports', report.id)
    await redis('LTRIM', 'reports', 0, KEEP - 1)
    return json({ id: report.id })
  }

  if (!who.admin) return json({ error: 'Solo el administrador ve los informes' }, 403)

  if (request.method === 'DELETE') {
    if (!id || !ID.test(id)) return json({ error: 'Falta el informe' }, 400)
    await redis('DEL', `report:${id}`)
    await redis('LREM', 'reports', 0, id)
    return json({ ok: true })
  }

  if (id) {
    if (!ID.test(id)) return json({ error: 'Informe no válido' }, 400)
    const raw = await redis<string | null>('GET', `report:${id}`)
    return raw ? json(JSON.parse(raw)) : json({ error: 'Ese informe ya no está' }, 404)
  }

  const ids = await redis<string[]>('LRANGE', 'reports', 0, KEEP - 1)
  if (!ids.length) return json([])
  const raws = await redis<(string | null)[]>('MGET', ...ids.map((i) => `report:${i}`))
  return json(raws.flatMap((r) => {
    if (!r) return []
    const { id, at, user, device } = JSON.parse(r) as Report
    return [{ id, at, user, device }]
  }))
}
