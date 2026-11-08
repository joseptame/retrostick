import { describe, expect, it } from 'vitest'
import { archiveFileUrl, downloadUrl, parseArchiveListing, proxyPath } from './archive'
import { consoleForFile } from './consoles'
import { groupFiles } from './group'
import { crc32 } from './hash'
import { bestBoxart, coverUrl, displayTitle, looseKey, region, regionCode, saveOf } from './names'

const ZELDA = 'Legend of Zelda, The - Oracle of Ages (Europe) (En,Fr,De,Es,It)'

describe('consoleForFile', () => {
  it('detects consoles by extension (README 7.4)', () => {
    expect(consoleForFile('x.gb')).toBe('gb')
    expect(consoleForFile('X.GBC')).toBe('gbc')
    expect(consoleForFile('mkarioo_dump01.gba')).toBe('gba')
    expect(consoleForFile('Game (Disc 1).chd')).toBe('psx')
    expect(consoleForFile('Game.cue')).toBe('psx')
    expect(consoleForFile('Game (Track 01).bin')).toBe('psx')
    expect(consoleForFile('notes.txt')).toBeNull()
  })
})

describe('names', () => {
  it('builds readable titles', () => {
    expect(displayTitle(ZELDA)).toBe('The Legend of Zelda - Oracle of Ages')
    expect(displayTitle('Pokemon - Edicion Esmeralda (Spain)')).toBe('Pokemon - Edicion Esmeralda')
    expect(displayTitle('Metroid Fusion (Europe) (En,Fr,De,Es,It)')).toBe('Metroid Fusion')
  })

  it('translates the region', () => {
    expect(region(ZELDA)).toBe('Europa')
    expect(region('Game (USA)')).toBe('EE. UU.')
    expect(region('Game')).toBeNull()
    expect(regionCode('Mario Kart - Super Circuit (Europe).gba')).toBe('EU')
    expect(regionCode('ATV - Quad Power Racing (USA, Europe).gba')).toBe('US · EU')
    expect(regionCode('Dragon Ball (Europe) (En,Fr,De,Es,It).gba')).toBe('EU')
    expect(regionCode('Game (Rev 1).gba')).toBeNull()
  })

  it('matches loosely ignoring case, accents and articles', () => {
    expect(looseKey('legend of zelda, the - oracle of ages')).toBe(looseKey(ZELDA))
    expect(looseKey('Pokémon Esmeralda')).toBe(looseKey('Pokemon Esmeralda (Spain)'))
  })
})

describe('covers', () => {
  const index = [
    'Legend of Zelda, The - Oracle of Ages (Europe) (En,Fr,De,Es,It)',
    'Legend of Zelda, The - Oracle of Ages (USA)',
    'Legend of Zelda, The - Oracle of Ages (Japan) (Beta)',
  ]

  it('prefers the European cover even for a USA dump', () => {
    expect(bestBoxart('Legend of Zelda, The - Oracle of Ages (USA)', index)).toBe(index[0])
    expect(bestBoxart(ZELDA, index)).toBe(index[0])
  })

  it('prefers a Spanish release over other European ones', () => {
    expect(bestBoxart('Game (USA)', ['Game (Europe)', 'Game (Spain)', 'Game (USA)'])).toBe('Game (Spain)')
  })

  it('skips betas and falls back to other regions', () => {
    expect(bestBoxart('Game (Japan)', ['Game (Japan) (Beta)', 'Game (USA)'])).toBe('Game (USA)')
    expect(bestBoxart('Other', index)).toBeNull()
  })

  it('builds the libretro-thumbnails URL (README 7.5)', () => {
    expect(coverUrl('Nintendo_-_Game_Boy_Color', 'A & B: C')).toBe(
      'https://wsrv.nl/?url=' +
        encodeURIComponent('https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Color/Named_Boxarts/A%20%26%20B%3A%20C.png') +
        '&w=400&output=webp&q=80',
    )
  })
})

describe('saves', () => {
  it('recognises RetroArch save files', () => {
    expect(saveOf(`${ZELDA}.srm`)).toEqual({ game: ZELDA, kind: 'srm' })
    expect(saveOf(`${ZELDA}.state.auto`)).toEqual({ game: ZELDA, kind: 'auto' })
    expect(saveOf(`${ZELDA}.state`)).toEqual({ game: ZELDA, kind: 'state', slot: 0 })
    expect(saveOf(`${ZELDA}.state3`)).toEqual({ game: ZELDA, kind: 'state', slot: 3 })
    expect(saveOf('status.json')).toBeNull()
  })
})

describe('groupFiles', () => {
  const f = (name: string) => ({ name })

  it('keeps an .m3u with its discs and their tracks', () => {
    const files = [
      f('Game (Europe).m3u'),
      f('Game (Europe) (Disc 1).cue'),
      f('Game (Europe) (Disc 1).bin'),
      f('Game (Europe) (Disc 2).chd'),
      f('Other (USA).chd'),
    ]
    const units = groupFiles(files)
    expect(units).toHaveLength(2)
    expect(units[0].main.name).toBe('Game (Europe).m3u')
    expect(units[0].parts.map((p) => p.name).sort()).toEqual([
      'Game (Europe) (Disc 1).bin', 'Game (Europe) (Disc 1).cue', 'Game (Europe) (Disc 2).chd',
    ])
    expect(units[1].main.name).toBe('Other (USA).chd')
  })

  it('keeps a .cue with all its tracks', () => {
    const units = groupFiles([f('Game.cue'), f('Game (Track 01).bin'), f('Game (Track 02).bin'), f('Zelda.gba')])
    expect(units.map((u) => [u.main.name, u.parts.length])).toEqual([['Game.cue', 2], ['Zelda.gba', 0]])
  })
})

describe('crc32', () => {
  it('matches the standard CRC-32', () => {
    expect(crc32(new TextEncoder().encode('123456789'))).toBe('cbf43926')
  })
})

describe('archive.org URLs', () => {
  it('encodes identifier and file name', () => {
    expect(downloadUrl('pokemon-emerald', 'Pokemon - Emerald Version (U).gba')).toBe(
      'https://archive.org/download/pokemon-emerald/Pokemon%20-%20Emerald%20Version%20(U).gba',
    )
  })

  it('keeps the "/" separator for a file inside an archive', () => {
    expect(archiveFileUrl('gba-pal', 'GBA PAL.zip', 'Dragon Ball - Advanced Adventure (Europe).gba')).toBe(
      'https://archive.org/download/gba-pal/GBA%20PAL.zip/Dragon%20Ball%20-%20Advanced%20Adventure%20(Europe).gba',
    )
  })

  it('builds the same-origin proxy path', () => {
    expect(proxyPath('pokemon-emerald', 'Game (U).gba')).toBe(
      '/api/download?id=pokemon-emerald&file=Game%20(U).gba',
    )
  })
})

describe('parseArchiveListing', () => {
  it('extracts names and sizes and decodes entities', () => {
    const html = [
      '<table class="archext">',
      '<tr><th>file<th>as jpg<th>timestamp<th>size</tr>',
      '<tr><td><a href="//archive.org/download/gba-pal/GBA%20PAL.zip/Dragon%20Ball%20-%20Advanced%20Adventure%20%28Europe%29.gba">Dragon Ball - Advanced Adventure (Europe).gba</a><td><td>1996-12-24 23:32<td id="size">16777216</tr>',
      '<tr><td><a href="//archive.org/download/gba-pal/GBA%20PAL.zip/Dungeons%20%26%20Dragons%20-%20Eye%20of%20the%20Beholder%20%28Europe%29.gba">Dungeons &amp; Dragons - Eye of the Beholder (Europe).gba</a><td><td>1996-12-24 23:32<td id="size">4194304</tr>',
      '</table>',
    ].join('\n')
    expect(parseArchiveListing(html)).toEqual([
      { name: 'Dragon Ball - Advanced Adventure (Europe).gba', size: 16777216 },
      { name: 'Dungeons & Dragons - Eye of the Beholder (Europe).gba', size: 4194304 },
    ])
  })
})

describe('igdb lookup', async () => {
  const { makeLookup } = await import('./igdbdb')
  const db = {
    generated: '2026-11-07',
    genres: ['Racing', 'Shooter'],
    games: {
      mariokartsupercircuit: [[0], 7.6, 2001],
      pokemonemerald: [[1], null, 2004],
      jamesbond007everythingornothing: [[1], 7.1, 2003],
    } as Record<string, [number[], number | null, number | null]>,
  }
  const look = makeLookup(db)
  it('matches exact, "Version" suffix and partial names', () => {
    expect(look('Mario Kart - Super Circuit (Europe)')).toEqual({ genres: ['Racing'], rating: 7.6, year: 2001 })
    expect(look('Pokemon - Emerald Version (USA, Europe)')?.year).toBe(2004)
    expect(look('007 - Everything or Nothing (Europe)')?.rating).toBe(7.1)
    expect(look('Juego Inventado (Europe)')).toBeNull()
  })
})

describe('ROM sets with folders and GoodTools names', async () => {
  const { baseName: bn, displayTitle: dt, regionCode: rc, region: rg } = await import('./names')
  const { archiveFileOf } = await import('./archive')
  it('drops the folder and reads GoodTools regions', () => {
    expect(dt('GBC/Adventures of the Smurfs, The (E) (M6) [C][!].gbc')).toBe('The Adventures of the Smurfs')
    expect(bn('GBC/Aladdin (E) (M6) [C][!].gbc')).toBe('Aladdin (E) (M6) [C][!]')
    expect(rc('GBC/Aladdin (E) (M6) [C][!].gbc')).toBe('EU')
    expect(rc('Pokemon - Gold Version (UE) [C][!].gbc')).toBe('US · EU')
    expect(rg('Juego (S).gbc')).toBe('España')
    expect(rc('Mario Kart - Super Circuit (Europe).gba')).toBe('EU')
  })
  it('picks the ROM archive, not the image pack', () => {
    expect(archiveFileOf([{ name: 'Imgs GBC.zip', size: '145598725' }, { name: 'GBC.zip', size: '238549560' }])?.name).toBe('GBC.zip')
  })
})

describe('archives made on a Mac', async () => {
  const { isJunk } = await import('./archive')
  it('skips __MACOSX and resource forks', () => {
    expect(isJunk('__MACOSX/GB/._Tetris (W) (V1.1) [!].gb')).toBe(true)
    expect(isJunk('GB/._Tetris.gb')).toBe(true)
    expect(isJunk('GB/.DS_Store')).toBe(true)
    expect(isJunk('GB/Tetris (W) (V1.1) [!].gb')).toBe(false)
  })
})

describe('multi-disc games', async () => {
  const { groupDiscs, m3uName } = await import('./archive')
  it('groups discs in order and names the .m3u', () => {
    const g = groupDiscs([
      { name: 'CHD-PSX-EUR/Ape Escape (Europe).chd', size: 100 },
      { name: 'CHD-PSX-EUR/Riven (Europe) (Disc 2).chd', size: 20 },
      { name: 'CHD-PSX-EUR/Riven (Europe) (Disc 1).chd', size: 10 },
    ])
    expect(g).toHaveLength(2)
    expect(g[1].discs?.map((d) => d.name)).toEqual(['CHD-PSX-EUR/Riven (Europe) (Disc 1).chd', 'CHD-PSX-EUR/Riven (Europe) (Disc 2).chd'])
    expect(g[1].size).toBe(30)
    expect(m3uName('CHD-PSX-EUR/Riven (Europe) (Disc 1).chd')).toBe('Riven (Europe).m3u')
  })
})

describe('demos and per-console overrides', async () => {
  const { isDemo: demo } = await import('./names')
  const { officialName } = await import('./romdb')
  it('spots demos but not games', () => {
    expect(demo('CHD-PSX-EUR/Final Fantasy VIII (Europe) (Demo 1).chd')).toBe(true)
    expect(demo('CHD-PSX-EUR/Euro Demo 64 (Europe).chd')).toBe(true)
    expect(demo('CHD-PSX-EUR/Final Fantasy VIII (Europe, Australia) (Disc 1).chd')).toBe(false)
    expect(demo('Demolition Racer (Europe).chd')).toBe(false)
  })
  it('keeps hand-picked box art on its own console', () => {
    expect(officialName('International Karate (E) [C][!]', 'gbc')).toBe('International Karate 2000 (Europe)')
    expect(officialName('International Karate + (Europe)', 'psx')).toBeNull()
    expect(officialName('Pokémon Amarillo', 'gbc')).not.toBeNull()
  })
})

describe('one release per game', async () => {
  const { bestVariants } = await import('./names')
  it('prefers Spanish, finished, original releases', () => {
    const pick = (names: string[]) => bestVariants(names.map((name) => ({ name }))).map((e) => e.name)
    expect(pick(['Asterix (Europe) (En,Fr,De,Nl).chd', 'Asterix (Europe) (En,Fr,Es,It).chd', 'Asterix (Europe) (De,Es,It) (Rev 1).chd'])).toEqual(['Asterix (Europe) (En,Fr,Es,It).chd'])
    expect(pick(['Alfred Chicken (Europe) (Beta 1).chd', 'Alfred Chicken (Europe) (En,Fr,De,Es,It).chd'])).toEqual(['Alfred Chicken (Europe) (En,Fr,De,Es,It).chd'])
    expect(pick(['Area 51 (Europe) (En,Fr,De,Es) (Midway Classics).chd', 'Area 51 (Europe) (En,Fr,De,Es).chd'])).toEqual(['Area 51 (Europe) (En,Fr,De,Es).chd'])
    expect(pick(['Tetris (W) (V1.1) [!].gb', 'Mario (U).gb'])).toHaveLength(2)
  })
})
