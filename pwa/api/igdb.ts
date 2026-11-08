/**
 * Vercel Edge Function: proxy for the IGDB API (Twitch). The client credential (IGDB_CLIENT_SECRET) is
 * confidential, so the OAuth token and the games query happen server-side; the browser only sees the
 * mapped result. Same-origin, so no CORS headers are needed.
 *
 * Requires the Vercel environment variables IGDB_CLIENT_ID and IGDB_CLIENT_SECRET (see README 7.7).
 */
export const config = { runtime: 'edge' }

import { genreEs } from '../src/lib/genres.js'

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
  /** In Spanish when we could get it (Spanish Wikipedia, else a translation of IGDB's); see spanishDescription. */
  description: string | null
  /** IGDB's own text (English), kept for reference. */
  descriptionEn?: string | null
  descriptionSource?: 'wikipedia' | 'traducción' | 'igdb'
  trailerId: string | null
  screenshots: string[]
  coverUrl: string | null
  genres: string[]
  /** Most people who can play at once on the console (IGDB's offline modes); 1 = single player only */
  players: number | null
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
    genres: (g.genres ?? []).map((x: any) => x.name).filter(Boolean).map(genreEs),
    players: playersOf(g),
    year: g.first_release_date ? new Date(g.first_release_date * 1000).getUTCFullYear() : null,
    rating: g.rating != null ? Math.round(g.rating) / 10 : null,
  }
}
/* eslint-enable @typescript-eslint/no-explicit-any */

/** Local players: the offline maximum IGDB gives; split screen or co-op without a number counts as two. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function playersOf(g: any): number | null {
  const modes: number[] = g.game_modes ?? []
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const offline = Math.max(0, ...(g.multiplayer_modes ?? []).map((m: any) => Math.max(m.offlinemax ?? 0, m.offlinecoopmax ?? 0)))
  if (offline > 1) return offline
  if (modes.includes(4) || modes.includes(3)) return 2
  if (modes.length) return 1
  return null
}

function json(body: unknown, status = 200, cacheDays = 0): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      'content-type': 'application/json; charset=utf-8',
      // A found game rarely changes: Vercel's cache keeps it (the login middleware still runs first).
      'cache-control': cacheDays ? `private, max-age=86400, s-maxage=${cacheDays * 86400}` : 'no-store',
    },
  })
}

const CONSOLE_NAMES: Record<string, string> = {
  nes: 'NES', snes: 'Super Nintendo', gb: 'Game Boy', gbc: 'Game Boy Color', gba: 'Game Boy Advance', md: 'Mega Drive', sms: 'Master System', psx: 'PlayStation',
}
/** The article must be about this console's version ("PlayStation", not "PlayStation 2"). */
const CONSOLE_TEXT: Record<string, RegExp> = {
  nes: /\bNES\b|Nintendo Entertainment System|Famicom/,
  snes: /Super Nintendo|Super NES|\bSNES\b|Super Famicom/,
  gb: /Game Boy(?! Advance| Color)/,
  gbc: /Game Boy Color/,
  gba: /Game Boy Advance/,
  md: /Mega Drive|Genesis/,
  sms: /Master System|Mark III/,
  psx: /PlayStation(?!\s?[2-5]|\s?Portable|\s?Vita)/,
}
const looseKey = (s: string) => s.toLowerCase().normalize('NFD').replace(/[^a-z0-9]/g, '')
const UA = { 'User-Agent': 'RetroStick/1.0 (personal retro games launcher)' }

/**
 * The game's article in the Spanish Wikipedia (its introduction), when there is one about this video game:
 * real Spanish, usually better than a translation. Null otherwise.
 */
async function wikipediaEs(name: string, consoleId: string | null): Promise<string | null> {
  const q = `${name} videojuego ${consoleId ? CONSOLE_NAMES[consoleId] ?? '' : ''}`.trim()
  const url = 'https://es.wikipedia.org/w/api.php?action=query&format=json&redirects=1&generator=search' +
    `&gsrsearch=${encodeURIComponent(q)}&gsrlimit=5&prop=extracts|description&exintro=1&explaintext=1&exlimit=5`
  const res = await fetch(url, { headers: UA })
  // Busy or refusing (too many requests): an error, so the answer is kept only briefly and retried later
  if (!res.ok) throw new Error(`wikipedia ${res.status}`)
  /* eslint-disable @typescript-eslint/no-explicit-any */
  const pages: any[] = Object.values(((await res.json()) as any).query?.pages ?? {})
  pages.sort((a, b) => (a.index ?? 99) - (b.index ?? 99))
  const want = looseKey(name)
  for (const p of pages) {
    const title = looseKey(String(p.title ?? '').replace(/\s*\(.*\)$/, ''))
    const text = String(p.extract ?? '').trim()
    const aboutGame = /videojuego/i.test(String(p.description ?? '')) || /videojuego/i.test(text.slice(0, 400))
    if (!aboutGame || text.length < 80) continue
    // Exactly this game: not its series ("The Legend of Zelda") nor a namesake on another console
    if (title !== want) continue
    if (consoleId && CONSOLE_TEXT[consoleId] && !CONSOLE_TEXT[consoleId].test(text)) continue
    return trimText(text)
  }
  /* eslint-enable @typescript-eslint/no-explicit-any */
  return null
}

/** IGDB's English text through Google's free translation endpoint (no key). Null if it fails. */
async function translateEs(text: string): Promise<string | null> {
  const url = `https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=es&dt=t&q=${encodeURIComponent(text.slice(0, 4500))}`
  const res = await fetch(url, { headers: UA })
  if (!res.ok) return null
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const data = (await res.json()) as any
  const out = (data?.[0] ?? []).map((part: unknown[]) => part?.[0] ?? '').join('').trim()
  return out || null
}

/** At most ~900 characters, cut at the end of a sentence (Wikipedia introductions can be long). */
function trimText(text: string): string {
  const clean = text
    .replace(/\u200b/g, '')
    // Japanese names in brackets: "(ドンキーコング, Donkī Kongu?)"
    .replace(/\s*\([^)]*[\u3040-\u30ff\u4e00-\u9fff][^)]*\)/g, '')
    .replace(/\n+/g, ' ')
    .replace(/\s{2,}/g, ' ')
    .trim()
  if (clean.length <= 900) return clean
  const cut = clean.slice(0, 900)
  const end = Math.max(cut.lastIndexOf('. '), cut.lastIndexOf('.'))
  return end > 300 ? cut.slice(0, end + 1) : cut.trimEnd() + '…'
}

/** Set when Wikipedia could not be asked: the response is then cached for a day instead of a month. */
let wikiFailed = false

async function spanishDescription(game: IgdbGame, consoleId: string | null): Promise<IgdbGame> {
  const en = game.description
  wikiFailed = false
  const fromWiki = await wikipediaEs(game.name, consoleId).catch(() => {
    wikiFailed = true
    return null
  })
  if (fromWiki) return { ...game, description: fromWiki, descriptionEn: en, descriptionSource: 'wikipedia' }
  if (en) {
    const es = await translateEs(en).catch(() => null)
    if (es) return { ...game, description: es, descriptionEn: en, descriptionSource: 'traducción' }
  }
  return { ...game, descriptionEn: en, descriptionSource: 'igdb' }
}

/** IGDB platform ids per console (as in scripts/build-igdb.ts); GBC sets also hold Game Boy games. */
const PLATFORMS: Record<string, number[]> = { gb: [33], gbc: [22, 33], gba: [24], nes: [18, 99], snes: [19, 58], md: [29], sms: [64], psx: [7] }

export default async function handler(request: Request): Promise<Response> {
  const id = process.env.IGDB_CLIENT_ID
  const secret = process.env.IGDB_CLIENT_SECRET
  if (!id || !secret) return json({ error: 'IGDB no está configurado (faltan IGDB_CLIENT_ID / IGDB_CLIENT_SECRET).' }, 503)

  const params = new URL(request.url).searchParams
  const title = params.get('title')
  if (!title) return json({ error: 'Falta el parámetro title.' }, 400)
  // The game of that console ("Super Mario World" on SNES, not its GBA remake); any platform if none matches.
  const platforms = PLATFORMS[params.get('console') ?? '']

  try {
    const access = await accessToken(id, secret)
    const query = async (where: string) => {
      const body = `search "${title.replace(/"/g, '')}"; fields name,summary,storyline,cover.image_id,genres.name,game_modes,multiplayer_modes.offlinemax,multiplayer_modes.offlinecoopmax,platforms.name,first_release_date,rating,videos.video_id,screenshots.image_id,version_parent,total_rating_count;${where} limit 15;`
      const res = await fetch(`${API}/games`, {
        method: 'POST',
        headers: { 'Client-ID': id, Authorization: `Bearer ${access}`, Accept: 'application/json' },
        body,
      })
      if (!res.ok) throw new Error(`igdb ${res.status}`)
      return (await res.json()) as { name?: string; version_parent?: number; total_rating_count?: number }[]
    }
    let games = platforms ? await query(` where platforms = (${platforms.join(',')});`) : []
    if (!games.length) games = await query('')
    if (!games.length) return json({ found: false })
    // Search order is by relevance. Among the exact titles (ignoring case and punctuation), the original game
    // rather than one of its versions (Japan-only edition, re-releases…), then the one most people rated.
    const loose = (s: string) => s.toLowerCase().normalize('NFD').replace(/[^a-z0-9]/g, '')
    const exact = games
      .filter((g) => g.name && loose(g.name) === loose(title))
      .sort((a, b) => Number(!!a.version_parent) - Number(!!b.version_parent) || (b.total_rating_count ?? 0) - (a.total_rating_count ?? 0))
    const game = await spanishDescription(mapGame(exact[0] ?? games[0]), params.get('console'))
    return json(game, 200, wikiFailed ? 1 : 30)
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 500)
  }
}
