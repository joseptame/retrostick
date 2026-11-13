/** The collection as seen in Drive: games (Coleccion + JugandoAhora), saves and the stick status. */
import { CONSOLES, type ConsoleId } from './consoles'
import { listFolder, moveFile, readJson, trashFile, type DriveFile, type Folders } from './drive'
import { groupFiles } from './group'
import { baseName, displayTitle, region, saveOf } from './names'

export interface Game {
  key: string
  console: ConsoleId
  /** No-Intro / Redump name: also the base name of its saves */
  name: string
  title: string
  region: string | null
  main: DriveFile
  files: DriveFile[]
  inStick: boolean
  size: number
}

/** Partidas/.stick/status-<id>.json written by each stick's launcher (README 8.3); status.json before 1.25.5 */
export interface StickStatus {
  id?: string
  name?: string
  /** Downloads JugandoAhora by itself (false: a second stick on the account, which picks its games) */
  autoRoms?: boolean
  updatedAt: string
  freeBytes: number
  romsBytes: number
  controllers: string[]
  appVersion: string
}

export interface Library {
  games: Game[]
  saves: DriveFile[]
  /** The stick that downloads JugandoAhora (the newest if several): what the space card measures */
  status: StickStatus | null
  /** Every stick on the account, newest first */
  sticks?: StickStatus[]
  fetchedAt: number
}

const CACHE_KEY = 'retrostick.library'

export function cachedLibrary(): Library | null {
  try {
    return JSON.parse(localStorage.getItem(CACHE_KEY) ?? 'null')
  } catch {
    return null
  }
}

function toGames(console: ConsoleId, files: DriveFile[], inStick: boolean): Game[] {
  return groupFiles(files).map(({ main, parts }) => {
    const name = baseName(main.name)
    const all = [main, ...parts]
    return {
      key: `${console}/${main.id}`,
      console,
      name,
      title: displayTitle(name),
      region: region(name),
      main,
      files: all,
      inStick,
      size: all.reduce((s, f) => s + Number(f.size ?? 0), 0),
    }
  })
}

export async function loadLibrary(folders: Folders): Promise<Library> {
  const perConsole = await Promise.all(
    CONSOLES.map(async (c) => {
      const [stick, drive] = await Promise.all([listFolder(folders.jugando[c.id]), listFolder(folders.coleccion[c.id])])
      return [...toGames(c.id, stick, true), ...toGames(c.id, drive, false)]
    }),
  )
  const [saves, states, stickFiles] = await Promise.all([
    listFolder(folders.saves),
    listFolder(folders.states),
    listFolder(folders.stick),
  ])
  // One file per stick; a stick on a launcher before 1.25.5 only writes status.json (shared, the last one wins)
  const perStick = stickFiles.filter((f) => /^status-[\w-]+\.json$/.test(f.name))
  const statusFiles = perStick.length ? perStick : stickFiles.filter((f) => f.name === 'status.json')
  const sticks = (await Promise.all(statusFiles.map((f) => readJson<StickStatus>(f.id).catch(() => null))))
    .filter((s): s is StickStatus => !!s)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
  const status = sticks.find((s) => s.autoRoms !== false) ?? sticks[0] ?? null
  const library: Library = {
    games: perConsole.flat().sort((a, b) => a.title.localeCompare(b.title, 'es')),
    saves: [...saves, ...states].sort((a, b) => b.modifiedTime.localeCompare(a.modifiedTime)),
    status,
    sticks,
    fetchedAt: Date.now(),
  }
  localStorage.setItem(CACHE_KEY, JSON.stringify(library))
  return library
}

export function savesFor(game: Game, saves: DriveFile[]): DriveFile[] {
  return saves.filter((s) => saveOf(s.name)?.game === game.name)
}

export function gameForSave(save: DriveFile, games: Game[]): Game | undefined {
  const name = saveOf(save.name)?.game
  return games.find((g) => g.name === name)
}

/** Moves the whole unit (a .m3u with its discs, a .cue with its tracks) between Coleccion and JugandoAhora. */
export async function setInStick(game: Game, inStick: boolean, folders: Folders): Promise<void> {
  const to = inStick ? folders.jugando[game.console] : folders.coleccion[game.console]
  const from = inStick ? folders.coleccion[game.console] : folders.jugando[game.console]
  await Promise.all(game.files.map((f) => moveFile(f.id, to, from)))
}

/** Deletes the game files (to the Drive bin). Saves are never touched (README 3). */
export async function deleteGame(game: Game): Promise<void> {
  await Promise.all(game.files.map((f) => trashFile(f.id)))
}

export function stickSpace(library: Library) {
  const used = library.games.filter((g) => g.inStick).reduce((s, g) => s + g.size, 0)
  // Without status.json: 4.5 GB free by default (README 7.4)
  const capacity = library.status ? library.status.freeBytes + library.status.romsBytes : 4.5e9
  return { used, capacity }
}
