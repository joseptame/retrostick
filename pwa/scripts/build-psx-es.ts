/**
 * Builds public/db/psx-es.json: the PlayStation games in Spanish RetroShop uses (api/shop.ts, src/lib/psx-es.ts),
 * read once from archive.org — they're old games and the lists don't change.
 *
 *   npx tsx scripts/build-psx-es.ts
 *
 * Output: { generated, es: [{ t, r, src, d }], alt: [{ t, k, v?, r, n, d, z? }] }
 *
 *   es   one Spanish release per title from «[PS1] Recopilatorio de Juegos Localizados al Español» (Spain, then
 *        Europe; the newest revision; only those that say they're in Spanish), taken as CHD wherever the very same
 *        disc is one somewhere: src «eur» RetroShop's European collection (d = its paths), «lc» the LibCrypt CHD set
 *        (protected games, which need it), «tr» the translations item; else «zip» the compilation's own zips
 *        (a .cue and its .bin each). d = [file, size] per disc.
 *   alt  the fan versions from «PSX - Compilación de Traducciones al Español», the best one per title and kind:
 *        k «voz» voices and texts (fandub), «txt» texts only (undub too), «mej» a Spanish release with its texts
 *        corrected («Retraducción» of an «(S)» one; v: its voices redone too). n discs; d = .chd files, or with
 *        z = 1 zips holding them. Latin American dubs, hacks and texture packs are left out.
 */
import { writeFileSync } from 'node:fs'
import { ES_ITEM, LC_ITEM, TR_ITEM } from '../src/lib/psx-es.ts'

const UA = 'RetroStick/1.0 (+https://retrostick.vercel.app)'
const EUR_ITEM = 'chd_psx_eur'
const DISC = /\s*\(Disc[o]? (\d+)[^)]*\)/i
const tags = (n: string) => n.replace(/\s*\([^)]*\)|\s*\[[^\]]*\]/g, '').trim()
const discNo = (n: string) => Number(n.match(DISC)?.[1] ?? 1)
const fileName = (p: string) => p.slice(p.lastIndexOf('/') + 1)
const decode = (s: string) => s.replace(/&amp;/g, '&').replace(/&#0?39;/g, "'").replace(/&quot;/g, '"')

type File = { name: string; size: number }

async function itemFiles(item: string): Promise<File[]> {
  const res = await fetch(`https://archive.org/metadata/${item}/files`, { headers: { 'User-Agent': UA } })
  if (!res.ok) throw new Error(`archive.org respondió ${res.status} para ${item}`)
  const json = (await res.json()) as { result?: { name: string; size?: string; source?: string }[] }
  return (json.result ?? []).filter((f) => f.source === 'original').map((f) => ({ name: f.name, size: Number(f.size ?? 0) }))
}

/** The files inside a zip on archive.org (its listing page). */
async function zipFiles(item: string, zip: string): Promise<File[]> {
  const url = `https://archive.org/download/${item}/${zip.split('/').map(encodeURIComponent).join('/')}/`
  const html = await (await fetch(url, { headers: { 'User-Agent': UA } })).text()
  return [...html.matchAll(/<tr><td><a\b[^>]*>([^<]*)<\/a>.*?<td id="size">(\d+)/g)].map((m) => ({ name: decode(m[1]), size: Number(m[2]) }))
}

/** Releases grouped by title, each with its discs in order. */
function byTitle(files: File[], ext: RegExp) {
  const titles = new Map<string, Map<string, File[]>>()
  for (const f of files.filter((f) => ext.test(f.name))) {
    const base = fileName(f.name).replace(ext, '')
    const release = base.replace(DISC, '')
    const rels = titles.get(tags(base)) ?? new Map<string, File[]>()
    rels.set(release, [...(rels.get(release) ?? []), f])
    titles.set(tags(base), rels)
  }
  for (const rels of titles.values()) for (const discs of rels.values()) discs.sort((a, b) => discNo(fileName(a.name)) - discNo(fileName(b.name)))
  return titles
}

const [esFiles, eurFiles, lcFiles, trFiles] = await Promise.all([ES_ITEM, EUR_ITEM, LC_ITEM, TR_ITEM].map(itemFiles))

// ---- Spanish releases ----
const spanish = (r: string) => /\(Spain/.test(r) || /\((?:[^)]*,)?Es(?:,[^)]*)?\)/.test(r)
const rev = (r: string) => Number(r.match(/\(Rev (\d+)\)/i)?.[1] ?? 0)
const esRank = (r: string) => (/\(Spain/.test(r) ? 0 : /\(Europe/.test(r) ? 100 : 200) - rev(r)
const asChd = (zip: string) => fileName(zip).replace(/\.zip$/i, '.chd')
const where = (files: File[]) => new Map(files.filter((f) => /\.chd$/i.test(f.name)).map((f) => [fileName(f.name), f]))
const sources = [['eur', where(eurFiles)], ['lc', where(lcFiles)], ['tr', where(trFiles)]] as const

const es = [...byTitle(esFiles, /\.zip$/i)].flatMap(([t, rels]) => {
  const best = [...rels].filter(([r]) => spanish(r)).sort((a, b) => esRank(a[0]) - esRank(b[0]) || a[0].localeCompare(b[0]))[0]
  if (!best) return []
  const [r, zips] = best
  for (const [src, chds] of sources) {
    const found = zips.map((z) => chds.get(asChd(z.name)))
    if (found.every(Boolean)) return [{ t, r, src, d: found.map((f) => [f!.name, f!.size]) }]
  }
  return [{ t, r, src: 'zip', d: zips.map((z) => [z.name, z.size]) }]
})

// ---- Fan versions ----
const SKIP = /latin|hack|trainer|texturas|hardtype/i
const region = (r: string) => (/\((E|EA|Europe|W)\)/.test(r) ? 0 : /\((U|UE|UC|USA|NTSC-U)\)/.test(r) ? 1 : 2)
const kindOf = (r: string) => (/\((S|Spain)\)/.test(r) ? (/retradu/i.test(r) ? 'mej' : null) : /fandub/i.test(r) ? 'voz' : 'txt')
const candidates: { t: string; k: string; v?: 1; r: string; n: number; d: [string, number][]; z?: 1 }[] = []
for (const [t, rels] of byTitle(trFiles, /\.chd$/i)) {
  for (const [r, discs] of rels) {
    const k = kindOf(r)
    if (!k || SKIP.test(r) || !/T[-+]Spa|Retradu/i.test(r)) continue
    candidates.push({ t, k, ...(k === 'mej' && /fandub/i.test(r) ? { v: 1 as const } : {}), r, n: discs.length, d: discs.map((f) => [f.name, f.size]) })
  }
}
// Zips holding the .chd (one with every disc): looked inside
for (const f of trFiles.filter((f) => /\.zip$/i.test(f.name) && !SKIP.test(f.name))) {
  const r = f.name.replace(/\.zip$/i, '')
  const k = kindOf(r)
  if (!k || !/T[-+]Spa|Retradu/i.test(r)) continue
  const chds = (await zipFiles(TR_ITEM, f.name)).filter((x) => /\.chd$/i.test(x.name))
  if (!chds.length) continue
  candidates.push({ t: tags(r), k, ...(k === 'mej' && /fandub/i.test(r) ? { v: 1 as const } : {}), r, n: chds.length,
    d: [[f.name, chds.reduce((s, x) => s + x.size, 0)]], z: 1 })
}
const version = (r: string) => r.match(/(\d+(?:\.\d+)+)[a-z]?\)/i)?.[1] ?? '0'
const alt = [...Map.groupBy(candidates, (c) => `${c.t}\u0000${c.k}`).values()].map((list) =>
  list.sort((a, b) => region(a.r) - region(b.r) || version(b.r).localeCompare(version(a.r), undefined, { numeric: true }))[0])

writeFileSync('public/db/psx-es.json', JSON.stringify({ generated: new Date().toISOString().slice(0, 10), es, alt }))
const count = (src: string) => es.filter((e) => e.src === src).length
console.log(`psx-es.json: ${es.length} en español (CHD: ${count('eur')} colección, ${count('lc')} LibCrypt, ${count('tr')} traducciones; ${count('zip')} bin/cue), ${alt.length} versiones de fans`)
for (const k of ['voz', 'txt', 'mej']) console.log(`  ${k}: ${alt.filter((a) => a.k === k).map((a) => a.t).join(' | ')}`)
