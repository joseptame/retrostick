import { useEffect, useMemo, useRef, useState } from 'react'
import type { AppState } from '../App'
import { GameSheet } from '../components/GameSheet'
import { SearchCard, type CardState } from '../components/SearchCard'
import { Banner, Segmented, StickySearch } from '../components/ui'
import { CollectionSheet } from '../components/CollectionSheet'
import { archiveFileUrl, downloadUrl, listItemFiles, proxyPath, type ArchiveEntry } from '../lib/archive'
import { consoleForFile, CONSOLES, extension, isCartridge, type ConsoleId } from '../lib/consoles'
import { uploadResumable } from '../lib/drive'
import { formatBytes } from '../lib/format'
import { fetchIgdb, type IgdbGame } from '../lib/igdb'
import { baseName, displayTitle, looseKey, regionCode } from '../lib/names'
import { loadRecents, removeRecent, saveRecent, type RecentCollection } from '../lib/recents'
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

interface Ficha {
  entry: ArchiveEntry
  data: IgdbGame | null
  loading: boolean
  error: string | null
}

/** Error set when the file is already in Drive: shown as owned, not as a failure. */
const DUPLICATE = 'Ya está en la colección'

const norm = (s: string) => s.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()

export function Buscar({ state }: { state: AppState }) {
  const [consoleId, setConsoleId] = useState<ConsoleId>('gba')
  const [recents, setRecents] = useState<RecentCollection[]>(loadRecents)
  const [loadedId, setLoadedId] = useState(() => recents[0]?.id ?? 'gba-pal')
  const [sheetOpen, setSheetOpen] = useState(false)
  const [entries, setEntries] = useState<ArchiveEntry[] | null>(null)
  const [archiveName, setArchiveName] = useState<string | null>(null)
  const [title, setTitle] = useState('')
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [toStick, setToStick] = useState(false)
  const [job, setJob] = useState<Job | null>(null)
  const [ficha, setFicha] = useState<Ficha | null>(null)
  const loaded = useRef(false)

  const busy = job?.phase === 'downloading' || job?.phase === 'uploading'
  const existingNames = (c: ConsoleId) =>
    new Set(state.library?.games.filter((g) => g.console === c).flatMap((g) => g.files.map((f) => f.name)) ?? [])

  /** Titles already in the library, per console, compared loosely (region, case, accents ignored). */
  const ownedKeys = useMemo(() => {
    const m = new Map<ConsoleId, Set<string>>()
    for (const g of state.library?.games ?? []) {
      if (!m.has(g.console)) m.set(g.console, new Set())
      m.get(g.console)!.add(looseKey(g.name))
    }
    return m
  }, [state.library])
  const owns = (c: ConsoleId, fileName: string) => ownedKeys.get(c)?.has(looseKey(baseName(fileName))) ?? false

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
      setRecents(saveRecent({ id: clean, title: t, count: list.length }))
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
    load(loadedId)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  async function upload(entry: ArchiveEntry) {
    if (!state.folders || !state.online || busy) return
    const console = consoleForFile(entry.name) ?? consoleId
    const ext = extension(entry.name)
    const key = entry.name
    setJob({ key, entry, phase: 'downloading', progress: 0 })
    state.setUpload({ progress: null })
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
        setJob({ key, entry, phase: 'done', progress: 1, error: DUPLICATE })
        state.notify(`${fileName}: ya estaba en tu colección`)
        return
      }

      const parent = toStick ? state.folders.jugando[console] : state.folders.coleccion[console]
      setJob({ key, entry, phase: 'uploading', progress: 0 })
      await uploadResumable(blob, fileName, parent, (sent, total) => {
        const progress = total ? sent / total : 1
        setJob({ key, entry, phase: 'uploading', progress })
        state.setUpload({ progress })
      })
      setJob({ key, entry, phase: 'done', progress: 1 })
      state.notify(`${fileName}: subido`)
      state.refresh()
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setJob({ key, entry, phase: 'done', progress: 0, error: msg })
      state.notify(`No se pudo subir: ${msg}`)
    } finally {
      state.setUpload(null)
    }
  }

  async function openFicha(entry: ArchiveEntry) {
    setFicha({ entry, data: null, loading: true, error: null })
    try {
      const data = await fetchIgdb(displayTitle(entry.name))
      setFicha((f) => (f && f.entry.name === entry.name ? { ...f, data, loading: false } : f))
    } catch (e) {
      setFicha((f) =>
        f && f.entry.name === entry.name ? { ...f, data: null, loading: false, error: e instanceof Error ? e.message : String(e) } : f,
      )
    }
  }

  const collName = title || loadedId
  const q = norm(query.trim())
  const filtered = entries?.filter((e) => consoleForFile(e.name) === consoleId && (!q || norm(e.name).includes(q))) ?? []

  return (
    <main className="screen">
      <Segmented
        label="Consola"
        options={CONSOLES.map((c) => ({ id: c.id, label: c.short }))}
        value={consoleId}
        onChange={setConsoleId}
      />

      <StickySearch value={query} onChange={setQuery} placeholder={`Buscar en ${collName}…`} label="Buscar juegos" />

      <div className="bx-row">
        <button className="bx-source" onClick={() => setSheetOpen(true)} aria-label={`Cambiar colección: ${collName}`}>
          <svg className="bx-box" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinejoin="round" aria-hidden>
            <path d="M3.5 7.5L12 3l8.5 4.5v9L12 21l-8.5-4.5z" />
            <path d="M3.5 7.5L12 12l8.5-4.5M12 12v9" />
          </svg>
          <span>{loading ? 'Cargando…' : collName}</span>
          <svg className="bx-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M6 9l6 6 6-6" />
          </svg>
        </button>
        <button
          role="switch"
          aria-checked={toStick}
          aria-label="Enviar también al stick"
          className={`bx-stick ${toStick ? 'on' : ''}`}
          onClick={() => setToStick((v) => !v)}
          disabled={busy}
        >
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
            <rect x="3" y="4" width="18" height="12" rx="2" />
            <path d="M9 20h6M12 16v4" />
          </svg>
          Al stick
          <span className="bx-sw" aria-hidden>
            <i />
          </span>
        </button>
      </div>

      {error && <Banner tone="error">{error}</Banner>}

      {loading && (
        <div className="coll-loading" aria-live="polite">
          <p className="coll-status">
            <span className="dots" aria-hidden>
              <i />
              <i />
              <i />
            </span>
            Cargando colección de archive.org…
          </p>
          <div className="grid gp-grid">
            {Array.from({ length: 6 }, (_, i) => (
              <div key={i} className="gp-card skel-card" style={{ animationDelay: `${i * 70}ms` }}>
                <div className="sk gp-art" />
                <div className="gp-bar">
                  <div className="gp-row">
                    <div className="gp-txt">
                      <span className="sk sk-line" style={{ width: `${60 + ((i * 17) % 35)}%` }} />
                      <span className="sk sk-line short" />
                    </div>
                    <span className="sk gp-btn" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {entries && !loading && (
        <>
          <p className="bx-count">
            {q ? (
              <>
                {filtered.length} {filtered.length === 1 ? 'resultado' : 'resultados'} para <b>«{query.trim()}»</b>
              </>
            ) : (
              <>
                <b>{filtered.length}</b> {filtered.length === 1 ? 'juego' : 'juegos'}
              </>
            )}
          </p>
          {filtered.length === 0 ? (
            <div className="empty">No hay resultados.</div>
          ) : (
            <div className="grid gp-grid">
              {filtered.map((e, i) => {
                const isJob = job?.key === e.name
                const c = consoleForFile(e.name) ?? consoleId
                let cardState: CardState = { kind: 'idle' }
                if (isJob && job.phase === 'downloading') cardState = { kind: 'downloading' }
                else if (isJob && job.phase === 'uploading') cardState = { kind: 'uploading', progress: job.progress }
                else if (isJob && job.phase === 'done' && job.error && job.error !== DUPLICATE)
                  cardState = { kind: 'failed', error: job.error }
                else if ((isJob && job.phase === 'done') || owns(c, e.name)) cardState = { kind: 'owned' }
                return (
                  <SearchCard
                    key={e.name}
                    console={c}
                    fileName={e.name}
                    title={displayTitle(e.name)}
                    meta={[regionCode(e.name), formatBytes(e.size)].filter(Boolean).join(' · ')}
                    state={cardState}
                    locked={busy && !isJob}
                    online={state.online}
                    style={i < 12 ? { animationDelay: `${i * 45}ms` } : { animation: 'none' }}
                    onOpen={() => openFicha(e)}
                    onUpload={() => upload(e)}
                  />
                )
              })}
            </div>
          )}
        </>
      )}

      {!entries && !loading && !error && <div className="empty">Carga una colección para ver sus juegos.</div>}


      {sheetOpen && (
        <CollectionSheet
          current={loadedId}
          recents={recents}
          onLoad={(id) => {
            setQuery('')
            load(id)
          }}
          onForget={(id) => setRecents(removeRecent(id))}
          onClose={() => setSheetOpen(false)}
        />
      )}

      {ficha && (
        <GameSheet
          consoleId={consoleForFile(ficha.entry.name) ?? consoleId}
          fileName={ficha.entry.name}
          fallbackTitle={displayTitle(ficha.entry.name)}
          sizeLabel={formatBytes(ficha.entry.size)}
          data={ficha.data}
          loading={ficha.loading}
          error={ficha.error}
          online={state.online}
          busy={busy}
          progress={job?.key === ficha.entry.name && job.phase !== 'done' ? job.progress : null}
          uploaded={job?.key === ficha.entry.name && job.phase === 'done' && !job.error}
          uploadError={job?.key === ficha.entry.name ? job.error ?? null : null}
          archiveHref={archiveName ? archiveFileUrl(loadedId, archiveName, ficha.entry.name) : downloadUrl(loadedId, ficha.entry.name)}
          onUpload={() => upload(ficha.entry)}
          onClose={() => setFicha(null)}
        />
      )}
    </main>
  )
}

