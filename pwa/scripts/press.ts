/**
 * Press scores and localized names from Wikidata + English Wikipedia, for build-igdb.ts.
 *
 * For every game Wikidata lists on a console: its English and Spanish names and aliases (to match file
 * names in either language) and, from the "Video game reviews" table of its English Wikipedia article,
 * a press score: Metacritic, else GameRankings, else the average of at least two critics' scores.
 * A score tagged for another platform is ignored; an untagged one only counts when the game came out
 * on that console alone, so ports never borrow another version's reviews.
 */

const UA = { 'User-Agent': 'RetroStick/1.0 (personal retro game library; https://github.com/joseptame/retrostick)' }
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** Wikidata items of each console, and how its reviews tag that platform. */
export const PRESS_CONSOLES: Record<string, { qid: string; tags: RegExp }> = {
  gb: { qid: 'Q186437', tags: /^(gb|game ?boy|dmg)$/i },
  gbc: { qid: 'Q203992', tags: /^(gbc|game ?boy colou?r)$/i },
  gba: { qid: 'Q188642', tags: /^(gba|game ?boy advance)$/i },
  nes: { qid: 'Q172742', tags: /^(nes|famicom|fc|nintendo entertainment system)$/i },
  snes: { qid: 'Q183259', tags: /^(snes|sfc|super nes|super famicom|super nintendo( entertainment system)?)$/i },
  md: { qid: 'Q10676', tags: /^(md|gen|genesis|mega ?drive|sega genesis|sega mega drive)$/i },
  sms: { qid: 'Q209868', tags: /^(sms|master system|sega master system|mark iii)$/i },
  psx: { qid: 'Q10677', tags: /^(ps|ps1|psx|psone|ps one|playstation)$/i },
}

/** Any platform label that can prefix a score in a reviews table ("GBA: 85/100", "(PC) 88/100"). */
const PLATFORM_TAG =
  /^(gba|gbc|gb|game ?boy( advance| colou?r)?|dmg|nes|snes|sfc|famicom|fc|super famicom|super nes|super nintendo|n64|gc|ngc|gcn|gamecube|ds|nds|3ds|wii|wiiu|switch|ns|ps|ps1|ps2|ps3|ps4|ps5|psx|psp|ps one|psone|playstation( \d)?|vita|pc|win|windows|mac|xbox|xb|x360|xbox 360|ios|android|mobile|mob|arcade|arc|gen|genesis|md|smd|saturn|sat|dc|dreamcast|n-gage|ngage|gg|sms|tg16|pce|neo ?geo|3do|jag|lynx|cd|scd|32x|amiga|c64|zx|msx|dos)$/i

/** Review fields that are not scores. */
const NOT_SCORES = new Set(['title', 'state', 'platforms', 'na', 'award', 'awards', 'subtitle', 'width', 'align', 'noagg', 'qualifier', 'edition', 'collapsed', 'show', 'rev', 'revscore', 'aggregator', 'reviews'])

export interface PressEntry {
  names: string[]
  /** 0..10 */
  score: number
}

type Row = { g: { value: string }; en?: { value: string }; es?: { value: string }; article?: { value: string }; aliases?: { value: string }; np: { value: string } }

async function sparql(query: string): Promise<Row[]> {
  const res = await fetch('https://query.wikidata.org/sparql?format=json&query=' + encodeURIComponent(query), { headers: UA })
  if (!res.ok) throw new Error(`wikidata ${res.status}`)
  return (await res.json()).results.bindings
}

/**
 * Scores in 0..1 from a review value: "85/100", "91.23%", "{{Rating|4|5}}", "8.5/10"… A panel such as
 * Famitsu's "7/10, 7/10, 8/10, 7/10" gives one score per reviewer.
 */
function parseScore(s: string): number[] {
  const rating = /\{\{\s*rating\s*\|\s*([\d.]+)\s*\|\s*([\d.]+)/i.exec(s)
  if (rating) return [Number(rating[1]) / Number(rating[2])]
  const fracs = [...s.matchAll(/(\d+(?:\.\d+)?)\s*\/\s*(\d+(?:\.\d+)?)/g)].filter((m) => Number(m[2]) > 0)
  if (fracs.length) {
    const panel = fracs.filter((m) => m[2] === fracs[0][2])
    return (panel.length > 1 ? panel : [fracs[0]]).map((m) => Number(m[1]) / Number(m[2]))
  }
  const pct = /(\d+(?:\.\d+)?)\s*%/.exec(s)
  if (pct) return [Number(pct[1]) / 100]
  return []
}

/** The scores of one review field for this console, honouring platform tags. */
function fieldScore(value: string, tags: RegExp, singlePlatform: boolean): number[] {
  const clean = value
    .replace(/<ref[^>]*\/>/gi, '')
    .replace(/<ref[\s\S]*?<\/ref>/gi, '')
    .replace(/'''?/g, '')
    .replace(/\[\[(?:[^|\]]*\|)?([^\]]*)\]\]/g, '$1')
  // List templates ("{{Unbulleted list center | ''Red'': 88% | ''Blue'': 88% }}") become one part per item.
  const parts = clean
    .replace(/\{\{\s*(?:plainlist|ubl|unbulleted list(?: center)?|flatlist)\s*\|/gi, '|')
    .replace(/\}\}\s*$/, '')
    .split(/<br\s*\/?>|\n|;|\||\*/i)
    .map((p) => p.trim())
    .filter(Boolean)
  let untagged: number[] = []
  for (const part of parts) {
    const m = /^\(?\s*([A-Za-z][A-Za-z0-9 .-]{0,22}?)\s*\)?\s*[:)]\s*(.*)$/.exec(part) ?? /^\(([^)]+)\)\s*(.*)$/.exec(part)
    const tag = m?.[1]?.trim()
    if (tag && PLATFORM_TAG.test(tag)) {
      if (tags.test(tag)) {
        const v = parseScore(m![2])
        if (v.length) return v
      }
      continue
    }
    const v = parseScore(part)
    if (v.length && !untagged.length) untagged = v
  }
  return singlePlatform ? untagged : []
}

/** Body of the article's {{Video game reviews …}} template (balanced braces), or null. */
function reviewsTemplate(wikitext: string): string | null {
  const m = /\{\{\s*Video game reviews/i.exec(wikitext)
  if (!m) return null
  let depth = 0
  for (let i = m.index; i < wikitext.length - 1; i++) {
    const two = wikitext.slice(i, i + 2)
    if (two === '{{') {
      depth++
      i++
    } else if (two === '}}') {
      depth--
      i++
      if (depth === 0) return wikitext.slice(m.index + m[0].length, i - 1)
    }
  }
  return null
}

/** Top-level "key = value" fields of a template body: values may span lines and hold nested templates. */
function templateFields(body: string): [string, string][] {
  const out: [string, string][] = []
  let depth = 0
  let start = -1
  const push = (end: number) => {
    if (start < 0) return
    const seg = body.slice(start, end)
    const eq = seg.indexOf('=')
    if (eq > 0) out.push([seg.slice(0, eq).trim(), seg.slice(eq + 1).trim()])
  }
  for (let i = 0; i < body.length; i++) {
    const two = body.slice(i, i + 2)
    if (two === '{{' || two === '[[') {
      depth++
      i++
    } else if (two === '}}' || two === ']]') {
      depth--
      i++
    } else if (body[i] === '|' && depth === 0) {
      push(i)
      start = i + 1
    }
  }
  push(body.length)
  return out
}

/** First platform of the article's infobox: the game's original one (later ports and re-releases follow). */
function primaryPlatform(wikitext: string): string | null {
  const field = /\|\s*platforms?\s*=\s*([^\n]*)/i.exec(wikitext)?.[1]
  if (!field) return null
  const link = /\[\[(?:[^|\]]*\|)?([^\]]+)\]\]/.exec(field)?.[1]
  return (link ?? field.split(/[,<{]/)[0]).trim() || null
}

/** Press score 0..10 from a "Video game reviews" table, or null. */
function pressScore(wikitext: string, tags: RegExp, onlyThisConsole: boolean): number | null {
  const box = reviewsTemplate(wikitext)
  if (!box) return null
  // Untagged scores belong to the original release: accept them when it is this console.
  const primary = primaryPlatform(wikitext)
  const singlePlatform = onlyThisConsole || (primary != null && tags.test(primary))
  // Multi-platform tables name the platform in the field ("MC_GBA = 80/100", "IGN_PS2 = 3.5/10"):
  // such a field counts only for its own console; plain fields follow the tag rules of fieldScore.
  const scores = new Map<string, number[]>()
  for (const [key, value] of templateFields(box.replace(/<!--[\s\S]*?-->/g, ''))) {
    if (!/^[A-Za-z0-9_ ]+$/.test(key)) continue
    const suffix = /^(.+?)_([A-Za-z0-9]+)$/.exec(key)
    let base = key
    let found: number[]
    if (suffix && PLATFORM_TAG.test(suffix[2])) {
      if (!tags.test(suffix[2])) continue
      base = suffix[1]
      found = fieldScore(value, tags, true)
    } else found = fieldScore(value, tags, singlePlatform)
    if (NOT_SCORES.has(base.toLowerCase()) || /^(rev|award|cat)\d*$/i.test(base)) continue
    const valid = found.filter((x) => x > 0 && x <= 1)
    if (valid.length) scores.set(base, [...(scores.get(base) ?? []), ...valid])
  }
  for (const key of ['MC', 'GR']) {
    const s = scores.get(key)?.[0]
    if (s != null) return Math.round(s * 100) / 10
  }
  // Average of the critics; a Famitsu-style panel counts each reviewer.
  const critics: number[] = []
  for (const [k, v] of scores) if (k !== 'MC' && k !== 'GR') critics.push(...v)
  if (critics.length < 2) return null
  return Math.round((critics.reduce((a, b) => a + b, 0) / critics.length) * 100) / 10
}

/** Names (en/es/aliases) and press score of every game of a console. Games without a score keep their names. */
export async function pressFor(consoleId: string): Promise<{ games: PressEntry[]; noScore: string[][]; articles: Set<string> }> {
  const { qid, tags } = PRESS_CONSOLES[consoleId]
  const rows = await sparql(`SELECT ?g (SAMPLE(?en_) AS ?en) (SAMPLE(?es_) AS ?es) (SAMPLE(?article_) AS ?article)
      (GROUP_CONCAT(DISTINCT ?alias; separator="|") AS ?aliases) (COUNT(DISTINCT ?p) AS ?np) WHERE {
    ?g wdt:P400 wd:${qid} . ?g wdt:P400 ?p .
    OPTIONAL { ?g rdfs:label ?en_ FILTER(lang(?en_) = "en") }
    OPTIONAL { ?g rdfs:label ?es_ FILTER(lang(?es_) = "es") }
    OPTIONAL { ?g skos:altLabel ?alias FILTER(lang(?alias) IN ("en", "es")) }
    OPTIONAL { ?article_ schema:about ?g ; schema:isPartOf <https://en.wikipedia.org/> }
  } GROUP BY ?g`)

  const items = rows.map((r) => {
    const article = r.article ? decodeURIComponent(r.article.value.split('/wiki/')[1]).replace(/_/g, ' ') : null
    // Many items lack an English label: the English article title ("Foo (video game)") always names the game.
    const fromArticle = article?.replace(/\s*\([^)]*\)\s*$/, '') ?? null
    const names = [r.en?.value ?? fromArticle, r.es?.value, ...(r.aliases?.value ? r.aliases.value.split('|') : []), fromArticle]
    return {
      names: [...new Set(names.filter((x): x is string => !!x && !/^Q\d+$/.test(x)))],
      article,
      single: Number(r.np.value) === 1,
    }
  })

  const titles = [...new Set(items.map((i) => i.article).filter((x): x is string => !!x))]
  const text = await wikitexts(titles)

  const games: PressEntry[] = []
  const noScore: string[][] = []
  for (const it of items) {
    const score = it.article ? pressScore(text.get(it.article) ?? '', tags, it.single) : null
    if (score != null) games.push({ names: it.names, score })
    else if (it.names.length) noScore.push(it.names)
  }
  return { games, noScore, articles: new Set(titles) }
}

/** English Wikipedia wikitext of each title, 50 per request (following redirects). */
export async function wikitexts(titles: string[]): Promise<Map<string, string>> {
  const text = new Map<string, string>()
  for (let i = 0; i < titles.length; i += 50) {
    const batch = titles.slice(i, i + 50)
    const url =
      'https://en.wikipedia.org/w/api.php?action=query&prop=revisions&rvprop=content&rvslots=main&format=json&formatversion=2&redirects=1&titles=' +
      encodeURIComponent(batch.join('|'))
    const j = await (await fetch(url, { headers: UA })).json()
    const back = new Map<string, string>()
    for (const r of [...(j.query?.normalized ?? []), ...(j.query?.redirects ?? [])]) back.set(r.to, r.from)
    for (const p of j.query?.pages ?? []) {
      let title: string = p.title
      while (back.has(title) && !batch.includes(title)) title = back.get(title)!
      text.set(title, p.revisions?.[0]?.slots?.main?.content ?? '')
    }
    await sleep(250)
  }
  return text
}

/** Press score 0..10 of an article for a console (same platform rules as pressFor). */
export function articleScore(wikitext: string, consoleId: string, onlyThisConsole: boolean): number | null {
  return pressScore(wikitext, PRESS_CONSOLES[consoleId].tags, onlyThisConsole)
}
