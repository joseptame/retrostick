import type { AppState } from '../App'
import { Banner } from '../components/ui'
import { accountEmail } from '../lib/auth'
import { formatAgo, formatBytes, formatWhen } from '../lib/format'

/** status.json written by the TV launcher (README 7.4 "Stick", 8.3). */
export function Stick({ state, onSignOut }: { state: AppState; onSignOut: () => void }) {
  const status = state.library?.status
  const stale = status && Date.now() - new Date(status.updatedAt).getTime() > 24 * 3600 * 1000

  return (
    <main className="screen">
      <h1>Stick</h1>
      {!status ? (
        <p className="sub">El stick todavía no ha enviado su estado. Conéctalo a Drive desde Retro Ajustes.</p>
      ) : (
        <>
          {stale && <Banner tone="warn">El stick no se ha sincronizado desde {formatWhen(status.updatedAt)}.</Banner>}
          <div className="card info-list">
            <Row k="Última sincronización" v={`${formatWhen(status.updatedAt)} (${formatAgo(status.updatedAt)})`} />
            <Row k="Espacio libre" v={formatBytes(status.freeBytes)} />
            <Row k="Juegos en el stick" v={formatBytes(status.romsBytes)} />
            <Row k="Mando conectado" v={status.controllers.length ? status.controllers.join(', ') : 'Ninguno'} />
            <Row k="Versión del menú" v={status.appVersion} />
          </div>
        </>
      )}

      <p className="sec">Cuenta</p>
      <div className="card info-list">
        <Row k="Google Drive" v={accountEmail() ?? '—'} />
      </div>
      <button className="btn secondary" onClick={() => confirm('¿Cerrar sesión en este móvil?') && onSignOut()}>
        Cerrar sesión
      </button>
      <p className="hint">
        <a href="https://joseptame.github.io/retrostick/privacidad.html">Política de privacidad</a> ·{' '}
        <a href="https://github.com/joseptame/retrostick">Código</a>
      </p>
    </main>
  )
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="info-row">
      <span>{k}</span>
      <b>{v}</b>
    </div>
  )
}
