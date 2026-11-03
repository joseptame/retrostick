import { useState } from 'react'
import type { AppState } from '../App'
import { Banner, Cover, Switch } from '../components/ui'
import { consoleForFile, CONSOLES, extension, isCartridge, type ConsoleId } from '../lib/consoles'
import { downloadUrl, fetchMetadata, proxyPath, searchArchive, type ArchiveFile, type ArchiveItem, type ArchiveMetadata } from '../lib/archive'
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
  file: ArchiveFile
  phase: 'downloading' | 'uploading' | 'done'
  progress: number
  error?: string
}

export function Buscar({ state }: { state: AppState }) {
  const [consoleId, setConsoleId] = useState<ConsoleId>('gba')
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<ArchiveItem[] | null>(null)
  const [searching, setSearching] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<ArchiveMetadata | null>(null)
  const [toStick, setToStick] = useState(false)
  const [job, setJob] = useState<Job | null>(null)

  const busy = job?.phase === 'downloading' || job?.phase === 'uploading'
  const files = selected ? selected.files.filter((f) => consoleForFile(f.name)) : []
  const existingNames = (c: ConsoleId) =>
    new Set(state.library?.games.filter((g) => g.console === c).flatMap((g) => g.files.map((f) => f.name)) ?? [])

  async function doSearch() {
    const term = query.trim()
    if (!term) return
    setSearching(true)
    setError(null)
    setSelected(null)
    try {
      setResults(await searchArchive(`mediatype:software AND (${term})`))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setResults([])
    } finally {
      setSearching(false)
    }
  }

  async function open(item: ArchiveItem) {
    setError(null)
    setSelected(null)
    try {
      setSelected(await fetchMetadata(item.identifier))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  async function uploadFile(file: ArchiveFile) {
    if (!state.folders || !state.online || !selected || busy) return
    const console = consoleForFile(file.name)!
    const key = `${selected.identifier}/${file.name}`
    setJob({ key, file, phase: 'downloading', progress: 0 })
    try {
      const res = await fetch(proxyPath(selected.identifier, file.name))
      if (!res.ok) throw new Error(`Descarga ${res.status}`)
      const blob = await res.blob()
      const f = new File([blob], file.name)
      const ext = extension(file.name)

      let ident: Identification | null = null
      try {
        const data = isCartridge(console) && f.size <= MAX_HASH_BYTES ? new Uint8Array(await f.arrayBuffer()) : null
        ident = await identify(console, file.name, data)
      } catch {
        ident = null
      }
      const name = ident && !KEEP_NAME.has(ext) ? ident.name : baseName(file.name)
      const fileName = `${name}.${ext}`
      if (existingNames(console).has(fileName)) {
        setJob({ key, file, phase: 'done', progress: 1, error: 'Ya está en la colección' })
        return
      }

      const parent = toStick ? state.folders.jugando[console] : state.folders.coleccion[console]
      setJob({ key, file, phase: 'uploading', progress: 0 })
      await uploadResumable(blob, fileName, parent, (sent, total) =>
        setJob({ key, file, phase: 'uploading', progress: total ? sent / total : 1 }),
      )
      setJob({ key, file, phase: 'done', progress: 1 })
      state.notify(`${fileName}: subido`)
      state.refresh()
    } catch (e) {
      setJob({ key, file, phase: 'done', progress: 0, error: e instanceof Error ? e.message : String(e) })
    }
  }

  const count = results?.length ?? 0

  return (
    <main className="screen">
      <h1>Buscar juegos</h1>
      <p className="sub">Busca en archive.org y sube el juego a tu colección</p>

      <div className="chips-row">
        {CONSOLES.map((c) => (
          <button key={c.id} className={`chip-tab ${consoleId === c.id ? 'on' : ''}`} onClick={() => setConsoleId(c.id)}>
            {c.short}
          </button>
        ))}
      </div>

      <div className="search">
        <input
          value={query}
          placeholder="Nombre del juego…"
          aria-label="Buscar juego"
          inputMode="search"
          enterKeyHint="search"
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && doSearch()}
        />
        <button onClick={doSearch} disabled={searching || !query.trim()}>
          {searching ? '…' : 'Buscar'}
        </button>
      </div>

      {error && <Banner tone="error">{error}</Banner>}

      {selected ? (
        <>
          <button className="back" onClick={() => setSelected(null)}>
            ← Volver a los resultados
          </button>
          <h2>{displayTitle(selected.title)}</h2>
          <p className="sub">{files.length ? `${files.length} archivos de juego` : 'Sin archivos de juego en este elemento'}</p>

          {files.map((f) => {
            const isJob = job?.key === `${selected.identifier}/${f.name}`
            return (
              <div key={f.name} className="frow">
                <div className="grow">
                  <b>{f.name}</b>
                  <small>{f.size ? formatBytes(Number(f.size)) : 'tamaño desconocido'}</small>
                  {isJob && job.phase !== 'done' && (
                    <div className="prog">
                      <i style={{ width: `${job.progress * 100}%` }} />
                    </div>
                  )}
                  {isJob && job.error && <small className="error">{job.error}</small>}
                </div>
                {isJob && job.phase === 'done' && !job.error ? (
                  <span className="chip g">Subido</span>
                ) : (
                  <button className="pill" onClick={() => uploadFile(f)} disabled={!state.online || busy}>
                    {isJob && busy ? `${Math.round(job.progress * 100)} %` : 'Subir'}
                  </button>
                )}
                <a
                  className="link"
                  href={downloadUrl(selected.identifier, f.name)}
                  target="_blank"
                  rel="noreferrer"
                  aria-label={`Abrir ${f.name} en archive.org`}
                >
                  Abrir
                </a>
              </div>
            )
          })}
        </>
      ) : (
        <>
          {searching && <p className="loading">Buscando…</p>}
          {results && !searching && (
            <>
              <p className="sub">
                {count} {count === 1 ? 'resultado' : 'resultados'}
              </p>
              {count === 0 ? (
                <div className="empty">No hay resultados.</div>
              ) : (
                <div className="grid">
                  {results.map((r) => (
                    <div key={r.identifier} className="game-card" onClick={() => open(r)}>
                      <Cover console={consoleId} name={r.title} title={displayTitle(r.title)} />
                      <div className="ft">
                        <div className="nm">{displayTitle(r.title)}</div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
          {!results && !searching && (
            <div className="empty">
              <p>Escribe el nombre de un juego y pulsa Buscar.</p>
            </div>
          )}
        </>
      )}

      <div className="opt">
        <span>Enviar también al stick</span>
        <Switch on={toStick} onChange={setToStick} label="Enviar también al stick" disabled={busy} />
      </div>
    </main>
  )
}
