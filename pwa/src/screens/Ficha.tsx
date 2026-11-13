import { useEffect, useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { Revisions, saveToPhone } from '../components/Revisions'
import { coverGradient, fitWideArt, Switch, useCoverUrl } from '../components/ui'
import { consoleById } from '../lib/consoles'
import type { DriveFile } from '../lib/drive'
import { formatAgo, formatBytes, formatWhen } from '../lib/format'
import { fetchIgdb, type IgdbGame } from '../lib/igdb'
import { loadLookup } from '../lib/igdbdb'
import { chosenStick, deleteGame, onStick, picksGames, savesFor, setOnStick, type Game } from '../lib/library'
import { languages, regionCode, saveLabel, saveOf } from '../lib/names'

export function Ficha({ game, state, onBack }: { game: Game; state: AppState; onBack: () => void }) {
  const [revisionsOf, setRevisionsOf] = useState<DriveFile | null>(null)
  const [busy, setBusy] = useState(false)
  const [info, setInfo] = useState<IgdbGame | null>(null)
  /** Press score from the prebuilt data (Wikipedia / IGDB critics), same as the cards */
  const [press, setPress] = useState<number | null>(null)
  const [expanded, setExpanded] = useState(false)
  const { url } = useCoverUrl(game.console, game.name)
  const saves = state.library ? savesFor(game, state.library.saves) : []
  const console = consoleById(game.console)

  useEffect(() => {
    window.scrollTo({ top: 0 })
    let alive = true
    setInfo(null)
    setPress(null)
    if (state.online) fetchIgdb(game.title, game.console).then((d) => alive && setInfo(d)).catch(() => {})
    loadLookup(game.console)
      .then((look) => alive && setPress(look(game.name)?.rating ?? null))
      .catch(() => {})
    return () => {
      alive = false
    }
  }, [game.key, game.title, game.name, game.console, state.online])

  // Several sticks on the account: the one chosen in Colección
  const stick = state.library ? chosenStick(state.library) : null
  const several = (state.library?.sticks?.length ?? 0) > 1
  const where = several && stick?.name ? stick.name : 'el stick'
  const { on, pending } = state.library ? onStick(state.library, stick, game) : { on: game.inStick, pending: false }

  async function toggle(inStick: boolean) {
    if (!state.folders || !state.library) return
    const flip = (v: boolean) =>
      state.setLibrary((l) => ({ ...l, games: l.games.map((g) => (g.key === game.key ? { ...g, inStick: v } : g)) }))
    const picking = picksGames(stick)
    if (!picking) flip(inStick)
    try {
      const request = await setOnStick(stick, game, inStick, state.folders)
      if (request) state.setLibrary((l) => ({ ...l, requests: [...(l.requests ?? []), request] }))
    } catch (e) {
      if (!picking) flip(!inStick)
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

  const desc = info?.description
  const longDesc = (desc?.length ?? 0) > 220
  const langs = languages(game.files[0]?.name ?? game.name)

  return (
    <main className="screen detail">
      <div className="detail-hero" aria-hidden>
        {url ? <img src={url} alt="" /> : <div style={{ background: coverGradient(game.title) }} />}
      </div>

      <button className="round-btn detail-back" onClick={onBack} aria-label="Volver">
        <Icon name="back" strokeWidth={2.6} />
      </button>

      <div className="detail-head">
        <div className="detail-cover" style={url ? undefined : { background: coverGradient(game.title) }}>
          {url ? <img src={url} alt="" onLoad={(e) => fitWideArt(e.currentTarget)} /> : <span>{game.title.replace(/^The /, '').charAt(0)}</span>}
        </div>
        <div className="detail-title">
          <h1>{game.title}</h1>
          <div className="tags">
            <span className="tag">{console.short}</span>
            {regionCode(game.name) && <span className="tag">{regionCode(game.name)}</span>}
            <span className="tag">{formatBytes(game.size)}</span>
            {info?.year && <span className="tag">{info.year}</span>}
            {press != null && (
              <span className="tag star" title="Nota de prensa">
                <Icon name="sparkle" /> {press.toFixed(1)}
              </span>
            )}
          </div>
          {langs.names.length > 0 && (
            <div className="gs-langs" title={langs.listed ? undefined : 'Según la región de esta versión'}>
              <span>Idiomas</span>
              {langs.names.map((l) => (
                <b key={l} className={l === 'Español' ? 'es' : undefined}>
                  {l}
                </b>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className={`stick-card ${on ? 'on' : ''}`}>
        <span className="stick-ico">
          <Icon name="tv" />
        </span>
        <div className="grow">
          <b>{on ? (several ? `En ${where}` : 'En el stick') : 'Solo en Drive'}</b>
          <small>
            {pending
              ? on
                ? `Pedido: ${where} lo descargará al sincronizar`
                : `${where} lo quitará al sincronizar`
              : on
                ? 'Disponible en la TV tras sincronizar'
                : `Actívalo para llevarlo a ${where}`}
          </small>
        </div>
        <Switch on={on} disabled={!state.online} label="En el stick" onChange={toggle} />
      </div>

      {info && (info.genres.length > 0 || desc) && (
        <section className="fade-in">
          {info.genres.length > 0 && (
            <div className="gs-genres">
              {info.genres.map((g) => (
                <span key={g} className="gs-genre">
                  {g}
                </span>
              ))}
            </div>
          )}
          {desc && (
            <div className="gs-desc-wrap">
              <p className={`gs-desc ${longDesc && !expanded ? 'clamped' : ''}`}>{desc}</p>
              {longDesc && (
                <button className="gs-more" onClick={() => setExpanded((x) => !x)}>
                  {expanded ? 'Ver menos' : 'Leer más'}
                </button>
              )}
            </div>
          )}
        </section>
      )}

      <h3 className="sec2">
        Partidas guardadas <span>{saves.length}</span>
      </h3>
      {saves.length === 0 ? (
        <p className="muted-box">Aún no hay partidas de este juego. Aparecerán cuando juegues en el stick.</p>
      ) : (
        <div className="rows">
          {saves.map((s) => {
            const kind = saveOf(s.name)?.kind
            return (
              <div key={s.id} className="row-card">
                <span className={`row-ico ${kind ?? ''}`}>
                  <Icon name={kind === 'state' ? 'bolt' : kind === 'auto' ? 'history' : 'save'} />
                </span>
                <div className="grow">
                  <b>{saveLabel(s.name)}</b>
                  <small title={formatWhen(s.modifiedTime)}>
                    {formatAgo(s.modifiedTime)} · {formatBytes(Number(s.size ?? 0))}
                  </small>
                </div>
                <button className="icon-btn" disabled={!state.online} onClick={() => downloadSave(s)} aria-label={`Descargar ${saveLabel(s.name)}`}>
                  <Icon name="download" />
                </button>
                <button className="icon-btn" disabled={!state.online} onClick={() => setRevisionsOf(s)} aria-label={`Versiones de ${saveLabel(s.name)}`}>
                  <Icon name="history" />
                </button>
              </div>
            )
          })}
        </div>
      )}

      <h3 className="sec2">
        Archivos <span>{game.files.length}</span>
      </h3>
      <div className="rows">
        {game.files.map((f) => (
          <div key={f.id} className="row-card file-row">
            <span className="row-ico">
              <Icon name="file" />
            </span>
            <div className="grow">
              <b>{f.name}</b>
              {f.size && <small>{formatBytes(Number(f.size))}</small>}
            </div>
          </div>
        ))}
      </div>

      <button className="danger-btn" disabled={busy || !state.online} onClick={remove}>
        <Icon name="trash" /> Borrar de la colección
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
