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

interface Entry { k: string; f: string; s: number; c: boolean; by: string; v: string; r: string; x?: string }

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
  // [T+Spa100%_Gogito], [T-Spa1.0v_Wave], (T-Spa Magno 101), (T-Spa jackic 1.0)
  const tag = /[[(]T([+-])Spa([^\])]*)[\])]/i.exec(name)
  if (!tag) continue
  const rest = tag[2].trim()
  let by: string
  let v: string
  if (rest.includes('_')) {
    by = rest.slice(rest.lastIndexOf('_') + 1).trim()
    v = rest.slice(0, rest.lastIndexOf('_')).trim()
  } else {
    const words = rest.split(/\s+/)
    by = words.filter((w) => !/^v?\d/.test(w)).join(' ')
    v = words.filter((w) => /^v?\d/.test(w)).join(' ')
  }
  const region = /\(([A-Z]{1,4})\)/.exec(name)?.[1] ?? ''
  const extra = name.slice(tag.index + tag[0].length).match(/[[(]([^\])]+)[\])]/g)?.map((g) => g.slice(1, -1)).join(', ')
  out[consoleId].push({ k: key(displayTitle(name)), f: f.name, s: Number(f.size ?? 0), c: tag[1] === '+', by: by || 'Anónimo', v, r: region, ...(extra ? { x: extra } : {}) })
}

for (const [id, entries] of Object.entries(out)) {
  entries.sort((a, b) => a.k.localeCompare(b.k) || Number(b.c) - Number(a.c))
  writeFileSync(`public/db/trans-${id}.json`, JSON.stringify({ generated: new Date().toISOString(), item: ITEM, entries }))
  process.stdout.write(`${id}: ${entries.length} traducciones\n`)
}
