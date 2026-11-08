/**
 * The pairing QR the stick shows in RetroShop, as a matrix it draws itself (no image library on the stick).
 * Public (excluded in middleware.ts), but it only ever encodes this site's own pairing link for a code.
 *
 *   GET /api/qr?code=ABCD2345  →  { size, rows: ["1010…", …], url }
 */
import qrcode from 'qrcode-generator'

export const config = { runtime: 'edge' }

export default function handler(request: Request): Response {
  const url = new URL(request.url)
  const code = url.searchParams.get('code') ?? ''
  if (!/^[A-Z0-9]{8}$/.test(code)) return new Response('Código inválido', { status: 400 })
  const link = `${url.origin}/?vincular=${code}`
  const qr = qrcode(0, 'M')
  qr.addData(link)
  qr.make()
  const size = qr.getModuleCount()
  const rows = Array.from({ length: size }, (_, r) => Array.from({ length: size }, (_, c) => (qr.isDark(r, c) ? '1' : '0')).join(''))
  return new Response(JSON.stringify({ size, rows, url: link }), {
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'public, max-age=86400' },
  })
}
