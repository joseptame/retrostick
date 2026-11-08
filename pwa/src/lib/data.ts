/**
 * Prebuilt data files (public/db/*.json). The PWA fetches them from its own origin; a server function
 * (api/shop.ts) points this at the deployment's URL with the headers that get it past the login.
 */
let base = '/'
let headers: Record<string, string> = {}

export function configureData(origin: string, extraHeaders: Record<string, string>) {
  base = origin.replace(/\/?$/, '/')
  headers = extraHeaders
}

export async function fetchData<T>(path: string): Promise<T> {
  const res = await fetch(base + path, { headers })
  if (!res.ok) throw new Error(`${path}: ${res.status}`)
  return (await res.json()) as T
}
