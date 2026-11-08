/** IGDB (Twitch) data for a game, fetched through the same-origin proxy `api/igdb.ts`. */
export interface IgdbGame {
  name: string
  description: string | null
  trailerId: string | null
  screenshots: string[]
  coverUrl: string | null
  genres: string[]
  /** Local players (offline): 1 = single player, null = unknown */
  players?: number | null
  year: number | null
  rating: number | null
}

interface IgdbResponse extends Partial<IgdbGame> {
  found?: boolean
  error?: string
}

/** Returns the IGDB entry for a game title, or null when IGDB has no match. */
export async function fetchIgdb(title: string, console?: string): Promise<IgdbGame | null> {
  const res = await fetch(`/api/igdb?title=${encodeURIComponent(title)}${console ? `&console=${console}` : ''}`)
  if (!res.ok) throw new Error(`igdb ${res.status}`)
  const json = (await res.json()) as IgdbResponse
  if (json.error) throw new Error(json.error)
  if (json.found === false) return null
  return json as IgdbGame
}
