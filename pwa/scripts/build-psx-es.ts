/**
 * Builds public/db/psx-es.json: the PlayStation games in Spanish RetroShop uses (api/shop.ts, src/lib/psx-es.ts),
 * read once from archive.org — both lists are old games and don't change.
 *
 *   npx tsx scripts/build-psx-es.ts
 *
 * Output: { generated, es: [{ t, r, d }], tr: [{ t, r, d }] }
 *   es  «[PS1] Recopilatorio de Juegos Localizados al Español»: one release per title (Spain, then Europe, then
 *       the rest; the newest revision), d = its discs' zips in order [file, size]
 *   tr  «PSX - Compilación de Traducciones al Español», the .chd ones: one per title (Europe, then USA, then Japan)
 *   t   title (no tags)   r   release name (no extension, no «(Disc n)»)
 */
import { writeFileSync } from 'node:fs'
import { ES_ITEM, TR_ITEM } from '../src/lib/psx-es.ts'

const UA = 'RetroStick/1.0 (+https://retrostick.vercel.app)'
const DISC = /\s*\(Disc (\d+)\)/i
const tags = (n: string) => n.replace(/\s*\([^)]*\)|\s*\[[^\]]*\]/g, '').trim()
const discNo = (n: string) => Number(n.match(DISC)?.[1] ?? 1)

async function itemFiles(item: string): Promise<{ name: string; size: number }[]> {
  const res = await fetch(`https://archive.org/metadata/${item}/files`, { headers: { 'User-Agent': UA } })
  if (!res.ok) throw new Error(`archive.org respondió ${res.status} para ${item}`)
  const json = (await res.json()) as { result?: { name: string; size?: string; source?: string }[] }
  return (json.result ?? []).filter((f) => f.source === 'original').map((f) => ({ name: f.name, size: Number(f.size ?? 0) }))
}

/** One release per title, [rank] lowest first, with its discs in order. */
function releases(files: { name: string; size: number }[], ext: RegExp, rank: (release: string) => number) {
  const byTitle = new Map<string, Map<string, { name: string; size: number }[]>>()
  for (const f of files.filter((f) => ext.test(f.name))) {
    const base = f.name.replace(ext, '')
    const title = tags(base)
    const release = base.replace(DISC, '')
    const rels = byTitle.get(title) ?? new Map()
    rels.set(release, [...(rels.get(release) ?? []), f])
    byTitle.set(title, rels)
  }
  return [...byTitle].map(([t, rels]) => {
    const [r, discs] = [...rels].sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))[0]
    const d = discs.sort((a, b) => discNo(a.name) - discNo(b.name)).map((f) => [f.name, f.size] as [string, number])
    return { t, r, d }
  })
}

const rev = (r: string) => Number(r.match(/\(Rev (\d+)\)/i)?.[1] ?? 0)
const es = releases(await itemFiles(ES_ITEM), /\.zip$/i, (r) => (/\(Spain/.test(r) ? 0 : /\(Europe/.test(r) ? 100 : 200) - rev(r))
  // Only releases that say they're in Spanish (Redump: «(Spain)» or «Es» in the languages); one USA one in English slipped in
  .filter((e) => /\(Spain/.test(e.r) || /\((?:[^)]*,)?Es(?:,[^)]*)?\)/.test(e.r))
const tr = releases(await itemFiles(TR_ITEM), /\.chd$/i, (r) => (/\((E|Europe)\)/.test(r) ? 0 : /\((U|USA|UE)\)/.test(r) ? 1 : 2))
writeFileSync('public/db/psx-es.json', JSON.stringify({ generated: new Date().toISOString().slice(0, 10), es, tr }))
console.log(`psx-es.json: ${es.length} en español, ${tr.length} traducciones`)
