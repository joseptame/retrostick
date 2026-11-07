import { formatBytes } from '../lib/format'
import { eta, isActive, type UploadItem } from '../lib/uploads'
import { Icon } from './icons'
import { Cover } from './ui'

/** "Subidas" in Subir: uploads in progress (bytes, speed, time left) and recent ones (done / failed). */
export function UploadsPanel({ uploads, onClear }: { uploads: UploadItem[]; onClear: () => void }) {
  if (uploads.length === 0) return null
  const finished = uploads.some((u) => !isActive(u))
  return (
    <section className="uploads" aria-label="Subidas">
      <h3 className="sec2 first">
        Subidas <span>{uploads.length}</span>
        {finished && (
          <button className="text-btn inline" onClick={onClear}>
            Limpiar
          </button>
        )}
      </h3>
      <div className="rows">
        {uploads.map((u) => (
          <UploadRow key={u.id} u={u} />
        ))}
      </div>
    </section>
  )
}

function UploadRow({ u }: { u: UploadItem }) {
  const active = isActive(u)
  const pct = u.total ? Math.min(100, (u.sent / u.total) * 100) : 0
  let line: string
  if (u.status === 'downloading') line = 'Descargando de archive.org…'
  else if (u.status === 'uploading')
    line = [`${formatBytes(u.sent)} / ${formatBytes(u.total)}`, u.speed ? `${formatBytes(u.speed)}/s` : null, eta(u)].filter(Boolean).join(' · ')
  else if (u.status === 'done') line = ['Subido', formatBytes(u.total), u.toStick ? 'al stick' : 'a la colección'].join(' · ')
  else line = u.error ?? 'Error'

  return (
    <div className={`row-card upload-row ${u.status}`}>
      <Cover console={u.console} name={u.coverName} title={u.title} className="row-thumb big" />
      <div className="grow">
        <b>{u.title}</b>
        {u.detail && <small className="upload-detail">{u.detail}</small>}
        {active && (
          <div className={`line-prog ${u.status === 'downloading' ? 'indet' : ''}`}>
            <i style={{ width: u.status === 'downloading' ? undefined : `${pct}%` }} />
          </div>
        )}
        <div className={`up-status ${u.status === 'error' ? 'error' : u.status === 'done' ? 'done' : 'uploading'}`}>
          {active && u.status === 'uploading' && <b className="upload-pct">{Math.round(pct)} %</b>}
          {line}
        </div>
      </div>
      {u.status === 'done' && (
        <span className="gp-btn ok sm" role="img" aria-label="Subido">
          <Icon name="check" strokeWidth={3.2} />
        </span>
      )}
      {u.status === 'error' && u.retry && (
        <button className="gp-btn err sm" onClick={u.retry} aria-label={`Reintentar ${u.title}`}>
          <Icon name="retry" strokeWidth={2.6} />
        </button>
      )}
      {active && <span className="upload-spin" aria-hidden />}
    </div>
  )
}
