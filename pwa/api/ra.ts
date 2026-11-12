/**
 * Vercel Edge Function: a game's RetroAchievements for the stick's Logros screen.
 *
 *   GET /api/ra?console=md&title=Sonic%20The%20Hedgehog&md5=<ROM hash>&user=jfuster
 *
 * Finds the game in RetroAchievements (by the ROM's MD5 when the stick sends it, else by title), then the
 * user's progress: every achievement with its badge, points, how rare it is and when it was earned. Titles and
 * descriptions are translated to Spanish (Google's free endpoint, kept per game in memory). The Web API key
 * (RA_API_KEY) stays server-side. Only reachable with the stick's pass (middleware.ts).
 */
export const config = { runtime: 'edge' }

declare const process: { env: Record<string, string | undefined> }

const API = 'https://retroachievements.org/API'
const MEDIA = 'https://media.retroachievements.org'
/** RetroAchievements console ids */
const CONSOLE_IDS: Record<string, number> = { md: 1, snes: 3, gb: 4, gba: 5, gbc: 6, nes: 7, sms: 11, n64: 2, neogeo: 27, psx: 12 }

interface ListGame { Title: string; ID: number; NumAchievements: number; Hashes?: string[] }
// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Any = any

const lists = new Map<number, { at: number; games: ListGame[] }>()
const translations = new Map<string, { titles: string[]; descriptions: string[] }>()

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' },
  })
}

async function get(path: string, key: string): Promise<Any> {
  const res = await fetch(`${API}/${path}${path.includes('?') ? '&' : '?'}y=${encodeURIComponent(key)}`, {
    headers: { 'User-Agent': 'RetroStick/1.0 (+https://retrostick.vercel.app)' },
  })
  if (!res.ok) throw new Error(`RetroAchievements ${res.status}`)
  return res.json()
}

/** Games with achievements of a console (with their ROM hashes), kept a day per server instance. */
async function gameList(consoleId: number, key: string): Promise<ListGame[]> {
  const cached = lists.get(consoleId)
  if (cached && Date.now() - cached.at < 86_400_000) return cached.games
  const games = (await get(`API_GetGameList.php?i=${consoleId}&h=1&f=1`, key)) as ListGame[]
  lists.set(consoleId, { at: Date.now(), games })
  return games
}

const loose = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/\(.*?\)|\[.*?\]/g, '').replace(/[^a-z0-9]/g, '')

/** "~Hack~", "~Homebrew~", "[Subset - …]": never the retail game. */
const isExtra = (t: string) => /~[^~]+~|\[subset/i.test(t)

/**
 * The game by the ROM's hash, or by title when the hash isn't one of the versions its achievements support
 * (`supported: false`: RetroArch then unlocks "Unsupported Game Version" and the real ones may misfire).
 * Without a hash (PS1 discs) the title decides and it counts as supported.
 */
function findGame(games: ListGame[], title: string, md5: string | null): { game: ListGame; supported: boolean } | null {
  if (md5) {
    const h = md5.toLowerCase()
    const byHash = games.find((g) => g.Hashes?.some((x) => x.toLowerCase() === h))
    if (byHash) return { game: byHash, supported: true }
  }
  const want = loose(title.replace(/^(.*), (The|A|An)\b/i, '$2 $1'))
  const retail = games.filter((g) => !isExtra(g.Title))
  // RetroAchievements writes "Legend of Zelda, The: …" / "Pokemon FireRed Version | Pokemon LeafGreen Version" sometimes
  const keys = (t: string) => t.split('|').map((p) => loose(p.replace(/^(.*), (The|A|An)\b/i, '$2 $1')))
  const byTitle = retail.find((g) => keys(g.Title).includes(want))
  return byTitle ? { game: byTitle, supported: !md5 } : null
}

/** Many short English lines to Spanish in one request; null if the line count doesn't survive. */
async function translateLines(lines: string[]): Promise<string[] | null> {
  if (!lines.length) return []
  const res = await fetch('https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=es&dt=t', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', 'User-Agent': 'Mozilla/5.0' },
    body: new URLSearchParams({ q: lines.join('\n') }),
  })
  if (!res.ok) return null
  const data = (await res.json()) as Any
  const out = (data?.[0] ?? []).map((p: unknown[]) => p?.[0] ?? '').join('').split('\n').map((s: string) => s.trim())
  return out.length === lines.length ? out : null
}

async function spanish(gameId: number, titles: string[], descriptions: string[]) {
  const k = String(gameId)
  const hit = translations.get(k)
  if (hit && hit.titles.length === titles.length) return hit
  const [t, d] = await Promise.all([translateLines(titles).catch(() => null), translateLines(descriptions).catch(() => null)])
  const out = { titles: t ?? titles, descriptions: d ?? descriptions }
  if (t && d) translations.set(k, out)
  return out
}

/** "Warning: Unsupported Core", "Unsupported Game Version": 0-point warnings, not real achievements. */
const isWarning = (a: Any) => !a.Points && /unsupported|warning/i.test(`${a.Title ?? ''}`)

/**
 * ?profile=1&user=…: the user's trophy profile (Retro Ajustes → Mis logros): points and rank, every game
 * with achievements they've played (progress) and the last achievements earned (30 days), in Spanish.
 */
async function profile(user: string, key: string): Promise<Response> {
  const u = encodeURIComponent(user)
  const [summary, completion, recent] = await Promise.all([
    get(`API_GetUserSummary.php?u=${u}&g=0&a=0`, key) as Promise<Any>,
    get(`API_GetUserCompletionProgress.php?u=${u}&c=500`, key) as Promise<Any>,
    get(`API_GetUserRecentAchievements.php?u=${u}&m=43200`, key) as Promise<Any[]>,
  ])
  const real = (recent ?? []).filter((a) => !isWarning(a)).slice(0, 30)
  const [titles, descriptions] = await Promise.all([
    translateLines(real.map((a) => a.Title ?? '')).catch(() => null),
    translateLines(real.map((a) => a.Description ?? '')).catch(() => null),
  ])
  const games = ((completion?.Results ?? []) as Any[])
    .filter((g) => (g.MaxPossible ?? 0) > 0)
    .map((g) => ({
      id: g.GameID,
      title: g.Title,
      console: g.ConsoleName,
      icon: g.ImageIcon ? `${MEDIA}${g.ImageIcon}` : null,
      total: g.MaxPossible,
      earned: Math.max(g.NumAwarded ?? 0, g.NumAwardedHardcore ?? 0),
      last: g.MostRecentAwardedDate ?? null,
    }))
  return json({
    user: summary?.User ?? user,
    avatar: summary?.UserPic ? `${MEDIA}${summary.UserPic}` : null,
    points: Number(summary?.TotalPoints) || 0,
    softcorePoints: Number(summary?.TotalSoftcorePoints) || 0,
    rank: Number(summary?.Rank) || null,
    memberSince: summary?.MemberSince ?? null,
    games,
    recent: real.map((a, i) => ({
      title: titles?.[i] || a.Title,
      description: descriptions?.[i] || a.Description,
      points: a.Points ?? 0,
      badge: a.BadgeURL ? `${MEDIA}${a.BadgeURL}` : null,
      game: a.GameTitle,
      gameId: a.GameID ?? null,
      console: a.ConsoleName,
      date: a.Date,
    })),
  })
}

/**
 * POST ?login=1 {user, password}: RetroAchievements' login for the phone app (Stick → RetroAchievements). Only
 * the user and the token it gives come back (they go to Retro/Config/ra.json); the password is passed on to
 * RetroAchievements and never kept nor logged.
 */
async function login(request: Request): Promise<Response> {
  if (request.method !== 'POST') return json({ error: 'Usa POST.' }, 405)
  const body = (await request.json().catch(() => null)) as { user?: string; password?: string } | null
  const user = body?.user?.trim()
  const password = body?.password
  if (!user || !password) return json({ error: 'Faltan el usuario y la contraseña.' }, 400)
  const res = await fetch('https://retroachievements.org/dorequest.php', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', 'User-Agent': 'RetroStick/1.0 (+https://retrostick.vercel.app)' },
    body: new URLSearchParams({ r: 'login2', u: user, p: password }),
  })
  const r = (await res.json().catch(() => null)) as Any
  if (!r?.Success || !r.Token) {
    const why = `${r?.Error ?? ''}`
    return json({ error: /password|credential|invalid/i.test(why) || res.status === 401 ? 'Usuario o contraseña incorrectos.' : why || `RetroAchievements respondió ${res.status}` }, 401)
  }
  return json({ user: r.User ?? user, token: r.Token })
}

export default async function handler(request: Request): Promise<Response> {
  if (new URL(request.url).searchParams.get('login')) {
    try {
      return await login(request)
    } catch (e) {
      return json({ error: e instanceof Error ? e.message : String(e) }, 502)
    }
  }
  const key = process.env.RA_API_KEY?.replace(/^["']|["']$/g, '')
  if (!key) return json({ error: 'RetroAchievements no está configurado (falta RA_API_KEY).' }, 503)
  const url = new URL(request.url)
  if (url.searchParams.get('profile')) {
    const who = url.searchParams.get('user')?.trim()
    if (!who) return json({ error: 'Falta user.' }, 400)
    try {
      return await profile(who, key)
    } catch (e) {
      return json({ error: e instanceof Error ? e.message : String(e) }, 502)
    }
  }
  const consoleId = CONSOLE_IDS[url.searchParams.get('console') ?? '']
  const title = url.searchParams.get('title')?.trim() ?? ''
  const md5 = url.searchParams.get('md5')
  const user = url.searchParams.get('user')?.trim()
  // ?id=…: a game by its RetroAchievements id (Mis logros → a game the stick may no longer have)
  const byId = Number(url.searchParams.get('id')) || 0
  if (!byId && (!consoleId || !title)) return json({ error: 'Faltan console y title.' }, 400)

  try {
    const match = byId
      ? { game: { ID: byId, Title: '' } as ListGame, supported: true }
      : findGame(await gameList(consoleId, key), title, md5)
    if (!match) return json({ found: false })
    const game = match.game
    const info = (await get(
      user ? `API_GetGameInfoAndUserProgress.php?g=${game.ID}&u=${encodeURIComponent(user)}` : `API_GetGameExtended.php?i=${game.ID}`,
      key,
    )) as Any
    const players = Number(info.NumDistinctPlayers) || 0
    const list = (Object.values(info.Achievements ?? {}) as Any[]).filter((a) => !isWarning(a))
    list.sort((a, b) => (a.DisplayOrder ?? 0) - (b.DisplayOrder ?? 0) || a.ID - b.ID)
    const es = await spanish(game.ID, list.map((a) => a.Title ?? ''), list.map((a) => a.Description ?? ''))
    const achievements = list.map((a, i) => ({
      id: a.ID,
      title: es.titles[i] || a.Title,
      description: es.descriptions[i] || a.Description,
      points: a.Points ?? 0,
      badge: `${MEDIA}/Badge/${a.BadgeName}.png`,
      badgeLocked: `${MEDIA}/Badge/${a.BadgeName}_lock.png`,
      earned: a.DateEarned ?? a.DateEarnedHardcore ?? null,
      // Share of the game's players who have it (PS5-style rarity)
      rarity: players ? Math.round(((a.NumAwarded ?? 0) / players) * 1000) / 10 : null,
    }))
    const earned = achievements.filter((a) => a.earned)
    return json({
      found: true,
      supported: match.supported,
      id: game.ID,
      title: info.Title ?? game.Title,
      icon: info.ImageIcon ? `${MEDIA}${info.ImageIcon}` : null,
      total: achievements.length,
      earned: earned.length,
      points: achievements.reduce((s, a) => s + a.points, 0),
      earnedPoints: earned.reduce((s, a) => s + a.points, 0),
      players,
      achievements,
    })
  } catch (e) {
    return json({ error: e instanceof Error ? e.message : String(e) }, 502)
  }
}
