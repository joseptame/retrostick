import { useMemo, useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { CoverCard } from '../components/SearchCard'
import { Segmented, StickySearch } from '../components/ui'
import { CONSOLES, type ConsoleId } from '../lib/consoles'
import { formatAgo, formatBytes } from '../lib/format'
import { chooseStick, chosenStick, confirmRoom, onStick, picksGames, setOnStick, stickSpace, type Game } from '../lib/library'
import { regionCode } from '../lib/names'

type Where = 'all' | 'stick' | 'drive'

const norm = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()

export function Coleccion({ state, onUpload, onSearch }: { state: AppState; onUpload: () => void; onSearch: () => void }) {
  const { library } = state
  const [filter, setFilter] = useState<ConsoleId | 'all'>('all')
  const [where, setWhere] = useState<Where>('all')
  const [query, setQuery] = useState('')
  // Several sticks on the account: which one the «en el stick» marks and the space are about
  const [stickId, setStickId] = useState<string | undefined>(() => (library ? chosenStick(library)?.id : undefined))
  const sticks = library?.sticks ?? []
  const stick = (stickId && sticks.find((s) => s.id === stickId)) || library?.status || null
  const has = (g: Game) => (library ? onStick(library, stick, g) : { on: g.inStick, pending: false })

  const games = useMemo(() => {
    if (!library) return []
    const q = norm(query.trim())
    return library.games.filter(
      (g) =>
        (filter === 'all' || g.console === filter) &&
        (where === 'all' || (where === 'stick') === onStick(library, stick, g).on) &&
        (!q || norm(g.title).includes(q) || norm(g.name).includes(q)),
    )
  }, [library, stick, filter, where, query])

  if (!library) return <main className="screen" />

  const { used, capacity } = stickSpace(library, stick)
  const over = used > capacity
  const pct = Math.min(100, (used / capacity) * 100)
  const inStick = library.games.filter((g) => has(g).on).length
  const stale = stick && Date.now() - new Date(stick.updatedAt).getTime() > 24 * 3600 * 1000
  const stickName = sticks.length > 1 ? (stick?.name ?? 'el stick') : 'el stick'

  async function toggle(game: Game, value: boolean) {
    if (!state.folders || !state.online || !library) return
    // Optimistic update with rollback (README 7.4)
    const flip = (v: boolean) =>
      state.setLibrary((l) => ({ ...l, games: l.games.map((g) => (g.key === game.key ? { ...g, inStick: v } : g)) }))
    if (value && !confirmRoom(library, stick, game, stickName)) return
    const picking = picksGames(stick)
    if (!picking) flip(value)
    try {
      const request = await setOnStick(stick, game, value, state.folders)
      if (request) state.setLibrary((l) => ({ ...l, requests: [...(l.requests ?? []), request] }))
      state.notify(value ? `${game.title}: irá a ${stickName} en la próxima sincronización` : `${game.title}: se quitará de ${stickName}`)
    } catch (e) {
      if (!picking) flip(!value)
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
      {sticks.length > 1 && (
        <div className="pills stick-pills" role="radiogroup" aria-label="Qué stick">
          {sticks.map((s, i) => (
            <button
              key={s.id ?? i}
              role="radio"
              aria-checked={s === stick}
              className={`pill2 ${s === stick ? 'on' : ''}`}
              onClick={() => {
                if (!s.id) return
                chooseStick(s.id)
                setStickId(s.id)
              }}
            >
              <Icon name="tv" /> {s.name ?? `Stick ${i + 1}`}
            </button>
          ))}
        </div>
      )}
      <section className={`meter ${over ? 'over' : ''}`} aria-label="Espacio en el stick">
        <div className="meter-top">
          <span className="meter-ico">
            <Icon name="tv" />
          </span>
          <div className="meter-txt">
            <b>{sticks.length > 1 ? `En ${stickName}` : 'En el stick'}</b>
            <small>
              <span className={`dot ${stick ? (stale ? 'stale' : 'ok') : ''}`} aria-hidden />
              {stick ? `Sincronizado ${formatAgo(stick.updatedAt)}` : 'Aún no ha sincronizado'}
            </small>
          </div>
          <div className="meter-num">
            <b>{formatBytes(used)}</b>
            <small>
              de {formatBytes(capacity)}
              {stick ? '' : ' (est.)'}
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
          {games.map((g, i) => {
            const { on, pending } = has(g)
            return (
            <CoverCard
              key={g.key}
              console={g.console}
              fileName={g.name}
              title={g.title}
              meta={[pending ? (on ? 'Pedido' : 'Se quitará') : on ? 'En el stick' : regionCode(g.name), formatBytes(g.size)]
                .filter(Boolean)
                .join(' · ')}
              metaTone={on && !pending ? 'ok' : undefined}
              style={i < 12 ? { animationDelay: `${i * 40}ms` } : { animation: 'none' }}
              onOpen={() => state.openGame(g.key)}
              action={
                <button
                  className={`gp-btn ${on ? 'ok' : 'off'}`}
                  aria-pressed={on}
                  aria-label={on ? `Quitar ${g.title} de ${stickName}` : `Llevar ${g.title} a ${stickName}`}
                  disabled={!state.online}
                  onClick={(ev) => {
                    ev.stopPropagation()
                    toggle(g, !on)
                  }}
                >
                  <Icon name={on ? 'check' : 'tv'} strokeWidth={on ? 3.2 : 2.2} />
                </button>
              }
            />
            )
          })}
        </div>
      )}
    </main>
  )
}
