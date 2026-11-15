/**
 * Builds public/db/trans-<console>.json: the Spanish fan translations archive.org's «Compilaciones Traducciones al
 * Español (2024/08)» keeps for each console (one zip per translated ROM, GoodTools names: «Chrono Trigger (U)
 * [T+Spa100%_Gogito].zip»). The stick lists a game's translations in its Detalles (Translations.kt) by the title key
 * and downloads the chosen zip from archive.org.
 *
 *   npx tsx scripts/build-translations.ts
 *
 * Output: { generated, item, entries: [{ k, f, s, c, by, v, r }] }
 *   k  title key (the stick's looseKey(displayTitle(name)))   f  file in the item   s  size
 *   c  complete (GoodTools «T+»; «T-» = old or partial)       by translator          v  version   r  region letters
 *   rev  revision of the game it was made on («(1.1)» → 1), absent for the first release
 *   x  what follows the tag («25th Aniversary», «FluteWarp+PotsDestroy Addons»), if anything
 */
import { writeFileSync } from 'node:fs'
import { displayTitle } from '../src/lib/names.ts'

const ITEM = 'compilaciones-traducciones-al-espanol-2024-08'
const PREFIX = 'Compilación Traducciones Español '
const FOLDERS: Record<string, string> = {
  'Nintendo Entertainment System': 'nes',
  'Super Nintendo': 'snes',
  'Nintendo Game Boy': 'gb',
  'Nintendo Game Boy Color': 'gbc',
  'Sega Mega Drive': 'md',
  'Sega Master System': 'sms',
}

/** The stick's looseKey (Shop.kt): lower case, no accents, letters and digits only. */
const key = (s: string) =>
  s.normalize('NFD').toLowerCase().replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]/g, '')

interface Entry { k: string; f: string; s: number; c: boolean; by: string; v: string; r: string; rev?: number; x?: string; in?: string }

/**
 * Consoles from compilations of their own, one .7z per translated ROM at the item's root: archive.org serves the ROM
 * inside a .7z by itself, so «in» names it and the stick downloads just that (no unpacking on the stick).
 */
const SEVEN_ZIP_ITEMS: Record<string, { item: string; rom: RegExp }> = {
  n64: { item: 'n64-compilacion-de-traducciones-en-espanol_202609', rom: /\.(z64|n64|v64)$/i },
  gba: { item: 'gba-compilacion-de-traducciones-en-espanol_202609', rom: /\.gba$/i },
}

const res = await fetch(`https://archive.org/metadata/${ITEM}/files`)
if (!res.ok) throw new Error(`archive.org ${res.status}`)
const files = ((await res.json()) as { result: { name: string; size?: string }[] }).result
const out: Record<string, Entry[]> = Object.fromEntries(Object.values(FOLDERS).map((c) => [c, []]))

for (const f of files) {
  const slash = f.name.indexOf('/')
  if (slash < 0 || !/\.zip$/i.test(f.name)) continue
  const consoleId = FOLDERS[f.name.slice(0, slash).replace(PREFIX, '')]
  if (!consoleId) continue
  const name = f.name.slice(slash + 1).replace(/\.zip$/i, '')
  const entry = parse(name, f.name, Number(f.size ?? 0))
  if (entry) out[consoleId].push(entry)
}

/** An entry from a GoodTools name with a «T+Spa» / «T-Spa» tag, or null. */
function parse(name: string, file: string, size: number): Entry | null {
  // [T+Spa100%_Gogito], [T-Spa1.0v_Wave], (T-Spa Magno 101), (T-Spa jackic 1.0)
  const tag = /[[(]T([+-])Spa([^\])]*)[\])]/i.exec(name)
  if (!tag) return null
  const rest = tag[2].trim()
  let by: string
  let v: string
  if (tag[0].startsWith('[')) {
    // [T+Spa1.0_Wave], [T+Spa100%_Gogito]: version, then the translator after the last «_»
    by = rest.includes('_') ? rest.slice(rest.lastIndexOf('_') + 1).trim() : rest
    v = rest.includes('_') ? rest.slice(0, rest.lastIndexOf('_')).trim() : ''
  } else {
    // (T-Spa javs_l10n 1.1), (T-Spa Magno 101), (T-Spa D_Skywalk 1.11h): translator, then the version
    const words = rest.split(/\s+/)
    const last = words.length > 1 && /^v?\d[\w.]*$/.test(words[words.length - 1]) ? words.pop()! : ''
    by = words.join(' ').replace(/_/g, ' ')
    v = last
  }
  // GoodTools revision of the game it was made on: «(1.1)» → 1; none → 0 (No-Intro: «(Rev 1)»)
  const revM = /\((?:V|Rev\s*)?1\.(\d)\)|\(Rev\s*(\d)\)/i.exec(name.slice(0, tag.index))
  const rev = revM ? Number(revM[1] ?? revM[2]) : 0
  const region = /\(([A-Z]{1,4})\)/.exec(name)?.[1] ?? ''
  const extra = name.slice(tag.index + tag[0].length).match(/[[(]([^\])]+)[\])]/g)?.map((g) => g.slice(1, -1)).join(', ')
  return { k: key(displayTitle(name)), f: file, s: size, c: tag[1] === '+', by: by || 'Anónimo', v, r: region, ...(rev ? { rev } : {}), ...(extra ? { x: extra } : {}) }
}

const items: Record<string, string> = Object.fromEntries(Object.keys(out).map((c) => [c, ITEM]))
for (const [consoleId, { item, rom }] of Object.entries(SEVEN_ZIP_ITEMS)) {
  const r = await fetch(`https://archive.org/metadata/${item}/files`)
  if (!r.ok) throw new Error(`archive.org ${r.status} (${item})`)
  const list = ((await r.json()) as { result: { name: string; size?: string }[] }).result.filter((f) => /\.7z$/i.test(f.name))
  out[consoleId] = []
  items[consoleId] = item
  for (const f of list) {
    const name = f.name.replace(/\.7z$/i, '')
    const entry = parse(name, f.name, Number(f.size ?? 0))
    if (!entry) continue
    // The ROM inside, from archive.org's listing of the .7z
    const page = await (await fetch(`https://archive.org/download/${item}/${encodeURIComponent(f.name)}/`)).text()
    const inner = [...page.matchAll(/<tr><td><a\b[^>]*>([^<]*)<\/a>.*?<td id="size">(\d+)/g)]
      .map((m) => ({ name: m[1].replace(/&amp;/g, '&').replace(/&#0?39;/g, "'"), size: Number(m[2]) })).find((x) => rom.test(x.name))
    if (!inner) { process.stdout.write(`  ${consoleId}: sin ROM dentro de ${f.name}\n`); continue }
    out[consoleId].push({ ...entry, s: inner.size, in: inner.name })
  }
}

for (const [id, entries] of Object.entries(out)) {
  entries.sort((a, b) => a.k.localeCompare(b.k) || Number(b.c) - Number(a.c))
  writeFileSync(`public/db/trans-${id}.json`, JSON.stringify({ generated: new Date().toISOString(), item: items[id], entries }))
  process.stdout.write(`${id}: ${entries.length} traducciones\n`)
}
