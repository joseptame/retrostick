/**
 * Vercel Routing Middleware: the whole app is behind a login checked on the server.
 * Credentials live in Vercel environment variables (AUTH_USER, AUTH_PASSWORD, AUTH_SECRET), never in the repo.
 * A successful login sets a signed, HttpOnly cookie for one year; /__logout removes it.
 */
export const config = {
  // The install metadata stays public: phones fetch the manifest and icons without cookies.
  matcher: '/((?!manifest\\.webmanifest|icon-192\\.png|icon-512\\.png|apple-touch-icon\\.png).*)',
}

const COOKIE = 'rs_auth'
const YEAR = 365 * 24 * 3600

declare const process: { env: Record<string, string | undefined> }

async function sign(value: string, secret: string): Promise<string> {
  const key = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, ['sign'])
  const mac = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(value))
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** Compares HMACs of both strings, so the time taken doesn't reveal the password. */
async function same(a: string, b: string, secret: string): Promise<boolean> {
  const [x, y] = await Promise.all([sign(a, secret), sign(b, secret)])
  let diff = x.length ^ y.length
  for (let i = 0; i < Math.min(x.length, y.length); i++) diff |= x.charCodeAt(i) ^ y.charCodeAt(i)
  return diff === 0
}

function cookie(request: Request, name: string): string | null {
  const m = new RegExp(`(?:^|;\\s*)${name}=([^;]+)`).exec(request.headers.get('cookie') ?? '')
  return m ? decodeURIComponent(m[1]) : null
}

/** Equivalent of next() from @vercel/functions: let the request through. */
const pass = () => new Response(null, { headers: { 'x-middleware-next': '1' } })

export default async function middleware(request: Request): Promise<Response> {
  const { AUTH_USER: user, AUTH_PASSWORD: password, AUTH_SECRET: secret } = process.env
  if (!user || !password || !secret) {
    return new Response('Acceso no configurado.', { status: 503, headers: { 'content-type': 'text/plain; charset=utf-8' } })
  }
  const url = new URL(request.url)
  const token = await sign(`v1:${user}`, secret)

  if (url.pathname === '/__logout') {
    return new Response(null, {
      status: 303,
      headers: { location: '/', 'set-cookie': `${COOKIE}=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Lax` },
    })
  }

  if (url.pathname === '/__login' && request.method === 'POST') {
    const form = await request.formData()
    const okUser = await same(String(form.get('user') ?? ''), user, secret)
    const okPassword = await same(String(form.get('password') ?? ''), password, secret)
    if (okUser && okPassword) {
      return new Response(null, {
        status: 303,
        headers: { location: '/', 'set-cookie': `${COOKIE}=${token}; Path=/; Max-Age=${YEAR}; HttpOnly; Secure; SameSite=Lax` },
      })
    }
    return loginPage(true)
  }

  if (cookie(request, COOKIE) === token) return pass()
  // The stick (RetroShop) has no login cookie: it sends its own key (STICK_KEY, baked into the launcher).
  const stickKey = process.env.STICK_KEY
  const sent = request.headers.get('x-stick-key')
  if (stickKey && sent && (await same(sent, stickKey, secret))) return pass()
  return loginPage(false)
}

function loginPage(failed: boolean): Response {
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
<div class="brand">Retro<span>Stick</span></div>
<p>Acceso privado</p>
${failed ? '<div class="error">Usuario o contraseña incorrectos.</div>' : ''}
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
