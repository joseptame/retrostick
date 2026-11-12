/**
 * Vercel Routing Middleware: the whole app is behind a login checked on the server.
 * Credentials live in Vercel environment variables (AUTH_USER, AUTH_PASSWORD, AUTH_SECRET), never in the repo.
 * A successful login sets a signed, HttpOnly cookie for one year; /__logout removes it.
 * The stick (RetroShop) has no cookie: it sends a pass issued by api/pair.ts once paired with a QR
 * (x-stick-token: "rs1.<id>.<HMAC>" for the admin's stick, "rs2.<user>.<id>.<HMAC>" for a user's; see api/_users.ts).
 * Besides the admin, users from the database (api/_users.ts, Upstash): each with their own password, created by the
 * admin in the app (Stick → Cuenta); deleting a user ends their sessions and their stick's pass.
 */
export const config = {
  // The install metadata stays public: phones fetch the manifest and icons without cookies. So does the
  // pairing QR the stick shows (api/qr.ts only encodes this site's own pairing link).
  matcher: '/((?!manifest\\.webmanifest|icon-192\\.png|icon-512\\.png|apple-touch-icon\\.png|api/qr$).*)',
}

import {
  adminCookie, checkPassword, COOKIE, getUser, lockedOut, loginFailed, normalizeName, setCookie, userCookie, whoFromCookie,
  whoFromStickToken, dbConfigured, same,
} from './api/_users'

declare const process: { env: Record<string, string | undefined> }

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

  if (url.pathname === '/__login' && request.method === 'POST') {
    const form = await request.formData()
    const next = String(form.get('next') ?? '/')
    const ip = clientIp(request)
    // Too many failed attempts from this connection: locked out for 15 minutes
    if (await lockedOut(ip)) return loginPage(false, next, true)
    const name = String(form.get('user') ?? '')
    const pass_ = String(form.get('password') ?? '')
    // Back to the page that asked for the login (a pairing link keeps its ?vincular=…).
    const go = (cookieValue: string) => new Response(null, {
      status: 303,
      headers: { location: next.startsWith('/') && !next.startsWith('//') ? next : '/', 'set-cookie': setCookie(cookieValue) },
    })
    // The admin (Vercel's AUTH_USER / AUTH_PASSWORD), as before
    if ((await same(name, user, secret)) && (await same(pass_, password, secret))) return go(await adminCookie(secret, user))
    // A user from the database
    const key = normalizeName(name)
    if (dbConfigured() && key) {
      const stored = await getUser(key, true).catch(() => null)
      if (stored && (await checkPassword(stored, pass_))) return go(await userCookie(stored, secret))
    }
    await loginFailed(ip)
    return loginPage(true, next)
  }

  if (await whoFromCookie(request)) return pass()
  if (await whoFromStickToken(request.headers.get('x-stick-token'))) return pass()
  return loginPage(false, url.pathname + url.search)
}

const escapeAttr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;')

function loginPage(failed: boolean, next = '/', locked = false): Response {
  const html = `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0d0b1f">
<meta name="robots" content="noindex">
<link rel="manifest" href="/manifest.webmanifest">
<link rel="apple-touch-icon" href="/apple-touch-icon.png">
<title>RetroStick · Acceso</title>
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
</style>
</head>
<body>
<form method="post" action="/__login">
<input type="hidden" name="next" value="${escapeAttr(next)}">
<div class="brand">Retro<span>Stick</span></div>
<p>Acceso privado</p>
${locked ? '<div class="error">Demasiados intentos. Espera 15 minutos y vuelve a probar.</div>' : failed ? '<div class="error">Usuario o contraseña incorrectos.</div>' : ''}
<label for="user">Usuario</label>
<input id="user" name="user" autocomplete="username" autocapitalize="none" autocorrect="off" required>
<label for="password">Contraseña</label>
<input id="password" name="password" type="password" autocomplete="current-password" required>
<button type="submit">Entrar</button>
</form>
</body>
</html>`
  return new Response(html, {
    status: failed ? 401 : 200,
    headers: { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' },
  })
}
