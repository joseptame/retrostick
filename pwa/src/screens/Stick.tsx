import { useState } from 'react'
import type { AppState } from '../App'
import { PairSheet } from '../components/PairSheet'
import { Icon, type IconName } from '../components/icons'
import { accountEmail } from '../lib/auth'
import { formatAgo, formatBytes, formatWhen } from '../lib/format'

/** status.json written by the TV launcher (README 7.4 "Stick", 8.3). */
export function Stick({ state, onSignOut }: { state: AppState; onSignOut: () => void }) {
  const status = state.library?.status
  const stale = status && Date.now() - new Date(status.updatedAt).getTime() > 24 * 3600 * 1000
  const total = status ? status.romsBytes + status.freeBytes : 0
  const pct = status && total ? (status.romsBytes / total) * 100 : 0
  const [pairing, setPairing] = useState(false)

  return (
    <main className="screen">
      <section className={`stick-hero ${!status ? 'none' : stale ? 'stale' : 'ok'}`}>
        <span className="stick-hero-ico">
          <Icon name="tv" />
          <span className="dot" aria-hidden />
        </span>
        <div className="grow">
          <b>{!status ? 'Sin conectar' : stale ? 'Sin sincronizar' : 'Al día'}</b>
          <small>
            {!status
              ? 'El stick todavía no ha enviado su estado. Conéctalo a Drive desde Retro Ajustes.'
              : `Última sincronización ${formatAgo(status.updatedAt)}`}
          </small>
        </div>
      </section>

      {status && (
        <>
          <section className="meter" aria-label="Almacenamiento del stick">
            <div className="meter-top">
              <span className="meter-ico">
                <Icon name="disk" />
              </span>
              <div className="meter-txt">
                <b>Almacenamiento</b>
                <small>{formatBytes(status.freeBytes)} libres</small>
              </div>
              <div className="meter-num">
                <b>{formatBytes(status.romsBytes)}</b>
                <small>en juegos</small>
              </div>
            </div>
            <div className="meter-bar" role="progressbar" aria-valuenow={Math.round(pct)} aria-valuemin={0} aria-valuemax={100}>
              <i style={{ width: `${pct}%` }} />
            </div>
          </section>

          <div className="tiles">
            <Tile icon="gamepad" label="Mando" value={status.controllers.length ? status.controllers.join(', ') : 'Ninguno'} />
            <Tile icon="info" label="Versión del menú" value={status.appVersion} />
            <Tile icon="history" label="Sincronizado" value={formatWhen(status.updatedAt)} wide />
          </div>
        </>
      )}

      <h3 className="sec2">RetroShop</h3>
      <div className="rows">
        <button className="row-card row-btn" onClick={() => setPairing(true)}>
          <span className="row-ico">
            <Icon name="tv" />
          </span>
          <div className="grow">
            <b>Escanear QR de la tele</b>
            <small>Vincula la tienda del stick a tu cuenta (Retro Ajustes → RetroShop)</small>
          </div>
        </button>
      </div>
      {pairing && <PairSheet state={state} onClose={() => setPairing(false)} />}

      <h3 className="sec2">Cuenta</h3>
      <div className="rows">
        <div className="row-card">
          <span className="row-ico">
            <Icon name="cloud" />
          </span>
          <div className="grow">
            <b>Google Drive</b>
            <small>{accountEmail() ?? '—'}</small>
          </div>
        </div>
        <button className="row-card row-btn" onClick={() => confirm('¿Desconectar Google Drive en este móvil?') && onSignOut()}>
          <span className="row-ico">
            <Icon name="link" />
          </span>
          <div className="grow">
            <b>Desconectar Google Drive</b>
            <small>Tendrás que volver a introducir el código</small>
          </div>
        </button>
        <button
          className="row-card row-btn warn"
          onClick={() =>
            confirm('¿Cerrar el acceso a RetroStick en este móvil? Te pedirá usuario y contraseña.') && (location.href = '/__logout')
          }
        >
          <span className="row-ico">
            <Icon name="logout" />
          </span>
          <div className="grow">
            <b>Cerrar acceso</b>
            <small>Pedirá usuario y contraseña al volver</small>
          </div>
        </button>
      </div>

      <p className="foot-links">
        <a href="https://joseptame.github.io/retrostick/privacidad.html">Política de privacidad</a>
        <span aria-hidden>·</span>
        <a href="https://github.com/joseptame/retrostick">Código</a>
      </p>
    </main>
  )
}

function Tile({ icon, label, value, wide }: { icon: IconName; label: string; value: string; wide?: boolean }) {
  return (
    <div className={`tile ${wide ? 'wide' : ''}`}>
      <Icon name={icon} />
      <small>{label}</small>
      <b>{value}</b>
    </div>
  )
}
