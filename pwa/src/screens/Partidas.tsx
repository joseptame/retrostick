import { useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { Revisions, saveToPhone } from '../components/Revisions'
import { Cover } from '../components/ui'
import type { DriveFile } from '../lib/drive'
import { formatAgo, formatBytes, formatWhen } from '../lib/format'
import { gameForSave } from '../lib/library'
import { displayTitle, saveLabel, saveOf } from '../lib/names'

const DAY = 24 * 3600 * 1000

/** "Hoy", "Ayer", "Esta semana", "Este mes", "Anteriores" by the save's date. */
function groupOf(iso: string, now = new Date()): string {
  const startToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const t = new Date(iso).getTime()
  if (t >= startToday) return 'Hoy'
  if (t >= startToday - DAY) return 'Ayer'
  if (t >= startToday - 6 * DAY) return 'Esta semana'
  if (t >= startToday - 30 * DAY) return 'Este mes'
  return 'Anteriores'
}

/** Saves newer than `newSince` (ms) are marked as new; 0 marks none. */
export function Partidas({ state, newSince }: { state: AppState; newSince: number }) {
  const [revisionsOf, setRevisionsOf] = useState<DriveFile | null>(null)
  const { library } = state
  if (!library) return <main className="screen" />
  const saves = library.saves.filter((s) => saveOf(s.name))

  const groups: { name: string; saves: DriveFile[] }[] = []
  for (const s of saves) {
    const name = groupOf(s.modifiedTime)
    const last = groups[groups.length - 1]
    if (last?.name === name) last.saves.push(s)
    else groups.push({ name, saves: [s] })
  }

  if (saves.length === 0) {
    return (
      <main className="screen">
        <div className="empty-hero">
          <span className="empty-ico">
            <Icon name="save" />
          </span>
          <h2>Aún no hay partidas</h2>
          <p>Cuando juegues en el stick, tus partidas se guardarán solas en Drive y aparecerán aquí.</p>
        </div>
      </main>
    )
  }

  return (
    <main className="screen">
      <p className="lead">
        <Icon name="cloud" /> Se copian solas a Drive desde el stick. Puedes descargarlas o volver a una versión anterior.
      </p>

      {groups.map((g) => (
        <section key={g.name}>
          <h3 className="sec2">{g.name}</h3>
          <div className="rows">
            {g.saves.map((s) => {
              const game = gameForSave(s, library.games)
              const info = saveOf(s.name)!
              const isNew = newSince > 0 && Date.parse(s.modifiedTime) > newSince
              return (
                <div key={s.id} className={`row-card save-row ${isNew ? 'new' : ''}`}>
                  {game ? (
                    <Cover console={game.console} name={game.name} title={game.title} className="row-thumb" />
                  ) : (
                    <span className="row-ico">
                      <Icon name="gamepad" />
                    </span>
                  )}
                  <div className="grow">
                    <b>{game?.title ?? displayTitle(info.game)}</b>
                    <small title={formatWhen(s.modifiedTime)}>
                      {isNew && <span className="new-chip">Nueva</span>}
                      {saveLabel(s.name)} · {formatAgo(s.modifiedTime)} · {formatBytes(Number(s.size ?? 0))}
                    </small>
                  </div>
                  <button
                    className="icon-btn"
                    disabled={!state.online}
                    aria-label={`Descargar ${saveLabel(s.name)}`}
                    onClick={() => saveToPhone(s).catch((e) => state.notify(`No se pudo descargar: ${e.message ?? e}`))}
                  >
                    <Icon name="download" />
                  </button>
                  <button className="icon-btn" disabled={!state.online} aria-label={`Versiones de ${saveLabel(s.name)}`} onClick={() => setRevisionsOf(s)}>
                    <Icon name="history" />
                  </button>
                </div>
              )
            })}
          </div>
        </section>
      ))}

      {revisionsOf && (
        <Revisions
          file={revisionsOf}
          onClose={() => setRevisionsOf(null)}
          onRestored={() => {
            state.notify('Copia restaurada: el stick la recibirá en la próxima sincronización')
            state.refresh()
          }}
        />
      )}
    </main>
  )
}
