import { useState } from 'react'
import type { AppState } from '../App'
import { Revisions, saveToPhone } from '../components/Revisions'
import { Cover, Switch } from '../components/ui'
import { consoleById } from '../lib/consoles'
import type { DriveFile } from '../lib/drive'
import { formatBytes, formatWhen } from '../lib/format'
import { deleteGame, savesFor, setInStick, type Game } from '../lib/library'
import { saveLabel } from '../lib/names'

export function Ficha({ game, state, onBack }: { game: Game; state: AppState; onBack: () => void }) {
  const [revisionsOf, setRevisionsOf] = useState<DriveFile | null>(null)
  const [busy, setBusy] = useState(false)
  const saves = state.library ? savesFor(game, state.library.saves) : []
  const console = consoleById(game.console)

  async function toggle(inStick: boolean) {
    if (!state.folders) return
    const flip = (v: boolean) =>
      state.setLibrary((l) => ({ ...l, games: l.games.map((g) => (g.key === game.key ? { ...g, inStick: v } : g)) }))
    flip(inStick)
    try {
      await setInStick(game, inStick, state.folders)
    } catch (e) {
      flip(!inStick)
      state.notify(`No se pudo cambiar: ${e instanceof Error ? e.message : e}`)
    }
  }

  async function remove() {
    if (!confirm(`¿Borrar «${game.title}» de tu colección?`)) return
    if (!confirm('¿Seguro? Irá a la papelera de Drive (30 días para recuperarlo). Las partidas NO se borran.')) return
    setBusy(true)
    try {
      await deleteGame(game)
      state.notify(`${game.title} enviado a la papelera de Drive`)
      onBack()
      state.refresh()
    } catch (e) {
      state.notify(`No se pudo borrar: ${e instanceof Error ? e.message : e}`)
      setBusy(false)
    }
  }

  async function downloadSave(file: DriveFile) {
    try {
      await saveToPhone(file)
    } catch (e) {
      state.notify(`No se pudo descargar: ${e instanceof Error ? e.message : e}`)
    }
  }

  return (
    <main className="screen">
      <button className="back" onClick={onBack}>
        ‹ {console.name}
      </button>
      <Cover console={game.console} name={game.name} title={game.title} className="big" />
      <h1 className="detail-title">{game.title}</h1>
      <p className="sub">{[console.name, game.region, formatBytes(game.size)].filter(Boolean).join(' · ')}</p>

      <div className={`toggle ${game.inStick ? 'on' : ''}`}>
        <div>
          <b>{game.inStick ? 'En el stick' : 'Solo en Drive'}</b>
          <small>{game.inStick ? 'Disponible en la TV tras sincronizar' : 'Actívalo para llevarlo al stick'}</small>
        </div>
        <Switch on={game.inStick} disabled={!state.online} label="En el stick" onChange={toggle} />
      </div>

      <p className="sec">Partidas guardadas · Drive</p>
      {saves.length === 0 && <p className="sub">Aún no hay partidas de este juego.</p>}
      {saves.map((s) => (
        <div key={s.id} className="save">
          <div>
            <b>{saveLabel(s.name)}</b>
            <small>{formatWhen(s.modifiedTime)}</small>
          </div>
          <div className="actions">
            <button className="pill" disabled={!state.online} onClick={() => downloadSave(s)}>
              Descargar
            </button>
            <button className="pill" disabled={!state.online} onClick={() => setRevisionsOf(s)}>
              Versiones
            </button>
          </div>
        </div>
      ))}

      <p className="sec">Archivos</p>
      {game.files.map((f) => (
        <p key={f.id} className="file">
          {f.name}
        </p>
      ))}

      <button className="btn danger" disabled={busy || !state.online} onClick={remove}>
        Borrar de la colección
      </button>

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
