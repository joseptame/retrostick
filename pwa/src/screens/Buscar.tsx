import { useEffect, useRef, useState } from 'react'
import type { AppState } from '../App'
import { Banner, Cover, Switch } from '../components/ui'
import { archiveFileUrl, downloadUrl, listItemFiles, proxyPath, type ArchiveEntry } from '../lib/archive'
import { consoleForFile, CONSOLES, extension, isCartridge, type ConsoleId } from '../lib/consoles'
import { uploadResumable } from '../lib/drive'
import { formatBytes } from '../lib/format'
import { baseName, displayTitle } from '../lib/names'
import { identify, type Identification } from '../lib/romdb'

/** Hashing is only done for cartridges; bigger files are matched by name. */
const MAX_HASH_BYTES = 64 * 1024 * 1024
/** .cue / .m3u list other files by name inside: renaming them would break the game */
const KEEP_NAME = new Set(['cue', 'bin', 'm3u'])

interface Job {
  key: string
  entry: ArchiveEntry
  phase: 'downloading' | 'uploading' | 'done'
  progress: number
  error?: string
}

const norm = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()

export function Buscar({ state }: { state: AppState }) {
  const [consoleId, setConsoleId] = useState<ConsoleId>('gba')
  const [identifier, setIdentifier] = useState('gba-pal')
  const [loadedId, setLoadedId] = useState('gba-pal')
  const [entries, setEntries] = useState<ArchiveEntry[] | null>(null)
  const [archiveName, setArchiveName] = useState<string | null>(null)
  const [title, setTitle] = useState('')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [toStick, setToStick] = useState(false)
  const [job, setJob] = useState<Job | null>(null)
  const loaded = useRef(false)

  const busy = job?.phase === 'downloading' || job?.phase === 'uploading'
  const existingNames = (c: ConsoleId) =>
    new Set(state.library?.games.filter((g) => g.console === c).flatMap((g) => g.files.map((f) => f.name)) ?? [])

  async function load(id: string) {
    const clean = id.trim()
    if (!clean) return
    setLoading(true)
    setError(null)
    setEntries(null)
    try {
      const { entries: list, archive, title: t } = await listItemFiles(clean)
      setLoadedId(clean)
      setArchiveName(archive)
      setTitle(t)
      setEntries(list)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setEntries([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (loaded.current) return
    loaded.current = true
    load(identifier)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function upload(entry: ArchiveEntry) {
    if (!state.folders || !state.online || busy) return
    const console = consoleForFile(entry.name) ?? consoleId
    const ext = extension(entry.name)
    const key = entry.name
    setJob({ key, entry, phase: 'downloading', progress: 0 })
    try {
      const url = archiveName
        ? archiveFileUrl(loadedId, archiveName, entry.name)
        : proxyPath(loadedId, entry.name)
      const res = await fetch(url)
      if (!res.ok) throw new Error(`Descarga ${res.status}`)
      const blob = await res.blob()
      const f = new File([blob], entry.name)

      let ident: Identification | null = null
      try {
        const data = isCartridge(console) && f.size <= MAX_HASH_BYTES ? new Uint8Array(await f.arrayBuffer()) : null
        ident = await identify(console, entry.name, data)
      } catch {
        ident = null
      }
      const name = ident && !KEEP_NAME.has(ext) ? ident.name : baseName(entry.name)
      const fileName = `${name}.${ext}`
      if (existingNames(console).has(fileName)) {
        setJob({ key, entry, phase: 'done', progress: 1, error: 'Ya está en la colección' })
        return
      }

      const parent = toStick ? state.folders.jugando[console] : state.folders.coleccion[console]
      setJob({ key, entry, phase: 'uploading', progress: 0 })
      await uploadResumable(blob, fileName, parent, (sent, total) =>
        setJob({ key, entry, phase: 'uploading', progress: total ? sent / total : 1 }),
      )
      setJob({ key, entry, phase: 'done', progress: 1 })
      state.notify(`${fileName}: subido`)
      state.refresh()
    } catch (e) {
      setJob({ key, entry, phase: 'done', progress: 0, error: e instanceof Error ? e.message : String(e) })
    }
  }

  const q = norm(query.trim())
  const filtered = entries?.filter((e) => consoleForFile(e.name) === consoleId && (!q || norm(e.name).includes(q))) ?? []

  return (
    <main className="screen">
      <h1>Buscar juegos</h1>
      <p className="sub">Explora una colección de archive.org y sube el juego que quieras</p>

      <div className="chips-row">
        {CONSOLES.map((c) => (
          <button key={c.id} className={`chip-tab ${consoleId === c.id ? 'on' : ''}`} onClick={() => setConsoleId(c.id)}>
            {c.short}
          </button>
        ))}
      </div>

      <div className="search">
        <input
          value={identifier}
          placeholder="Identificador de archive.org (ej. gba-pal)"
          aria-label="Identificador de archive.org"
          autoCapitalize="none"
          autoCorrect="off"
          onChange={(e) => setIdentifier(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && load(identifier)}
        />
        <button onClick={() => load(identifier)} disabled={loading || !identifier.trim()}>
          {loading ? '…' : 'Cargar'}
        </button>
      </div>

      {error && <Banner tone="error">{error}</Banner>}

      {entries && !loading && (
        <div className="search">
          <input
            value={query}
            placeholder="Filtrar por nombre…"
            aria-label="Filtrar juegos"
            inputMode="search"
            onChange={(e) => setQuery(e.target.value)}
          />
        </div>
      )}

      {loading && <p className="loading">Cargando colección…</p>}

      {entries && !loading && (
        <>
          <p className="sub">
            {title ? `${title} · ` : ''}
            {filtered.length} {filtered.length === 1 ? 'juego' : 'juegos'}
          </p>
          {filtered.length === 0 ? (
            <div className="empty">No hay resultados.</div>
          ) : (
            <div className="grid">
              {filtered.map((e) => {
                const isJob = job?.key === e.name
                return (
                  <div key={e.name} className="game-card">
                    <Cover console={consoleId} name={e.name} title={displayTitle(e.name)} />
                    <div className="ft">
                      <div className="nm">{displayTitle(e.name)}</div>
                      <div className="st">{formatBytes(e.size)}</div>
                      {isJob && job.phase !== 'done' && (
                        <div className="prog">
                          <i style={{ width: `${job.progress * 100}%` }} />
                        </div>
                      )}
                      {isJob && job.error && <div className="pt error">{job.error}</div>}
                      <div className="actions">
                        {isJob && job.phase === 'done' && !job.error ? (
                          <span className="chip g">Subido</span>
                        ) : (
                          <button className="pill" onClick={() => upload(e)} disabled={!state.online || busy}>
                            {isJob && busy ? `${Math.round(job.progress * 100)} %` : 'Subir'}
                          </button>
                        )}
                        <a
                          className="link"
                          href={archiveName ? archiveFileUrl(loadedId, archiveName, e.name) : downloadUrl(loadedId, e.name)}
                          target="_blank"
                          rel="noreferrer"
                        >
                          Abrir
                        </a>
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </>
      )}

      {!entries && !loading && !error && <div className="empty">Carga una colección para ver sus juegos.</div>}

      <div className="opt">
        <span>Enviar también al stick</span>
        <Switch on={toStick} onChange={setToStick} label="Enviar también al stick" disabled={busy} />
      </div>
    </main>
  )
}
