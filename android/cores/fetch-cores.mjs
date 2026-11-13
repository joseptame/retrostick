// Downloads the emulator cores RetroStick uses (armeabi-v7a, libretro buildbot) into src/main/jniLibs, renamed
// lib<core>.so: Android only extracts native libraries named lib*.so. Run before building the cores APK.
//   node fetch-cores.mjs
import { execFileSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, renameSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const BUILDBOT = 'https://buildbot.libretro.com/nightly/android/latest/armeabi-v7a'
export const CORES = [
  'fceumm', 'snes9x2010', 'gambatte', 'mgba', 'genesis_plus_gx', 'mupen64plus_next_gles2', 'fbneo', 'pcsx_rearmed',
]

const out = join(import.meta.dirname, 'src/main/jniLibs/armeabi-v7a')
mkdirSync(out, { recursive: true })
const tmp = mkdtempSync(join(tmpdir(), 'cores-'))
for (const core of CORES) {
  const name = `${core}_libretro_android.so`
  const res = await fetch(`${BUILDBOT}/${name}.zip`)
  if (!res.ok) throw new Error(`${name}: HTTP ${res.status}`)
  const zip = join(tmp, `${name}.zip`)
  writeFileSync(zip, Buffer.from(await res.arrayBuffer()))
  // bsdtar reads zips (Windows has it in System32; Git Bash's GNU tar does not)
  execFileSync(process.platform === 'win32' ? 'C:/Windows/System32/tar.exe' : 'bsdtar', ['-xf', zip, '-C', tmp])
  renameSync(join(tmp, name), join(out, `lib${name}`))
  console.log(`lib${name}`)
}
