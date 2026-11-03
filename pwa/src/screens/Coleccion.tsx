import { useState } from 'react'
import type { AppState } from '../App'
import { Banner, Cover, Switch } from '../components/ui'
import { CONSOLES, type ConsoleId } from '../lib/consoles'
import { formatAgo, formatBytes } from '../lib/format'
import { setInStick, stickSpace, type Game } from '../lib/library'

export function Coleccion({ state, onUpload }: { state: AppState; onUpload: () => void }) {
  const { library } = state
  const [filter, setFilter] = useState<ConsoleId | 'all'>('all')
  if (!library) return <main className="screen" />

  const games = library.games.filter((g) => filter === 'all' || g.console === filter)
  const { used, capacity } = stickSpace(library)
  const over = used > capacity
  const synced = library.status ? `sincronizado ${formatAgo(library.status.updatedAt)}` : 'el stick aún no ha sincronizado'

  async function toggle(game: Game, inStick: boolean) {
    if (!state.folders || !state.online) return
    // Optimistic update with rollback (README 7.4)
    const flip = (value: boolean) =>
      state.setLibrary((l) => ({ ...l, games: l.games.map((g) => (g.key === game.key ? { ...g, inStick: value } : g)) }))
    flip(inStick)
    try {
      await setInStick(game, inStick, state.folders)
      state.notify(inStick ? `${game.title}: irá al stick en la próxima sincronización` : `${game.title}: se quitará del stick`)
    } catch (e) {
      flip(!inStick)
      state.notify(`No se pudo cambiar: ${e instanceof Error ? e.message : e}`)
    }
  }

  return (
    <main className="screen">
      <h1>Mi colección</h1>
      <p className="sub">
        {library.games.length} {library.games.length === 1 ? 'juego' : 'juegos'} · {synced}
      </p>

      <div className="card space">
        <div className="row">
          <span>En el stick</span>
          <span>
            <b>{formatBytes(used)}</b> de {formatBytes(capacity)}
            {library.status ? '' : ' (estimado)'}
          </span>
        </div>
        <div className="bar">
          <i style={{ width: `${Math.min(100, (used / capacity) * 100)}%` }} className={over ? 'over' : ''} />
        </div>
      </div>
      {over && <Banner tone="warn">Los juegos marcados para el stick no caben. Quita alguno.</Banner>}

      <div className="chips-row">
        {[{ id: 'all' as const, short: 'Todos' }, ...CONSOLES].map((c) => (
          <button key={c.id} className={`chip-tab ${filter === c.id ? 'on' : ''}`} onClick={() => setFilter(c.id)}>
            {c.short}
          </button>
        ))}
      </div>

      {games.length === 0 ? (
        <div className="empty">
          <p>Aún no hay juegos aquí.</p>
          <button className="btn" onClick={onUpload}>
            Subir copias
          </button>
        </div>
      ) : (
        <div className="grid">
          {games.map((g) => (
            <div key={g.key} className="game-card" onClick={() => state.openGame(g.key)}>
              <Cover console={g.console} name={g.name} title={g.title} />
              <div className="ft">
                <div className="nm">{g.title}</div>
                <div className="st">
                  <span className={g.inStick ? 'ok' : ''}>{g.inStick ? 'En el stick' : 'Solo en Drive'}</span>
                  <Switch on={g.inStick} disabled={!state.online} label={`${g.title} en el stick`} onChange={(v) => toggle(g, v)} />
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </main>
  )
}
