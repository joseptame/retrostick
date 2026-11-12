/**
 * Vercel Routing Middleware: the whole app is behind a login checked on the server.
 * Credentials live in Vercel environment variables (AUTH_USER, AUTH_PASSWORD, AUTH_SECRET), never in the repo.
 * A successful login sets a signed, HttpOnly cookie for one year; /__logout removes it.
 * The stick (RetroShop) has no cookie: it sends a pass issued by api/pair.ts once paired with a QR
 * (x-stick-token: "rs1.<id>.<HMAC>" for the admin's stick, "rs2.<user>.<id>.<HMAC>" for a user's; see api/_users.ts).
 * Besides the admin, users from the database (api/_users.ts, Upstash): each with their own password, created by the
 * admin in the app (Stick → Cuenta); deleting a user ends their sessions and their stick's pass.
 *
 * This file imports nothing on purpose: importing ./api/_users made Vercel's middleware fail (HTTP 500 on every
 * request). The helpers below are copies of the ones in api/_users.ts and must stay identical to them.
 */
export const config = {
  // The install metadata stays public: phones fetch the manifest and icons without cookies. So does the
  // pairing QR the stick shows (api/qr.ts only encodes this site's own pairing link).
  matcher: '/((?!manifest\\.webmanifest|icon-192\\.png|icon-512\\.png|apple-touch-icon\\.png|api/qr$).*)',
}

declare const process: { env: Record<string, string | undefined> }

const COOKIE = 'rs_auth'
const COOKIE_RE = /(?:^|;\s*)rs_auth=([^;]+)/
const U2 = /^u2\.([a-z0-9._-]{2,32})\.(\d+)\.([0-9a-f]{64})$/
const RS1 = /^rs1\.([0-9a-f]{32})\.([0-9a-f]{64})$/
const RS2 = /^rs2\.([a-z0-9._-]{2,32})\.([0-9a-f]{32})\.([0-9a-f]{64})$/

interface StoredUser {
  name: string
  salt: string
  hash: string
  v: number
}

const hex = (b: ArrayBuffer | Uint8Array) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('')
const dbConfigured = () => !!(process.env.UPSTASH_REDIS_REST_URL && process.env.UPSTASH_REDIS_REST_TOKEN)

/** One Redis command through Upstash's REST API. */
async function redis<T = unknown>(...command: (string | number)[]): Promise<T> {
  const res = await fetch(process.env.UPSTASH_REDIS_REST_URL ?? '', {
    method: 'POST',
    headers: { authorization: `Bearer ${process.env.UPSTASH_REDIS_REST_TOKEN}`, 'content-type': 'application/json' },
    body: JSON.stringify(command),
  })
  const json = (await res.json()) as { result?: T; error?: string }
  if (!res.ok || json.error) throw new Error(json.error ?? `Upstash ${res.status}`)
  return json.result as T
}

async function sign(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  return hex(await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value)))
}

/** Compares HMACs of both strings, so the time taken doesn't reveal them. */
async function same(a: string, b: string, secret: string): Promise<boolean> {
  const [x, y] = await Promise.all([sign(a, secret), sign(b, secret)])
  let diff = x.length ^ y.length
  for (let i = 0; i < Math.min(x.length, y.length); i++) diff |= x.charCodeAt(i) ^ y.charCodeAt(i)
  return diff === 0
}

const cache = new Map<string, { at: number; user: StoredUser | null }>()

async function getUser(name: string, fresh = false): Promise<StoredUser | null> {
  const hit = cache.get(name)
  if (!fresh && hit && Date.now() - hit.at < 5 * 60_000) return hit.user
  const raw = await redis<string | null>('GET', `user:${name}`)
  const user = raw ? (JSON.parse(raw) as StoredUser) : null
  cache.set(name, { at: Date.now(), user })
  return user
}

async function pbkdf2(password: string, salt: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(password), 'PBKDF2', false, ['deriveBits'])
  return hex(await crypto.subtle.deriveBits(
    { name: 'PBKDF2', hash: 'SHA-256', salt: new TextEncoder().encode(salt), iterations: 100_000 }, key, 256))
}

async function checkPassword(user: StoredUser, password: string, secret: string): Promise<boolean> {
  return same(await pbkdf2(password, user.salt), user.hash, secret)
}

/**
 * Sign-up with the admin's invitation code (api/users.ts?invite): a new user, or the reason it can't be created.
 * SET … NX so two sign-ups with the same name can't overwrite each other.
 */
async function signUp(name: string, password: string, code: string, admin: string, secret: string): Promise<StoredUser | string> {
  const invite = await redis<string | null>('GET', 'invite')
  if (!invite) return 'Ahora mismo no se pueden crear cuentas. Pídele al administrador un código de invitación.'
  if (!(await same(code.trim().toUpperCase(), invite, secret))) return 'El código de invitación no es correcto.'
  if (!/^[a-z0-9._-]{2,32}$/.test(name)) return 'El usuario: de 2 a 32 letras, números, puntos, guiones o _ (sin espacios ni acentos).'
  if (name === admin.trim().toLowerCase()) return 'Ese nombre de usuario ya existe.'
  if (password.length < 8) return 'La contraseña necesita al menos 8 caracteres.'
  const salt = hex(crypto.getRandomValues(new Uint8Array(16)))
  const user = { name, salt, hash: await pbkdf2(password, salt), created: Date.now(), v: 1 }
  if ((await redis<string | null>('SET', `user:${name}`, JSON.stringify(user), 'NX')) !== 'OK') return 'Ese nombre de usuario ya existe.'
  await redis('SADD', 'users', name)
  cache.set(name, { at: Date.now(), user })
  return user
}

/** Too many failed logins from this connection (10 in 15 minutes). */
async function lockedOut(ip: string): Promise<boolean> {
  if (!dbConfigured()) return false
  const n = await redis<string | null>('GET', `fail:${ip}`).catch(() => null)
  return Number(n ?? 0) >= 10
}

async function loginFailed(ip: string): Promise<void> {
  if (!dbConfigured()) return
  await redis('INCR', `fail:${ip}`).catch(() => {})
  await redis('EXPIRE', `fail:${ip}`, 900).catch(() => {})
}

const adminCookie = (secret: string, admin: string) => sign(`v1:${admin}`, secret)
const userCookie = async (u: StoredUser, secret: string) => `u2.${u.name}.${u.v}.${await sign(`u2:${u.name}:${u.v}`, secret)}`
const setCookie = (value: string) => `${COOKIE}=${value}; Path=/; Max-Age=31536000; HttpOnly; Secure; SameSite=Lax`

/** The cookie belongs to the admin or to a user still in the database (same session version). */
async function validCookie(request: Request, admin: string, secret: string): Promise<boolean> {
  const m0 = COOKIE_RE.exec(request.headers.get('cookie') ?? '')
  if (!m0) return false
  const value = decodeURIComponent(m0[1])
  if (value === (await adminCookie(secret, admin))) return true
  const m = U2.exec(value)
  if (!m || !dbConfigured()) return false
  if (!(await same(m[3], await sign(`u2:${m[1]}:${m[2]}`, secret), secret))) return false
  const user = await getUser(m[1]).catch(() => null)
  return !!user && String(user.v) === m[2]
}

/** The stick's pass: the admin's (rs1) or a user's that still exists (rs2). */
async function validStickToken(value: string | null, secret: string): Promise<boolean> {
  if (!value) return false
  const m1 = RS1.exec(value)
  if (m1) return same(m1[2], await sign(`stick:${m1[1]}`, secret), secret)
  const m2 = RS2.exec(value)
  if (!m2 || !dbConfigured()) return false
  if (!(await same(m2[3], await sign(`stick:${m2[1]}:${m2[2]}`, secret), secret))) return false
  return !!(await getUser(m2[1]).catch(() => null))
}

/** Equivalent of next() from @vercel/functions: let the request through. */
const pass = () => new Response(null, { headers: { 'x-middleware-next': '1' } })

const clientIp = (request: Request) =>
  (request.headers.get('x-real-ip') ?? request.headers.get('x-forwarded-for')?.split(',')[0] ?? 'unknown').trim()

export default async function middleware(request: Request): Promise<Response> {
  const { AUTH_USER: user, AUTH_PASSWORD: password, AUTH_SECRET: secret } = process.env
  if (!user || !password || !secret) {
    return new Response('Acceso no configurado.', { status: 503, headers: { 'content-type': 'text/plain; charset=utf-8' } })
  }
  const url = new URL(request.url)

  if (url.pathname === '/__logout') {
    return new Response(null, {
      status: 303,
      headers: { location: '/', 'set-cookie': `${COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax` },
    })
  }

  if (url.pathname === '/__signup' && request.method === 'GET') {
    return signupPage(null, url.searchParams.get('next') ?? '/')
  }

  if ((url.pathname === '/__login' || url.pathname === '/__signup') && request.method === 'POST') {
    const form = await request.formData()
    const next = String(form.get('next') ?? '/')
    const ip = clientIp(request)
    const name = String(form.get('user') ?? '')
    const given = String(form.get('password') ?? '')
    // Back to the page that asked for the login (a pairing link keeps its ?vincular=…).
    const go = (cookieValue: string) => new Response(null, {
      status: 303,
      headers: { location: next.startsWith('/') && !next.startsWith('//') ? next : '/', 'set-cookie': setCookie(cookieValue) },
    })

    if (url.pathname === '/__signup') {
      if (!dbConfigured()) return signupPage('Las cuentas no están disponibles todavía.', next)
      // A wrong invitation code counts as a failed login: guessing codes gets locked out like guessing passwords
      if (await lockedOut(ip)) return signupPage('Demasiados intentos. Espera 15 minutos y vuelve a probar.', next)
      const created = await signUp(name.trim().toLowerCase(), given, String(form.get('code') ?? ''), user, secret)
        .catch(() => 'No se ha podido crear la cuenta. Prueba otra vez en un momento.')
      if (typeof created === 'string') {
        if (created.startsWith('El código')) await loginFailed(ip)
        return signupPage(created, next, name)
      }
      return go(await userCookie(created, secret))
    }

    // Too many failed attempts from this connection: locked out for 15 minutes
    if (await lockedOut(ip)) return loginPage(false, next, true)
    // The admin (Vercel's AUTH_USER / AUTH_PASSWORD), as before
    if ((await same(name, user, secret)) && (await same(given, password, secret))) return go(await adminCookie(secret, user))
    // A user from the database
    const key = name.trim().toLowerCase()
    if (dbConfigured() && key) {
      const stored = await getUser(key, true).catch(() => null)
      if (stored && (await checkPassword(stored, given, secret))) return go(await userCookie(stored, secret))
    }
    await loginFailed(ip)
    return loginPage(true, next)
  }

  if (await validCookie(request, user, secret)) return pass()
  if (await validStickToken(request.headers.get('x-stick-token'), secret)) return pass()
  return loginPage(false, url.pathname + url.search)
}

const escapeAttr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')

/** The login and sign-up pages share this shell (the app's dark look, no scripts). */
function page(title: string, body: string, status: number): Response {
  const html = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0d0b1f">
<meta name="robots" content="noindex">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<title>RetroStick · ${title}</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{min-height:100vh;font-family:Inter,system-ui,sans-serif;color:#f2f0ff;display:flex;align-items:center;justify-content:center;
padding:24px;background:radial-gradient(900px 600px at 50% 0%,#2a2170,#0d0b1f 70%) fixed #0d0b1f}
form{width:100%;max-width:360px}
.brand{font-weight:800;font-size:26px;margin-bottom:6px}.brand span{color:#9d8cff}
p{color:#a49ccf;font-size:14px;margin-bottom:24px}
label{display:block;font-size:12px;letter-spacing:1.2px;text-transform:uppercase;color:#8f88bd;margin:14px 0 6px}
input{width:100%;padding:14px;border-radius:14px;border:1px solid rgba(255,255,255,.12);background:rgba(255,255,255,.06);
color:#f2f0ff;font-size:16px}
input:focus{outline:2px solid #7c6cff;border-color:transparent}
button{width:100%;margin-top:22px;padding:15px;border:0;border-radius:16px;background:#fff;color:#1b1446;font-weight:800;font-size:16px}
.error{margin:0 0 4px;padding:11px 14px;border-radius:12px;background:rgba(248,113,113,.13);color:#fecaca;font-size:13px}
.hint{display:block;margin-top:6px;font-size:12px;color:#8f88bd}
.alt{display:block;margin-top:20px;text-align:center;font-size:14px;color:#a49ccf}
.alt a{color:#c9c0ff;font-weight:700;text-decoration:none}
</style>
</head>
<body>
${body}
</body>
</html>`
  return new Response(html, { status, headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' } })
}

function loginPage(failed: boolean, next = '/', locked = false): Response {
  return page('Acceso', `<form method="post" action="/__login">
<input type="hidden" name="next" value="${escapeAttr(next)}">
<div class="brand">Retro<span>Stick</span></div>
<p>Acceso privado</p>
${locked ? '<div class="error">Demasiados intentos. Espera 15 minutos y vuelve a probar.</div>' : failed ? '<div class="error">Usuario o contraseña incorrectos.</div>' : ''}
<label for="user">Usuario</label>
<input id="user" name="user" autocomplete="username" autocapitalize="none" autocorrect="off" required>
<label for="password">Contraseña</label>
<input id="password" name="password" type="password" autocomplete="current-password" required>
<button type="submit">Entrar</button>
<span class="alt">¿Tienes un código de invitación? <a href="/__signup?next=${encodeURIComponent(next)}">Crear cuenta</a></span>
</form>`, failed ? 401 : 200)
}

function signupPage(error: string | null, next = '/', name = ''): Response {
  return page('Crear cuenta', `<form method="post" action="/__signup">
<input type="hidden" name="next" value="${escapeAttr(next)}">
<div class="brand">Retro<span>Stick</span></div>
<p>Crea tu cuenta. Luego conectarás tu propio Google Drive: tus juegos y partidas van allí.</p>
${error ? `<div class="error">${escapeAttr(error)}</div>` : ''}
<label for="code">Código de invitación</label>
<input id="code" name="code" autocapitalize="characters" autocorrect="off" autocomplete="off" required>
<span class="hint">Te lo da quien te ha invitado.</span>
<label for="user">Usuario</label>
<input id="user" name="user" value="${escapeAttr(name)}" autocomplete="username" autocapitalize="none" autocorrect="off"
pattern="[A-Za-z0-9._\-]{2,32}" required>
<span class="hint">Sin espacios ni acentos: pepe, ana.g, luis_23…</span>
<label for="password">Contraseña</label>
<input id="password" name="password" type="password" autocomplete="new-password" minlength="8" required>
<span class="hint">8 caracteres o más.</span>
<button type="submit">Crear cuenta</button>
<span class="alt">¿Ya tienes cuenta? <a href="${escapeAttr(next)}">Entrar</a></span>
</form>`, error ? 400 : 200)
}
