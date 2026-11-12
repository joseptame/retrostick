/**
 * The consoles' boot screens need each console's BIOS / boot ROM. RetroStick never downloads one: the user
 * uploads their own here (Stick → Pantallas de inicio) to Retro/Config/bios, and the stick copies them to
 * RetroArch/system. Same table as android/.../Bios.kt.
 */
export interface BiosEntry {
  console: string
  /** Name the emulator looks for: the upload is renamed to it */
  file: string
  /** Known good dumps; the stick doesn't use any other (a bad PS1 one left games on a black screen) */
  md5: string[]
  /**
   * A zip BIOS (Neo Geo): its MD5 changes with the packing, so it is checked by the CRC32 of the files inside;
   * all of these must be there (FinalBurn Neo's current dumps).
   */
  zipCrcs?: string[]
  shows: string
}

/** CRC32s of the files in a zip, from its central directory (no unzipping). */
export async function zipCrcs(file: Blob): Promise<Set<string>> {
  const buf = new DataView(await file.arrayBuffer())
  const out = new Set<string>()
  // End of central directory: signature 0x06054b50, within the last 64 KB
  let eocd = -1
  for (let i = buf.byteLength - 22; i >= Math.max(0, buf.byteLength - 65557); i--) {
    if (buf.getUint32(i, true) === 0x06054b50) { eocd = i; break }
  }
  if (eocd < 0) return out
  const count = buf.getUint16(eocd + 10, true)
  let p = buf.getUint32(eocd + 16, true)
  for (let n = 0; n < count && p + 46 <= buf.byteLength && buf.getUint32(p, true) === 0x02014b50; n++) {
    out.add(buf.getUint32(p + 16, true).toString(16).padStart(8, '0'))
    p += 46 + buf.getUint16(p + 28, true) + buf.getUint16(p + 30, true) + buf.getUint16(p + 32, true)
  }
  return out
}

/** Whether an uploaded BIOS is one the stick will use. */
export async function isKnownBios(e: BiosEntry, file: Blob, md5: string | undefined): Promise<boolean> {
  if (e.zipCrcs) {
    const crcs = await zipCrcs(file)
    return e.zipCrcs.every((c) => crcs.has(c))
  }
  return !!md5 && e.md5.includes(md5)
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
      // SCPH-7502 (v4.1 E), checked on the stick
      'b9d9a0286c33dc6b7237bb13cd46fdee',
    ],
    shows: 'Los logos de Sony y PlayStation (y mejor compatibilidad)',
  },
  { console: 'Master System', file: 'bios_E.sms', md5: ['840481177270d5642a14ca71ee72844c'], shows: 'La pantalla de SEGA de la consola' },
  { console: 'Mega Drive', file: 'bios_MD.bin', md5: ['45e298905a08f9cfb38fd504cd6dbc84'], shows: 'El aviso «Produced by or under license from SEGA»' },
  // Neo Geo: needed to play at all (FinalBurn Neo). 000-lo.lo, sfix.sfix, sm1.sm1 as FinalBurn Neo dumps them now
  // (older sets carry e09e253c / 354029fc / 97cf998b and no game starts) and the European AES BIOS (neo-epo.bin).
  {
    console: 'Neo Geo', file: 'neogeo.zip', md5: [],
    zipCrcs: ['5a86cff2', 'c2ea0cfd', '94416d67', 'd27a71f1'],
    shows: 'Imprescindible: sin ella no arranca ningún juego de Neo Geo',
  },
]
