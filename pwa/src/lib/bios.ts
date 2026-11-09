/**
 * The consoles' boot screens need each console's BIOS / boot ROM. RetroStick never downloads one: the user
 * uploads their own here (Stick → Pantallas de inicio) to Retro/Config/bios, and the stick copies them to
 * RetroArch/system. Same table as android/.../Bios.kt.
 */
export interface BiosEntry {
  console: string
  /** Name the emulator looks for: the upload is renamed to it */
  file: string
  /** Known good dumps; another one is kept and used all the same */
  md5: string[]
  shows: string
}

export const BIOS: BiosEntry[] = [
  { console: 'Game Boy', file: 'gb_bios.bin', md5: ['32fbbd84168d3482956eb3c5051637f5'], shows: 'El logo de Nintendo bajando y el «ding»' },
  { console: 'Game Boy Color', file: 'gbc_bios.bin', md5: ['dbfce9db9deaa2567f6a84fde55f9680'], shows: 'La animación del logo GAME BOY COLOR' },
  { console: 'Game Boy Advance', file: 'gba_bios.bin', md5: ['a860e8c0b6d573d191e4ec7db1b1e4f6'], shows: 'El logo GAME BOY con su sonido' },
  {
    console: 'PlayStation',
    file: 'scph5502.bin',
    md5: [
      '32736f17079d0b2b7024407c39bd3050',
      '490f666e1afb15b7362b406ed1cea246',
      '924e392ed05558ffdb115408c263dccf',
      '8dd7d5296a650fac7319bce665a6a53c',
      '1e68c231d0896b7eadcad1d7d8e76129',
    ],
    shows: 'Los logos de Sony y PlayStation (y mejor compatibilidad)',
  },
  { console: 'Master System', file: 'bios_E.sms', md5: ['840481177270d5642a14ca71ee72844c'], shows: 'La pantalla de SEGA de la consola' },
  { console: 'Mega Drive', file: 'bios_MD.bin', md5: ['45e298905a08f9cfb38fd504cd6dbc84'], shows: 'El aviso «Produced by or under license from SEGA»' },
]
