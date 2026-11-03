import { describe, expect, it } from 'vitest'
import { consoleForFile } from './consoles'
import { groupFiles } from './group'
import { crc32 } from './hash'
import { bestBoxart, coverUrl, displayTitle, looseKey, region, saveOf } from './names'

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
      'https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Color/master/Named_Boxarts/A%20%26%20B%3A%20C.png',
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
