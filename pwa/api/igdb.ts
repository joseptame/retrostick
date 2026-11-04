/**
 * Vercel Edge Function: proxy for the IGDB API (Twitch). The client credential (IGDB_CLIENT_SECRET) is
 * confidential, so the OAuth token and the games query happen server-side; the browser only sees the
 * mapped result. Same-origin, so no CORS headers are needed.
 *
 * Requires the Vercel environment variables IGDB_CLIENT_ID and IGDB_CLIENT_SECRET (see README 7.7).
 */
export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

const TOKEN_URL = 'https://id.twitch.tv/oauth2/token'
const API = 'https://api.igdb.com/v4'

let token: { value: string; expiresAt: number } | null = null

async function accessToken(id: string, secret: string): Promise<string> {
  if (token && Date.now() < token.expiresAt) return token.value
  const res = await fetch(`${TOKEN_URL}?client_id=${id}&client_secret=${secret}&grant_type=client_credentials`, {
    method: 'POST',
  })
  if (!res.ok) throw new Error(`twitch oauth ${res.status}`)
  const json = (await res.json()) as { access_token: string; expires_in: number }
  token = { value: json.access_token, expiresAt: Date.now() + json.expires_in * 1000 - 60_000 }
  return token.value
}

function imageUrl(size: string, imageId: string): string {
  return `https://images.igdb.com/igdb/image/upload/t_${size}/${imageId}.jpg`
}

interface IgdbGame {
  name: string
  description: string | null
  trailerId: string | null
  screenshots: string[]
  coverUrl: string | null
  genres: string[]
  year: number | null
  rating: number | null
}

/* eslint-disable @typescript-eslint/no-explicit-any */
function mapGame(g: any): IgdbGame {
  return {
    name: g.name ?? '',
    description: g.summary ?? g.storyline ?? null,
    trailerId: g.videos?.[0]?.video_id ?? null,
    screenshots: (g.screenshots ?? []).map((s: any) => imageUrl('screenshot_big', s.image_id)),
    coverUrl: g.cover?.image_id ? imageUrl('cover_big', g.cover.image_id) : null,
    genres: (g.genres ?? []).map((x: any) => x.name).filter(Boolean),
    year: g.first_release_date ? new Date(g.first_release_date * 1000).getUTCFullYear() : null,
    rating: g.rating != null ? Math.round(g.rating) / 10 : null,
  }
}
/* eslint-enable @typescript-eslint/no-explicit-any */

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

export default async function handler(request: Request): Promise<Response> {
  const id = process.env.IGDB_CLIENT_ID
  const secret = process.env.IGDB_CLIENT_SECRET
  if (!id || !secret) return json({ error: 'IGDB no está configurado (faltan IGDB_CLIENT_ID / IGDB_CLIENT_SECRET).' }, 503)

  const title = new URL(request.url).searchParams.get('title')
  if (!title) return json({ error: 'Falta el parámetro title.' }, 400)

  try {
    const access = await accessToken(id, secret)
    const body = `search "${title.replace(/"/g, '')}"; fields name,summary,storyline,cover.image_id,genres.name,platforms.name,first_release_date,rating,videos.video_id,screenshots.image_id; limit 1;`
    const res = await fetch(`${API}/games`, {
      method: 'POST',
      headers: { 'Client-ID': id, Authorization: `Bearer ${access}`, Accept: 'application/json' },
      body,
    })
    if (!res.ok) return json({ error: `igdb ${res.status}` }, 502)
    const games = (await res.json()) as unknown[]
    if (!games.length) return json({ found: false })
    return json(mapGame(games[0]))
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500)
  }
}
