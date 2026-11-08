/**
 * Local stand-in for Vercel (dev server only): runs `middleware.ts` and the `api/*.ts` edge functions
 * inside Vite, so `npm run dev` behaves like production — login gate included.
 * `vercel dev` can't be used for this: its middleware never receives POST bodies, so the login form fails.
 * Variables are read from `.env.local` on every request (AUTH_USER, AUTH_PASSWORD, AUTH_SECRET,
 * IGDB_CLIENT_ID, IGDB_CLIENT_SECRET), so editing that file needs no restart.
 */
import { loadEnv, type Plugin } from 'vite'

declare const process: { env: Record<string, string | undefined> }

/** Same exclusions as `config.matcher` in middleware.ts. */
const PUBLIC = /^\/(manifest\.webmanifest|icon-192\.png|icon-512\.png|apple-touch-icon\.png|api\/qr)$/
const API = /^\/api\/([\w-]+)$/
const HANDLERS = new Set(['igdb', 'download', 'transfer', 'shop', 'pair', 'qr'])

/* Minimal shapes of Node's IncomingMessage / ServerResponse (no @types/node in this project). */
interface Req extends AsyncIterable<Uint8Array> {
  url?: string
  method?: string
  headers: Record<string, string | string[] | undefined>
}
interface Res {
  statusCode: number
  setHeader(name: string, value: string | string[]): void
  write(chunk: Uint8Array): boolean
  end(): void
}
type Handler = (request: Request) => Promise<Response>

async function toRequest(req: Req, url: URL): Promise<Request> {
  const headers = new Headers()
  for (const [k, v] of Object.entries(req.headers)) {
    if (v == null || k.startsWith(':')) continue
    for (const x of Array.isArray(v) ? v : [v]) headers.append(k, x)
  }
  const method = req.method ?? 'GET'
  let body: Uint8Array | undefined
  if (method !== 'GET' && method !== 'HEAD') {
    const chunks: Uint8Array[] = []
    for await (const c of req) chunks.push(c)
    body = new Uint8Array(chunks.reduce((n, c) => n + c.length, 0))
    let off = 0
    for (const c of chunks) {
      body.set(c, off)
      off += c.length
    }
  }
  return new Request(url, { method, headers, body })
}

async function send(r: Response, res: Res) {
  res.statusCode = r.status
  r.headers.forEach((v, k) => k !== 'set-cookie' && res.setHeader(k, v))
  const cookies = r.headers.getSetCookie()
  if (cookies.length) res.setHeader('set-cookie', cookies)
  if (r.body) {
    const reader = r.body.getReader()
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      res.write(value)
    }
  }
  res.end()
}

export function vercelDev(): Plugin {
  return {
    name: 'retrostick-vercel-dev',
    apply: 'serve',
    configureServer(server) {
      // Environment the process started with, before any .env file was applied. Kept on globalThis
      // because Vite restarts the server (re-running this) in the same process when a .env file changes.
      const g = globalThis as { __retrostickBaseEnv?: Record<string, string | undefined> }
      const base = (g.__retrostickBaseEnv ??= { ...process.env })
      server.middlewares.use(async (rawReq, rawRes, next) => {
        const req = rawReq as unknown as Req
        const res = rawRes as unknown as Res
        try {
          // Back to the startup environment first: with an empty prefix loadEnv also returns process.env,
          // so a variable set on a previous request would otherwise stick after leaving the file.
          for (const k of Object.keys(process.env)) if (!(k in base)) delete process.env[k]
          Object.assign(process.env, base)
          Object.assign(process.env, loadEnv(server.config.mode, server.config.envDir || server.config.root, ''))
          const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
          const request = await toRequest(req, url)

          if (!PUBLIC.test(url.pathname)) {
            const mw = (await server.ssrLoadModule('/middleware.ts')) as { default: Handler }
            const r = await mw.default(request.clone())
            if (r.headers.get('x-middleware-next') !== '1') return await send(r, res)
          }

          const api = API.exec(url.pathname)
          if (api && HANDLERS.has(api[1])) {
            const mod = (await server.ssrLoadModule(`/api/${api[1]}.ts`)) as { default: Handler }
            return await send(await mod.default(request), res)
          }
          next()
        } catch (e) {
          next(e)
        }
      })
    },
  }
}
