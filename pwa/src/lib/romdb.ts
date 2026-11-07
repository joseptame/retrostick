/** Game identification against the No-Intro / Redump database built by scripts/build-db.ts. */
import { consoleById, type ConsoleId } from './consoles'
import { crc32, sha1 } from './hash'
import { baseName, bestBoxart, coverUrl, looseKey } from './names'

interface Db {
  names: string[]
  sha1: Record<string, number>
  crc: Record<string, number>
  boxarts: string[]
}

const cache = new Map<ConsoleId, Promise<Db>>()

export function loadDb(id: ConsoleId): Promise<Db> {
  let db = cache.get(id)
  if (!db) {
    db = fetch(`${import.meta.env.BASE_URL}db/${id}.json`).then((r) => {
      if (!r.ok) throw new Error(`db ${id}: ${r.status}`)
      return r.json()
    })
    db.catch(() => cache.delete(id))
    cache.set(id, db)
  }
  return db
}

export interface Identification {
  name: string
  /** sha1/crc = exact dump match; name = guessed from the file name */
  by: 'sha1' | 'crc' | 'name'
}

/**
 * The ROM as dumped and also without the header some files carry (the database hashes header-less
 * data): iNES 16 bytes on NES ("NES\x1A"), a 512-byte copier header on SNES (size 512 over a multiple of 1 KiB).
 */
export function withoutHeader(id: ConsoleId, data: Uint8Array): Uint8Array[] {
  if (id === 'nes' && data.length > 16 && data[0] === 0x4e && data[1] === 0x45 && data[2] === 0x53 && data[3] === 0x1a) {
    return [data, data.subarray(16)]
  }
  if (id === 'snes' && data.length % 1024 === 512) return [data, data.subarray(512)]
  return [data]
}

/** README 7.4: SHA-1 first, CRC32 as a fallback, then the file name. */
export async function identify(id: ConsoleId, fileName: string, data: Uint8Array | null): Promise<Identification | null> {
  const db = await loadDb(id)
  for (const d of data ? withoutHeader(id, data) : []) {
    const s = db.sha1[await sha1(d)]
    if (s !== undefined) return { name: db.names[s], by: 'sha1' }
    const c = db.crc[crc32(d)]
    if (c !== undefined) return { name: db.names[c], by: 'crc' }
  }
  const guess = guessByName(db.names, baseName(fileName))
  return guess ? { name: guess, by: 'name' } : null
}

/** Same title ignoring case/accents/punctuation; European, then shortest (fewest tags) name first. */
export function guessByName(names: string[], fileBase: string): string | null {
  const key = looseKey(fileBase)
  if (!key) return null
  const candidates = names.filter((n) => looseKey(n) === key && !/\((Beta|Proto|Demo|Sample)/i.test(n))
  const euro = (n: string) => (/\((?:[^)]*[ ,])?(Europe|Spain)[,)]/.test(n) ? 0 : 1)
  candidates.sort((a, b) => euro(a) - euro(b) || a.length - b.length)
  return candidates[0] ?? null
}

/** Cover URL with the European preference (README 7.5), or null when the repo has none. */
/**
 * Box art chosen by hand for games whose file name doesn't match libretro's (looseKey of the title →
 * console and box art name). European / Spanish art, as everywhere else.
 */
const COVER_OVERRIDES: Record<string, { console: ConsoleId; boxart: string }> = {
  pokemonamarillo: { console: 'gb', boxart: 'Pokemon - Edicion Amarilla - Edicion Especial Pikachu (Spain) (CGB+SGB Enhanced)' },
  asterixobelixvscaesar: { console: 'gbc', boxart: 'Asterix _ Obelix Contre Cesar (Europe) (En,Fr,De,Es,Nl) (GB Compatible)' },
  // GoodTools names of the gbc_20240311 set (archive.org) → their No-Intro box art
  asterixsearchfordogmatix: { console: 'gbc', boxart: "Asterix - Sur la Trace D'Idefix (Europe) (En,Fr,De,Es,It,Nl)" },
  bugsbunnylolabunnyoperationcarrots: {
    console: 'gbc',
    boxart: 'Bugs Bunny _ Lola Bunny - Operation Carrot Patch (Europe) (En,Fr,De,Es,It,Nl) (GB Compatible)',
  },
  carmageddon: { console: 'gbc', boxart: 'Carmageddon - Carpocalypse Now (USA, Europe) (En,Fr,Es,It)' },
  catzyourvirtualpetzpalz: { console: 'gbc', boxart: 'Catz (Europe)' },
  dogzyourvirtualpetzpalz: { console: 'gbc', boxart: 'Dogz (Europe)' },
  f1worldgrandprixii: { console: 'gbc', boxart: 'F1 World Grand Prix II for Game Boy Color (Europe) (En,Fr,De,Es)' },
  harleydavidsonraceacrossamerica: { console: 'gbc', boxart: 'Harley-Davidson Motor Cycles - Race Across America (USA)' },
  internationalkarate: { console: 'gbc', boxart: 'International Karate 2000 (Europe)' },
  kirbystiltntumble: { console: 'gbc', boxart: "Kirby - Tilt 'n' Tumble (USA)" },
  microsoftentertainmentpack: { console: 'gbc', boxart: 'Microsoft - The Best of Entertainment Pack (Europe)' },
  microsoftpuzzlecollection: { console: 'gbc', boxart: 'Microsoft - The 6 in 1 Puzzle Collection Entertainment Pack (Europe) (En,Fr,De,Es,It)' },
  newbatmanadventureschaosingotham: { console: 'gbc', boxart: 'Batman - Chaos in Gotham (Europe) (En,Fr,De,Es,It,Nl)' },
  oddworldadventuresii: { console: 'gbc', boxart: 'Oddworld Adventures 2 (Europe) (En,Fr,De,Es,It) (GB Compatible)' },
  questrpgbriansjourney: { console: 'gbc', boxart: "Quest - Brian's Journey (USA) (GB Compatible)" },
  rayman2thegreatescape: { console: 'gbc', boxart: 'Rayman 2 Forever (Europe) (En,Fr,De,Es,It)' },
  roadsters98: { console: 'gbc', boxart: 'Roadsters (Europe) (En,Fr,De,Es,It,Nl) (GB Compatible)' },
  roadsterstrophy: { console: 'gbc', boxart: 'Roadsters (Europe) (En,Fr,De,Es,It,Nl) (GB Compatible)' },
  spiroulapaniquemecanique: { console: 'gbc', boxart: 'Spirou - The Robot Invasion (Europe) (En,Fr,De,Es,It,Nl,Da)' },
  sylvesterandtweety: { console: 'gbc', boxart: 'Sylvester _ Tweety - Breakfast on the Run (Europe) (En,Fr,De,Es,It,Nl) (GB Compatible)' },
  tazmaniandevilmunchingmadness: { console: 'gbc', boxart: 'Tasmanian Devil - Munching Madness (Europe) (En,Fr,De,Es,It) (GB Compatible)' },
  yarsrevengethequotileultimatum: { console: 'gbc', boxart: "Yars' Revenge (USA, Europe) (GB Compatible)" },
  // GoodTools names of the gameboy_202401 set (archive.org) → their No-Intro box art
  '4in1funpakvolii': { console: 'gb', boxart: "4-in-1 Funpak - Volume II (USA, Europe)" },
  adventureislandii: { console: 'gb', boxart: "Adventure Island II - Aliens in Paradise (USA, Europe)" },
  adventuresofrockyandbullwinkle: { console: 'gb', boxart: "Adventures of Rocky and Bullwinkle and Friends, The (USA)" },
  alienolympics2044ad: { console: 'gb', boxart: "Alien Olympics (Europe)" },
  alteredspace: { console: 'gb', boxart: "Altered Space - A 3-D Alien Adventure (Europe)" },
  battletoadsdoubledragontheultimateteam: { console: 'gb', boxart: "Battletoads-Double Dragon (Europe)" },
  beethovens2nd: { console: 'gb', boxart: "Beethoven (Europe) (SGB Enhanced)" },
  berlitzfrenchlanguagetranslator: { console: 'gb', boxart: "InfoGenius Productivity Pak - Berlitz French Translator (USA, Europe)" },
  berlitzspanishlanguagetranslator: { console: 'gb', boxart: "InfoGenius Productivity Pak - Berlitz Spanish Translator (USA, Europe)" },
  billtedsexcellentgameboyadventure: { console: 'gb', boxart: "Bill _ Ted's Excellent Game Boy Adventure - A Bogus Journey! (USA, Europe)" },
  bojacksonhitandrun: { console: 'gb', boxart: "Bo Jackson - Two Games in One (USA)" },
  boxxle2: { console: 'gb', boxart: "Boxxle II (USA, Europe)" },
  aboyandhisblobtherescueofprincessblobette: { console: 'gb', boxart: "David Crane's The Rescue of Princess Blobette (Europe)" },
  bubsy2: { console: 'gb', boxart: "Bubsy II (Europe)" },
  bugsbunny: { console: 'gb', boxart: "Bugs Bunny Crazy Castle, The (USA, Europe)" },
  bugsbunnycrazycastleii: { console: 'gb', boxart: "Bugs Bunny Crazy Castle 2, The (USA)" },
  busterbrothers: { console: 'gb', boxart: "Buster Bros. (USA)" },
  castlevaniaadventure: { console: 'gb', boxart: "Castlevania - The Adventure (Europe)" },
  daedaleanopus: { console: 'gb', boxart: "Daedalian Opus (USA)" },
  daffyduckthemarvinmissions: { console: 'gb', boxart: "Daffy Duck (USA, Europe) (SGB Enhanced)" },
  disneysmulan: { console: 'gb', boxart: "Mulan (Europe) (SGB Enhanced)" },
  donaldduckinmauimallard: { console: 'gb', boxart: "Maui Mallard in Cold Shadow (USA)" },
  doubledragon2: { console: 'gb', boxart: "Double Dragon II (USA, Europe)" },
  doubledragon3: { console: 'gb', boxart: "Double Dragon 3 - The Arcade Game (USA, Europe)" },
  frommerspersonalorganizer: { console: 'gb', boxart: "InfoGenius Productivity Pak - Personal Organizer and Phone Book (USA)" },
  gargoylesquestghostsngoblins: { console: 'gb', boxart: "Gargoyle's Quest (USA, Europe)" },
  getaway: { console: 'gb', boxart: "Getaway, The - High Speed II (USA)" },
  hammerinharryghostbuildingcompany: { console: 'gb', boxart: "Hammerin' Harry (Europe)" },
  hittheice: { console: 'gb', boxart: "Hit the Ice - VHL - The Official Video Hockey League (USA, Europe)" },
  homealone2: { console: 'gb', boxart: "Home Alone 2 - Lost in New York (USA, Europe)" },
  houghtonmifflinspellcheckerandcalculator: { console: 'gb', boxart: "InfoGenius Productivity Pak - Spell Checker and Calculator (USA)" },
  hunchbackofnotredametopsyturvygames: { console: 'gb', boxart: "Hunchback of Notre Dame, The - 5 Foolishly Fun Topsy Turvy Games (USA, Europe) (SGB Enhanced)" },
  infogeniussystemspersonalorganizer: { console: 'gb', boxart: "InfoGenius Systems - Personal Organizer with Phone Book (Europe)" },
  ishido: { console: 'gb', boxart: "Ishido - The Way of Stones (USA)" },
  jeepjamboree: { console: 'gb', boxart: "Jeep Jamboree - Off-Road Adventure (USA)" },
  jetsons: { console: 'gb', boxart: "Jetsons, The - Robot Panic (USA, Europe)" },
  jurassicpark2thechaoscontinues: { console: 'gb', boxart: "Jurassic Park Part 2 - The Chaos Continues (USA, Europe) (En,Fr,De,It)" },
  kwirk: { console: 'gb', boxart: "Kwirk - He's A-maze-ing! (USA, Europe)" },
  mickeymousemagicwand: { console: 'gb', boxart: "Mickey Mouse - Magic Wands! (USA, Europe) (SGB Enhanced)" },
  micromachines2: { console: 'gb', boxart: "Micro Machines 2 - Turbo Tournament (Europe)" },
  miner2049er: { console: 'gb', boxart: "Miner 2049er Starring Bounty Bob (USA)" },
  mortalkombatiii: { console: 'gb', boxart: "Mortal Kombat _ Mortal Kombat II (USA, Europe)" },
  mysticalninja: { console: 'gb', boxart: "Mystical Ninja Starring Goemon (Europe) (SGB Enhanced)" },
  nflquarterbackclub2: { console: 'gb', boxart: "NFL Quarterback Club II (USA, Europe)" },
  nhlhockey96: { console: 'gb', boxart: "NHL 96 (USA, Europe) (SGB Enhanced)" },
  nigelmansellsworldchampionship92: { console: 'gb', boxart: "Nigel Mansell's World Championship (Europe)" },
  onthetilesfrankyjoedirk: { console: 'gb', boxart: "Franky, Joe _ Dirk - On the Tiles (Europe) (En,Fr,De,Es,It,Nl)" },
  ottosottifanten: { console: 'gb', boxart: "Otto's Ottifanten - Baby Bruno's Alptraum (Europe) (En,Fr,De,Es)" },
  parasolstars: { console: 'gb', boxart: "Parasol Stars - Rainbow Islands II (Europe)" },
  robocopvstheterminator: { console: 'gb', boxart: "RoboCop Versus The Terminator (Europe)" },
  smurfs2: { console: 'gb', boxart: "Smurfs Travel the World, The (Europe) (En,Fr,De,Es)" },
  spidermanandthexmeninarcadesrevenge: { console: 'gb', boxart: "Spider-Man - X-Men - Arcade's Revenge (USA, Europe)" },
  starwarssuperreturnofthejedi: { console: 'gb', boxart: "Super Star Wars - Return of the Jedi (USA, Europe) (SGB Enhanced)" },
  tarzan: { console: 'gb', boxart: "Tarzan - Lord of the Jungle (USA, Europe)" },
  teenagemutantheroturtlesbackfromthesewers: { console: 'gb', boxart: "Teenage Mutant Hero Turtles II - Back from the Sewers (Europe)" },
  wcwmainevent: { console: 'gb', boxart: "WCW - The Main Event (USA, Europe)" },
  wizardswarriorschapterxthefortressoffear: { console: 'gb', boxart: "Wizards _ Warriors X - The Fortress of Fear (USA, Europe)" },
  xviiolympicwintergameslillehammer1994: { console: 'gb', boxart: "Winter Olympic Games (USA)" },
  zool: { console: 'gb', boxart: "Zool - Ninja of the 'Nth' Dimension (Europe)" },
}

/** The No-Intro name chosen by hand for a file whose name doesn't match (see COVER_OVERRIDES), or null. */
export function officialName(name: string, id: ConsoleId): string | null {
  return overrideFor(name, id)?.boxart ?? null
}

/** A hand-picked box art only applies to its own console (a GBC set may also hold Game Boy games). */
function overrideFor(name: string, id: ConsoleId) {
  const o = COVER_OVERRIDES[looseKey(name)]
  return o && (o.console === id || (id === 'gbc' && o.console === 'gb')) ? o : null
}

export async function coverFor(id: ConsoleId, name: string): Promise<string | null> {
  const forced = overrideFor(name, id)
  if (forced) return coverUrl(consoleById(forced.console).thumbs, forced.boxart)
  const db = await loadDb(id)
  const boxart = bestBoxart(name, db.boxarts)
  if (boxart) return coverUrl(consoleById(id).thumbs, boxart)
  // Game Boy Color sets often include original Game Boy games (Pokémon Red/Blue/Yellow…): their box art
  // lives in the Game Boy thumbnails.
  if (id === 'gbc') {
    const gb = bestBoxart(name, (await loadDb('gb')).boxarts)
    if (gb) return coverUrl(consoleById('gb').thumbs, gb)
  }
  return null
}
