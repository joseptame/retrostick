import { useState } from 'react'
import type { AppState } from '../App'
import { Revisions, saveToPhone } from '../components/Revisions'
import { Cover } from '../components/ui'
import type { DriveFile } from '../lib/drive'
import { formatBytes, formatWhen } from '../lib/format'
import { gameForSave } from '../lib/library'
import { displayTitle, saveLabel, saveOf } from '../lib/names'

export function Partidas({ state }: { state: AppState }) {
  const [revisionsOf, setRevisionsOf] = useState<DriveFile | null>(null)
  const { library } = state
  if (!library) return <main className="screen" />
  const saves = library.saves.filter((s) => saveOf(s.name))

  return (
    <main className="screen">
      <h1>Partidas</h1>
      <p className="sub">Guardadas en Drive desde el stick · las más recientes primero</p>
      {saves.length === 0 && <p className="sub">Todavía no hay partidas. Aparecerán cuando juegues en el stick.</p>}
      {saves.map((s) => {
        const game = gameForSave(s, library.games)
        const name = saveOf(s.name)!.game
        return (
          <div key={s.id} className="save with-cover">
            {game ? (
              <Cover console={game.console} name={game.name} title={game.title} className="thumb small" />
            ) : (
              <div className="cover thumb small" />
            )}
            <div className="grow">
              <b>{game?.title ?? displayTitle(name)}</b>
              <small>
                {saveLabel(s.name)} · {formatWhen(s.modifiedTime)} · {formatBytes(Number(s.size ?? 0))}
              </small>
              <div className="actions">
                <button
                  className="pill"
                  disabled={!state.online}
                  onClick={() => saveToPhone(s).catch((e) => state.notify(`No se pudo descargar: ${e.message ?? e}`))}
                >
                  Descargar
                </button>
                <button className="pill" disabled={!state.online} onClick={() => setRevisionsOf(s)}>
                  Versiones
                </button>
              </div>
            </div>
          </div>
        )
      })}
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
