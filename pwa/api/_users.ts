/**
 * Users of the web app (besides the admin, AUTH_USER / AUTH_PASSWORD in Vercel's environment): kept in Upstash
 * Redis (UPSTASH_REDIS_REST_URL / _TOKEN, REST API, so it works from the edge middleware too).
 *
 *   user:<name>   JSON { name, salt, hash, created, v }   v = session version: bumping it ends every session
 *   users         set of names
 *   fail:<ip>     failed logins, expires after 15 minutes (10 = locked out until then)
 *
 * Passwords: PBKDF2-SHA256, 100 000 rounds, 16-byte random salt. Sessions are an HMAC-signed cookie
 * "u2.<name>.<v>.<sig>" (AUTH_SECRET); the admin keeps the original "v1" cookie. A user's record is read at
 * most every few minutes per server instance (CACHE_MS), so normal browsing barely touches the database.
 */
declare const process: { env: Record<string, string | undefined> }

export interface StoredUser {
  name: string
  salt: string
  hash: string
  created: number
  v: number
}

export interface Who {
  name: string
  admin: boolean
}

export const COOKIE = 'rs_auth'
export const USER_NAME = /^[a-z0-9._-]{2,32}$/
const CACHE_MS = 5 * 60_000
const ROUNDS = 100_000
const MAX_FAILS = 10

const hex = (b: ArrayBuffer | Uint8Array) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('')

export const normalizeName = (s: string) => s.trim().toLowerCase()

export function dbConfigured(): boolean {
  return !!(process.env.UPSTASH_REDIS_REST_URL && process.env.UPSTASH_REDIS_REST_TOKEN)
}

/** One Redis command through Upstash's REST API: ["GET", "user:pepe"] → its result. */
export async function redis<T = unknown>(...command: (string | number)[]): Promise<T> {
  const url = process.env.UPSTASH_REDIS_REST_URL
  const token = process.env.UPSTASH_REDIS_REST_TOKEN
  if (!url || !token) throw new Error('Base de datos de usuarios no configurada')
  const res = await fetch(url, {
    method: 'POST',
    headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
    body: JSON.stringify(command),
  })
  const json = (await res.json()) as { result?: T; error?: string }
  if (!res.ok || json.error) throw new Error(json.error ?? `Upstash ${res.status}`)
  return json.result as T
}

export async function sign(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value)))
}

/** Compares HMACs of both strings, so the time taken doesn't reveal them. */
export async function same(a: string, b: string, secret: string): Promise<boolean> {
  const [x, y] = await Promise.all([sign(a, secret), sign(b, secret)])
  let diff = x.length ^ y.length
  for (let i = 0; i < Math.min(x.length, y.length); i++) diff |= x.charCodeAt(i) ^ y.charCodeAt(i)
  return diff === 0
}

export async function hashPassword(password: string, salt?: string): Promise<{ salt: string; hash: string }> {
  const s = salt ?? hex(crypto.getRandomValues(new Uint8Array(16)))
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits'])
  const bits = await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode(s), iterations: ROUNDS }, key, 256)
  return { salt: s, hash: hex(bits) }
}

// ---- Users ----

const cache = new Map<string, { at: number; user: StoredUser | null }>()

export async function getUser(name: string, fresh = false): Promise<StoredUser | null> {
  const hit = cache.get(name)
  if (!fresh && hit && Date.now() - hit.at < CACHE_MS) return hit.user
  const raw = await redis<string | null>('GET', `user:${name}`)
  const user = raw ? (JSON.parse(raw) as StoredUser) : null
  cache.set(name, { at: Date.now(), user })
  return user
}

export async function putUser(user: StoredUser): Promise<void> {
  await redis('SET', `user:${user.name}`, JSON.stringify(user))
  await redis('SADD', 'users', user.name)
  cache.set(user.name, { at: Date.now(), user })
}

export async function deleteUser(name: string): Promise<void> {
  await redis('DEL', `user:${name}`)
  await redis('SREM', 'users', name)
  cache.set(name, { at: Date.now(), user: null })
}

export async function listUsers(): Promise<StoredUser[]> {
  const names = (await redis<string[]>('SMEMBERS', 'users')) ?? []
  if (!names.length) return []
  const raws = await redis<(string | null)[]>('MGET', ...names.map((n) => `user:${n}`))
  return raws.filter((r): r is string => !!r).map((r) => JSON.parse(r) as StoredUser).sort((a, b) => a.name.localeCompare(b.name))
}

export async function checkPassword(user: StoredUser, password: string): Promise<boolean> {
  const { hash } = await hashPassword(password, user.salt)
  const secret = process.env.AUTH_SECRET ?? ''
  return same(hash, user.hash, secret)
}

// ---- Failed logins ----

export async function lockedOut(ip: string): Promise<boolean> {
  if (!dbConfigured()) return false
  const n = await redis<string | null>('GET', `fail:${ip}`).catch(() => null)
  return Number(n ?? 0) >= MAX_FAILS
}

export async function loginFailed(ip: string): Promise<void> {
  if (!dbConfigured()) return
  await redis('INCR', `fail:${ip}`).catch(() => {})
  await redis('EXPIRE', `fail:${ip}`, 900).catch(() => {})
}

// ---- Sessions ----

export async function adminCookie(secret: string, user: string): Promise<string> {
  return sign(`v1:${user}`, secret)
}

export async function userCookie(user: StoredUser, secret: string): Promise<string> {
  return `u2.${user.name}.${user.v}.${await sign(`u2:${user.name}:${user.v}`, secret)}`
}

export function readCookie(request: Request, name = COOKIE): string | null {
  const m = new RegExp(`(?:^|;\\s*)${name}=([^;]+)`).exec(request.headers.get('cookie') ?? '')
  return m ? decodeURIComponent(m[1]) : null
}

const YEAR = 365 * 24 * 3600
export const setCookie = (value: string) => `${COOKIE}=${value}; Path=/; Max-Age=${YEAR}; HttpOnly; Secure; SameSite=Lax`

/** Who the request's cookie belongs to: the admin, a user still in the database (same session version), or null. */
export async function whoFromCookie(request: Request): Promise<Who | null> {
  const { AUTH_USER: admin, AUTH_SECRET: secret } = process.env
  if (!admin || !secret) return null
  const value = readCookie(request)
  if (!value) return null
  if (value === (await adminCookie(secret, admin))) return { name: admin, admin: true }
  const m = /^u2\.([a-z0-9._-]{2,32})\.(\d+)\.([0-9a-f]{64})$/.exec(value)
  if (!m || !dbConfigured()) return null
  if (!(await same(m[3], await sign(`u2:${m[1]}:${m[2]}`, secret), secret))) return null
  const user = await getUser(m[1]).catch(() => null)
  return user && String(user.v) === m[2] ? { name: user.name, admin: false } : null
}

// ---- Stick passes (RetroShop) ----

const RS1 = /^rs1\.([0-9a-f]{32})\.([0-9a-f]{64})$/
const RS2 = /^rs2\.([a-z0-9._-]{2,32})\.([0-9a-f]{32})\.([0-9a-f]{64})$/

/** A new pass for [who]'s stick: the admin's keep the original "rs1" form; a user's carry their name. */
export async function stickToken(who: Who, secret: string): Promise<string> {
  const id = hex(crypto.getRandomValues(new Uint8Array(16)))
  if (who.admin) return `rs1.${id}.${await sign(`stick:${id}`, secret)}`
  return `rs2.${who.name}.${id}.${await sign(`stick:${who.name}:${id}`, secret)}`
}

/** The owner of a stick pass (x-stick-token): the admin (rs1), a user that still exists (rs2), or null. */
export async function whoFromStickToken(value: string | null): Promise<Who | null> {
  const { AUTH_USER: admin, AUTH_SECRET: secret } = process.env
  if (!value || !admin || !secret) return null
  const m1 = RS1.exec(value)
  if (m1) return (await same(m1[2], await sign(`stick:${m1[1]}`, secret), secret)) ? { name: admin, admin: true } : null
  const m2 = RS2.exec(value)
  if (!m2 || !dbConfigured()) return null
  if (!(await same(m2[3], await sign(`stick:${m2[1]}:${m2[2]}`, secret), secret))) return null
  const user = await getUser(m2[1]).catch(() => null)
  return user ? { name: user.name, admin: false } : null
}

/** Who is asking: the browser's cookie, else the stick's pass. */
export async function whoIs(request: Request): Promise<Who | null> {
  return (await whoFromCookie(request)) ?? (await whoFromStickToken(request.headers.get('x-stick-token')))
}

export const json = (body: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers } })
