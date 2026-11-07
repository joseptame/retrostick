import { useMemo, useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { CoverCard } from '../components/SearchCard'
import { Segmented, StickySearch } from '../components/ui'
import { CONSOLES, type ConsoleId } from '../lib/consoles'
import { formatAgo, formatBytes } from '../lib/format'
import { setInStick, stickSpace, type Game } from '../lib/library'
import { regionCode } from '../lib/names'

type Where = 'all' | 'stick' | 'drive'

const norm = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()

export function Coleccion({ state, onUpload, onSearch }: { state: AppState; onUpload: () => void; onSearch: () => void }) {
  const { library } = state
  const [filter, setFilter] = useState<ConsoleId | 'all'>('all')
  const [where, setWhere] = useState<Where>('all')
  const [query, setQuery] = useState('')

  const games = useMemo(() => {
    if (!library) return []
    const q = norm(query.trim())
    return library.games.filter(
      (g) =>
        (filter === 'all' || g.console === filter) &&
        (where === 'all' || (where === 'stick') === g.inStick) &&
        (!q || norm(g.title).includes(q) || norm(g.name).includes(q)),
    )
  }, [library, filter, where, query])

  if (!library) return <main className="screen" />

  const { used, capacity } = stickSpace(library)
  const over = used > capacity
  const pct = Math.min(100, (used / capacity) * 100)
  const inStick = library.games.filter((g) => g.inStick).length
  const stale = library.status && Date.now() - new Date(library.status.updatedAt).getTime() > 24 * 3600 * 1000

  async function toggle(game: Game, value: boolean) {
    if (!state.folders || !state.online) return
    // Optimistic update with rollback (README 7.4)
    const flip = (v: boolean) =>
      state.setLibrary((l) => ({ ...l, games: l.games.map((g) => (g.key === game.key ? { ...g, inStick: v } : g)) }))
    flip(value)
    try {
      await setInStick(game, value, state.folders)
      state.notify(value ? `${game.title}: irá al stick en la próxima sincronización` : `${game.title}: se quitará del stick`)
    } catch (e) {
      flip(!value)
      state.notify(`No se pudo cambiar: ${e instanceof Error ? e.message : e}`)
    }
  }

  if (library.games.length === 0) {
    return (
      <main className="screen">
        <div className="empty-hero">
          <span className="empty-ico">
            <Icon name="gamepad" />
          </span>
          <h2>Tu colección está vacía</h2>
          <p>Busca juegos en archive.org o sube tus propias copias. Se guardan en tu Google Drive.</p>
          <div className="empty-actions">
            <button className="cta" onClick={onSearch}>
              <Icon name="search" strokeWidth={2.6} /> Buscar juegos
            </button>
            <button className="cta ghost" onClick={onUpload}>
              <Icon name="upload" strokeWidth={2.4} /> Subir copias
            </button>
          </div>
        </div>
      </main>
    )
  }

  return (
    <main className="screen">
      <section className={`meter ${over ? 'over' : ''}`} aria-label="Espacio en el stick">
        <div className="meter-top">
          <span className="meter-ico">
            <Icon name="tv" />
          </span>
          <div className="meter-txt">
            <b>En el stick</b>
            <small>
              <span className={`dot ${library.status ? (stale ? 'stale' : 'ok') : ''}`} aria-hidden />
              {library.status ? `Sincronizado ${formatAgo(library.status.updatedAt)}` : 'Aún no ha sincronizado'}
            </small>
          </div>
          <div className="meter-num">
            <b>{formatBytes(used)}</b>
            <small>
              de {formatBytes(capacity)}
              {library.status ? '' : ' (est.)'}
            </small>
          </div>
        </div>
        <div className="meter-bar" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
          <i style={{ width: `${pct}%` }} />
        </div>
        {over && <p className="meter-warn">Los juegos marcados no caben. Quita alguno del stick.</p>}
      </section>

      <Segmented
        label="Consola"
        options={[{ id: 'all' as const, label: 'Todos' }, ...CONSOLES.map((c) => ({ id: c.id, label: c.short }))]}
        value={filter}
        onChange={setFilter}
      />

      <StickySearch value={query} onChange={setQuery} placeholder="Buscar en tu colección…" label="Buscar en tu colección" />

      <div className="pills" role="radiogroup" aria-label="Dónde está">
        {(
          [
            ['all', `Todos · ${library.games.length}`],
            ['stick', `En el stick · ${inStick}`],
            ['drive', `Solo Drive · ${library.games.length - inStick}`],
          ] as const
        ).map(([id, label]) => (
          <button key={id} role="radio" aria-checked={where === id} className={`pill2 ${where === id ? 'on' : ''}`} onClick={() => setWhere(id)}>
            {label}
          </button>
        ))}
      </div>

      {games.length === 0 ? (
        <div className="empty">No hay juegos con estos filtros.</div>
      ) : (
        <div className="grid gp-grid">
          {games.map((g, i) => (
            <CoverCard
              key={g.key}
              console={g.console}
              fileName={g.name}
              title={g.title}
              meta={[g.inStick ? 'En el stick' : regionCode(g.name), formatBytes(g.size)].filter(Boolean).join(' · ')}
              metaTone={g.inStick ? 'ok' : undefined}
              style={i < 12 ? { animationDelay: `${i * 40}ms` } : { animation: 'none' }}
              onOpen={() => state.openGame(g.key)}
              action={
                <button
                  className={`gp-btn ${g.inStick ? 'ok' : 'off'}`}
                  aria-pressed={g.inStick}
                  aria-label={g.inStick ? `Quitar ${g.title} del stick` : `Llevar ${g.title} al stick`}
                  disabled={!state.online}
                  onClick={(ev) => {
                    ev.stopPropagation()
                    toggle(g, !g.inStick)
                  }}
                >
                  <Icon name={g.inStick ? 'check' : 'tv'} strokeWidth={g.inStick ? 3.2 : 2.2} />
                </button>
              }
            />
          ))}
        </div>
      )}
    </main>
  )
}
