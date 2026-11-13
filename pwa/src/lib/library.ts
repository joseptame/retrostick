/** The collection as seen in Drive: games (Coleccion + JugandoAhora), saves and the stick status. */
import { CONSOLES, type ConsoleId } from './consoles'
import { listFolder, moveFile, readJson, saveStickRequest, stickRequestFiles, trashFile, type DriveFile, type Folders } from './drive'
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
  /** File names on that stick, by console (launcher 1.25.7+) */
  games?: Partial<Record<ConsoleId, string[]>>
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
  /** Games asked for a stick that picks its own, not taken by it yet, oldest first */
  requests?: StickRequest[]
  fetchedAt: number
}

/** Retro/Config/stick/pedido-<stick>-<time>.json (drive.ts saveStickRequest) */
export interface StickRequest {
  stick: string
  console: string
  file: string
  add: boolean
  at: string
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
  const requests = sticks.some((s) => s.autoRoms === false)
    ? (
        await Promise.all(
          (await stickRequestFiles(folders).catch(() => [])).map(async (f) => {
            const j = await readJson<Omit<StickRequest, 'stick'>>(f.id).catch(() => null)
            return j ? { ...j, stick: f.name.split('-')[1] } : null
          }),
        )
      )
        .filter((r): r is StickRequest => !!r)
        .sort((a, b) => a.at.localeCompare(b.at))
    : []
  const library: Library = {
    games: perConsole.flat().sort((a, b) => a.title.localeCompare(b.title, 'es')),
    saves: [...saves, ...states].sort((a, b) => b.modifiedTime.localeCompare(a.modifiedTime)),
    status,
    sticks,
    requests,
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

/**
 * Where a game being downloaded or uploaded goes: only the collection, one stick (by id) or all of them.
 * The main stick (and «all») = JugandoAhora; a stick that picks its games gets a request (see setOnStick).
 */
export type StickTarget = 'drive' | 'all' | string

/** The Drive folder a new game goes to for [target]. */
export function targetParent(library: Library | null, folders: Folders, console: ConsoleId, target: StickTarget): string {
  if (target === 'drive') return folders.coleccion[console]
  const stick = library?.sticks?.find((s) => s.id === target)
  return stick && picksGames(stick) ? folders.coleccion[console] : folders.jugando[console]
}

/** After the upload: the sticks that pick their games among [target] are asked for [file] (the game's main file). */
export async function requestForTarget(
  library: Library | null,
  folders: Folders,
  console: ConsoleId,
  file: string,
  target: StickTarget,
): Promise<void> {
  if (target === 'drive') return
  const sticks = (library?.sticks ?? []).filter((s) => picksGames(s) && (target === 'all' || s.id === target))
  await Promise.all(sticks.map((s) => saveStickRequest(folders, s.id!, { console, file, add: true })))
}

const STICK_KEY = 'retrostick.stick'

/** The stick Colección and the ficha show (several on the account): the one picked last, else the main one. */
export function chosenStick(library: Library): StickStatus | null {
  const sticks = library.sticks ?? []
  let id: string | null = null
  try {
    id = localStorage.getItem(STICK_KEY)
  } catch {
    /* private mode */
  }
  return sticks.find((s) => s.id && s.id === id) ?? library.status
}

export function chooseStick(id: string) {
  try {
    localStorage.setItem(STICK_KEY, id)
  } catch {
    /* private mode */
  }
}

/** A stick that picks its own games (DriveSync.autoRoms off), as opposed to the main one that follows JugandoAhora. */
export const picksGames = (stick: StickStatus | null) => !!stick && stick.autoRoms === false && !!stick.id

/** Whether [game] is (or is about to be) on [stick]; pending: asked, the stick hasn't taken it yet. */
export function onStick(library: Library, stick: StickStatus | null, game: Game): { on: boolean; pending: boolean } {
  if (!picksGames(stick)) return { on: game.inStick, pending: false }
  const asked = (library.requests ?? []).filter((r) => r.stick === stick!.id && r.console === game.console && r.file === game.main.name)
  const has = !!stick!.games?.[game.console]?.includes(game.main.name)
  const last = asked.at(-1)
  return last ? { on: last.add, pending: last.add !== has } : { on: has, pending: false }
}

/** Puts [game] on [stick] or takes it off: moved to JugandoAhora (main stick) or asked for (a stick that picks). */
export async function setOnStick(
  stick: StickStatus | null,
  game: Game,
  value: boolean,
  folders: Folders,
): Promise<StickRequest | null> {
  if (!picksGames(stick)) {
    await setInStick(game, value, folders)
    return null
  }
  const request = { console: game.console, file: game.main.name, add: value }
  await saveStickRequest(folders, stick!.id!, request)
  return { ...request, stick: stick!.id!, at: new Date().toISOString() }
}

export function stickSpace(library: Library, stick: StickStatus | null = library.status) {
  if (picksGames(stick)) {
    const used = library.games.filter((g) => onStick(library, stick, g).on).reduce((s, g) => s + g.size, 0)
    return { used, capacity: stick!.freeBytes + stick!.romsBytes }
  }
  const used = library.games.filter((g) => g.inStick).reduce((s, g) => s + g.size, 0)
  // Without status.json: 4.5 GB free by default (README 7.4)
  const capacity = library.status ? library.status.freeBytes + library.status.romsBytes : 4.5e9
  return { used, capacity }
}
