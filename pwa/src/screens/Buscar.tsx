import { useEffect, useMemo, useRef, useState } from 'react'
import type { AppState } from '../App'
import { GameSheet } from '../components/GameSheet'
import { SearchCard, type CardState } from '../components/SearchCard'
import { Banner, Segmented, StickySearch } from '../components/ui'
import { Icon } from '../components/icons'
import { CollectionSheet } from '../components/CollectionSheet'
import { FilterSheet, filtersActive, NO_FILTERS, UNRATED, type Filters, type FilterSection } from '../components/FilterSheet'
import { archiveFileUrl, downloadUrl, groupDiscs, listItemFiles, m3uName, packedRom, proxyPath, type ArchiveEntry } from '../lib/archive'
import { consoleForFile, CONSOLES, extension, isCartridge, type ConsoleId } from '../lib/consoles'
import { uploadResumable } from '../lib/drive'
import { formatBytes } from '../lib/format'
import { fetchIgdb, type IgdbGame } from '../lib/igdb'
import { genreEs, loadLookup, type Brief } from '../lib/igdbdb'
import { baseName, bestVariants, displayTitle, fileOnly, isDemo, looseKey, regionCode } from '../lib/names'
import { collectionFor, loadRecents, removeRecent, saveRecent, setCollectionFor, type RecentCollection } from '../lib/recents'
import { identify, type Identification } from '../lib/romdb'
import { transferFromArchive } from '../lib/transfer'
import { isActive } from '../lib/uploads'

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
  /** Buscar starts on a platform picker: nothing is loaded until one is chosen. */
  const [chosen, setChosen] = useState(false)
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [filtersOpen, setFiltersOpen] = useState<FilterSection | null>(null)
  const [lookup, setLookup] = useState<{ id: ConsoleId; fn: (name: string) => Brief | null } | null>(null)
  const [recents, setRecents] = useState<RecentCollection[]>(loadRecents)
  const [loadedId, setLoadedId] = useState(() => collectionFor('gba') ?? recents[0]?.id ?? 'gba-pal')
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

  // One upload at a time, wherever it was started (it keeps going if you leave Buscar).
  const busy = state.uploads.some(isActive)
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
      const { entries: list, archive, title: t } = await listItemFiles(clean, consoleId)
      setLoadedId(clean)
      setArchiveName(archive)
      setTitle(t)
      setEntries(groupDiscs(list))
      // Count the games the list will show: ROMs only, discs joined, no demos, one release per title.
      const games = bestVariants(groupDiscs(list).filter((e) => consoleForFile(e.name) && !isDemo(e.name)))
      setRecents(saveRecent({ id: clean, title: t, count: games.length }))
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setEntries([])
    } finally {
      setLoading(false)
    }
  }

  // Each console has its own collection (gba-pal, gbc_20240311…): switching tabs loads it.
  useEffect(() => {
    if (!chosen) return
    const want = collectionFor(consoleId)
    if (!loaded.current) {
      loaded.current = true
      load(want ?? loadedId)
    } else if (want && want !== loadedId) {
      setQuery('')
      load(want)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [consoleId, chosen])

  // Genres and ratings for the filters: prebuilt per console (public/db/igdb-*.json), loaded once.
  useEffect(() => {
    let alive = true
    setFilters(NO_FILTERS)
    loadLookup(consoleId)
      .then((fn) => alive && setLookup({ id: consoleId, fn }))
      .catch(() => alive && setLookup(null))
    return () => {
      alive = false
    }
  }, [consoleId])

  async function uploadByServer(entry: ArchiveEntry, console: ConsoleId) {
    if (!state.folders) return
    const key = entry.name
    const discs = entry.discs ?? [entry]
    const playlist = discs.length > 1 ? m3uName(discs[0].name) : null
    const first = playlist ?? fileOnly(entry.name)
    if (existingNames(console).has(first)) {
      setJob({ key, entry, phase: 'done', progress: 1, error: DUPLICATE })
      state.notify(`${first}: ya estaba en tu colección`)
      return
    }
    const parent = toStick ? state.folders.jugando[console] : state.folders.coleccion[console]
    const total = discs.reduce((n, d) => n + d.size, 0)
    const id = state.uploadsApi.start({
      key: `${loadedId}/${key}`,
      title: displayTitle(entry.name),
      console,
      coverName: entry.name,
      total,
      toStick,
      detail: discs.length > 1 ? `${discs.length} discos` : undefined,
      retry: () => uploadByServer(entry, console),
    })
    let done = 0
    const report = (sent: number) => {
      setJob({ key, entry, phase: 'uploading', progress: total ? (done + sent) / total : 1 })
      state.uploadsApi.progress(id, done + sent)
    }
    setJob({ key, entry, phase: 'uploading', progress: 0 })
    try {
      for (const d of discs) {
        await transferFromArchive({
          identifier: loadedId,
          file: d.name,
          size: d.size,
          name: fileOnly(d.name),
          parentId: parent,
          onProgress: (sent) => report(sent),
        })
        done += d.size
      }
      if (playlist) {
        const text = discs.map((d) => fileOnly(d.name)).join('\n') + '\n'
        await uploadResumable(new Blob([text], { type: 'audio/x-mpegurl' }), playlist, parent, () => {})
      }
      setJob({ key, entry, phase: 'done', progress: 1 })
      state.uploadsApi.finish(id)
      state.notify(`${displayTitle(entry.name)}: subido${discs.length > 1 ? ` (${discs.length} discos)` : ''}`)
      state.refresh()
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setJob({ key, entry, phase: 'done', progress: 0, error: msg })
      state.uploadsApi.finish(id, msg)
      state.notify(`No se pudo subir: ${msg}`)
    }
  }

  async function upload(entry: ArchiveEntry) {
    if (!state.folders || !state.online || busy) return
    const console = consoleForFile(entry.name) ?? consoleId
    const key = entry.name
    // Loose discs (PS1 .chd, hundreds of MB): the server copies them from archive.org into Drive,
    // so they never pass through the phone. Discs of one game go together, with their .m3u.
    if (!archiveName && !isCartridge(console)) return uploadByServer(entry, console)
    const id = state.uploadsApi.start({
      key: `${loadedId}/${key}`,
      title: displayTitle(entry.name),
      console,
      coverName: entry.name,
      total: entry.size,
      toStick,
      status: 'downloading',
      retry: () => upload(entry),
    })
    setJob({ key, entry, phase: 'downloading', progress: 0 })
    try {
      // One archive per game (No-Intro_NES…): the ROM comes out of it (its real name may say .smc).
      const rom = entry.pack ? await packedRom(loadedId, entry.pack) : null
      const ext = rom ? extension(rom.name) : extension(entry.name)
      const url = rom
        ? archiveFileUrl(loadedId, entry.pack!, rom.name)
        : archiveName
          ? archiveFileUrl(loadedId, archiveName, entry.name)
          : proxyPath(loadedId, entry.name)
      const res = await fetch(url)
      if (!res.ok) throw new Error(`Descarga ${res.status}`)
      const blob = await res.blob()
      if (rom) state.uploadsApi.progress(id, 0, undefined, blob.size)
      const f = new File([blob], rom?.name ?? entry.name)

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
        state.uploadsApi.finish(id, DUPLICATE)
        state.notify(`${fileName}: ya estaba en tu colección`)
        return
      }

      const parent = toStick ? state.folders.jugando[console] : state.folders.coleccion[console]
      setJob({ key, entry, phase: 'uploading', progress: 0 })
      state.uploadsApi.progress(id, 0, 'uploading')
      await uploadResumable(blob, fileName, parent, (sent, total) => {
        setJob({ key, entry, phase: 'uploading', progress: total ? sent / total : 1 })
        state.uploadsApi.progress(id, sent)
      })
      setJob({ key, entry, phase: 'done', progress: 1 })
      state.uploadsApi.finish(id)
      state.notify(`${fileName}: subido`)
      state.refresh()
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      setJob({ key, entry, phase: 'done', progress: 0, error: msg })
      state.uploadsApi.finish(id, msg)
      state.notify(`No se pudo subir: ${msg}`)
    }
  }


  async function openFicha(entry: ArchiveEntry) {
    setFicha({ entry, data: null, loading: true, error: null })
    try {
      const data = await fetchIgdb(displayTitle(entry.name), consoleForFile(entry.name) ?? consoleId)
      setFicha((f) => (f && f.entry.name === entry.name ? { ...f, data, loading: false } : f))
    } catch (e) {
      setFicha((f) =>
        f && f.entry.name === entry.name ? { ...f, data: null, loading: false, error: e instanceof Error ? e.message : String(e) } : f,
      )
    }
  }

  const collName = title || loadedId
  const q = norm(query.trim())
  const info = lookup?.id === consoleId ? lookup.fn : null
  const base = useMemo(
    () =>
      bestVariants((entries ?? []).filter((e) => consoleForFile(e.name) === consoleId && !isDemo(e.name)))
        .filter((e) => !q || norm(e.name).includes(q))
        .map((e) => ({ e, info: info?.(baseName(e.name)) ?? null })),
    [entries, consoleId, q, info],
  )
  const genreCounts = useMemo(() => {
    const m = new Map<string, number>()
    for (const x of base) for (const g of x.info?.genres ?? []) m.set(g, (m.get(g) ?? 0) + 1)
    return [...m].map(([name, count]) => ({ name, count })).sort((a, b) => b.count - a.count)
  }, [base])
  const shown = useMemo(() => {
    let list = base
    if (filters.genres.length) list = list.filter((x) => x.info?.genres.some((g) => filters.genres.includes(g)))
    if (filters.minRating === UNRATED) list = list.filter((x) => x.info?.rating == null)
    else if (filters.minRating) list = list.filter((x) => (x.info?.rating ?? -1) >= filters.minRating)
    if (filters.sort === 'rating') list = [...list].sort((a, b) => (b.info?.rating ?? -1) - (a.info?.rating ?? -1))
    if (filters.sort === 'year') list = [...list].sort((a, b) => (b.info?.year ?? 0) - (a.info?.year ?? 0))
    return list
  }, [base, filters])
  const filtered = shown.map((x) => x.e)
  const active = filtersActive(filters)

  return (
    <main className="screen">
      {!chosen ? (
        <PlatformPicker
          recents={recents}
          uploads={state.uploads.filter(isActive).length}
          onPick={(c) => {
            setConsoleId(c)
            setChosen(true)
          }}
        />
      ) : (
      <>
      <div className="bx-top">
        <button className="bx-back" onClick={() => { setChosen(false); setQuery('') }} aria-label="Volver a las plataformas">
          <Icon name="back" />
        </button>
        <Segmented
          label="Consola"
          options={CONSOLES.map((c) => ({ id: c.id, label: c.short }))}
          value={consoleId}
          onChange={setConsoleId}
        />
      </div>

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

      {entries && !loading && (
        <div className="pills filter-pills">
          <button className={`pill2 ${filters.genres.length ? 'on' : ''}`} onClick={() => setFiltersOpen('genre')} disabled={!info}>
            {filters.genres.length ? `${genreEs(filters.genres[0])}${filters.genres.length > 1 ? ` +${filters.genres.length - 1}` : ''}` : 'Género'} ▾
          </button>
          <button className={`pill2 ${filters.minRating ? 'on' : ''}`} onClick={() => setFiltersOpen('rating')} disabled={!info}>
            {filters.minRating === UNRATED ? 'Sin nota' : filters.minRating ? `★ ${filters.minRating}+` : 'Valoración'} ▾
          </button>
          <button className={`pill2 ${filters.sort !== 'name' ? 'on' : ''}`} onClick={() => setFiltersOpen('sort')} disabled={!info}>
            {filters.sort === 'rating' ? 'Mejor valorados' : filters.sort === 'year' ? 'Más recientes' : 'Ordenar'} ▾
          </button>
          {active && (
            <button className="pill2 clear" onClick={() => setFilters(NO_FILTERS)} aria-label="Quitar filtros">
              ✕
            </button>
          )}
        </div>
      )}

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
            <div className="empty">{active ? 'Ningún juego cumple estos filtros.' : 'No hay resultados.'}</div>
          ) : (
            <div className="grid gp-grid">
              {shown.map(({ e, info: brief }, i) => {
                const isJob = job?.key === e.name
                const c = consoleForFile(e.name) ?? consoleId
                // An upload started before leaving and coming back to Buscar lives in the shared list.
                const shared = state.uploads.find((u) => u.key === `${loadedId}/${e.name}`)
                let cardState: CardState = { kind: 'idle' }
                if (!isJob && shared && isActive(shared))
                  cardState = shared.status === 'downloading' ? { kind: 'downloading' } : { kind: 'uploading', progress: shared.total ? shared.sent / shared.total : 0 }
                else if (!isJob && shared?.status === 'error' && shared.error !== DUPLICATE) cardState = { kind: 'failed', error: shared.error ?? '' }
                else if (!isJob && shared?.status === 'done') cardState = { kind: 'owned' }
                else if (isJob && job.phase === 'downloading') cardState = { kind: 'downloading' }
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
                    meta={[regionCode(e.name), e.discs ? `${e.discs.length} discos` : null, formatBytes(e.size)].filter(Boolean).join(' · ')}
                    rating={info ? (brief?.rating ?? null) : undefined}
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


      {filtersOpen && (
        <FilterSheet
          section={filtersOpen}
          value={filters}
          onChange={setFilters}
          genres={genreCounts}
          results={shown.length}
          withData={base.filter((x) => x.info).length}
          total={base.length}
          onClose={() => setFiltersOpen(null)}
        />
      )}

      {sheetOpen && (
        <CollectionSheet
          current={loadedId}
          recents={recents}
          onLoad={(id) => {
            setCollectionFor(consoleId, id)
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
          data={ficha.data && { ...ficha.data, rating: info?.(baseName(ficha.entry.name))?.rating ?? null }}
          loading={ficha.loading}
          error={ficha.error}
          online={state.online}
          busy={busy}
          progress={job?.key === ficha.entry.name && job.phase !== 'done' ? job.progress : null}
          uploaded={job?.key === ficha.entry.name && job.phase === 'done' && !job.error}
          uploadError={job?.key === ficha.entry.name ? job.error ?? null : null}
          archiveHref={archiveName ? archiveFileUrl(loadedId, archiveName, ficha.entry.name) : downloadUrl(loadedId, ficha.entry.pack ?? ficha.entry.name)}
          onUpload={() => upload(ficha.entry)}
          onClose={() => setFicha(null)}
        />
      )}
      </>
      )}
    </main>
  )
}


/** Accent of each platform card (pairs with the console's box art colours). */
const PLATFORM_LOOK: Record<ConsoleId, { from: string; to: string; glyph: string }> = {
  nes: { from: '#b91c1c', to: '#3b0a0a', glyph: 'NES' },
  snes: { from: '#6d28d9', to: '#1e1b4b', glyph: 'SNES' },
  gb: { from: '#4d6b3c', to: '#1f2d1a', glyph: 'GB' },
  gbc: { from: '#7c3aed', to: '#2e1065', glyph: 'GBC' },
  gba: { from: '#4f46e5', to: '#1e1b4b', glyph: 'GBA' },
  md: { from: '#1f2937', to: '#030712', glyph: 'MD' },
  sms: { from: '#b91c1c', to: '#1f2937', glyph: 'SMS' },
  n64: { from: '#15803d', to: '#14213d', glyph: 'N64' },
  neogeo: { from: '#b91c1c', to: '#111827', glyph: 'NEO' },
  psx: { from: '#475569', to: '#0f172a', glyph: 'PS1' },
}

/** First step of Buscar: choose the platform; its archive.org collection loads only then. */
function PlatformPicker({
  recents,
  uploads,
  onPick,
}: {
  recents: RecentCollection[]
  uploads: number
  onPick: (c: ConsoleId) => void
}) {
  return (
    <section className="picker" aria-label="Elige plataforma">
      <h2 className="picker-title">¿Qué quieres buscar?</h2>
      <p className="picker-sub">Elige una plataforma para explorar su colección de archive.org.</p>
      <div className="picker-grid">
        {CONSOLES.map((c, i) => {
          const id = collectionFor(c.id)
          const known = recents.find((r) => r.id === id)
          const look = PLATFORM_LOOK[c.id]
          return (
            <button
              key={c.id}
              className="picker-card"
              style={{ background: `linear-gradient(150deg, ${look.from}, ${look.to})`, animationDelay: `${i * 60}ms` }}
              onClick={() => onPick(c.id)}
            >
              <img className="picker-logo" src={`/logos/logo_${c.id}.svg`} alt="" aria-hidden />
              <span className="picker-glyph" aria-hidden>
                {look.glyph}
              </span>
              <span className="picker-name">{c.name}</span>
              <span className="picker-meta">
                {known ? `${known.title || known.id}${known.count ? ` · ${known.count} juegos` : ''}` : id ? id : 'Sin colección: elige una al entrar'}
              </span>
            </button>
          )
        })}
      </div>
      {uploads > 0 && <p className="picker-note">Tienes {uploads === 1 ? 'una subida' : `${uploads} subidas`} en curso: la ves en Subir.</p>}
    </section>
  )
}
