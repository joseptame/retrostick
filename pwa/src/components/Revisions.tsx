import { useEffect, useState } from 'react'
import { download, listRevisions, restoreRevision, type DriveFile, type Revision } from '../lib/drive'
import { formatAgo, formatBytes, formatWhen } from '../lib/format'
import { saveLabel } from '../lib/names'
import { Icon } from './icons'
import { Sheet, SheetHead } from './Sheet'

export async function saveToPhone(file: DriveFile) {
  const blob = await download(file.id)
  const url = URL.createObjectURL(blob)
  const a = Object.assign(document.createElement('a'), { href: url, download: file.name })
  a.click()
  setTimeout(() => URL.revokeObjectURL(url), 10_000)
}

/** Previous copies of a save kept by Drive; restoring makes an old one current (README 7.4). */
export function Revisions({ file, onClose, onRestored }: { file: DriveFile; onClose: () => void; onRestored: () => void }) {
  const [revisions, setRevisions] = useState<Revision[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    listRevisions(file.id).then(setRevisions).catch((e) => setError(String(e.message ?? e)))
  }, [file.id])

  return (
    <Sheet label={`Versiones de ${saveLabel(file.name)}`} onClose={onClose}>
      {(close) => {
        async function restore(r: Revision) {
          const ok = confirm(
            `¿Restaurar la copia del ${formatWhen(r.modifiedTime)}?\n\n` +
              'Cierra antes el juego en el stick. La copia actual seguirá en el historial de Drive.',
          )
          if (!ok) return
          setBusy(true)
          try {
            await restoreRevision(file.id, r.id)
            close(onRestored)
          } catch (e) {
            setError(e instanceof Error ? e.message : String(e))
            setBusy(false)
          }
        }
        // listRevisions returns the newest first: the current copy is on top.
        const list = revisions
        return (
          <>
            <SheetHead title={saveLabel(file.name)} sub="Copias guardadas en Drive. Restaura una si algo salió mal." onClose={() => close()} />
            {error && <p className="hint error">{error}</p>}
            {!list && !error && (
              <div className="rows" style={{ marginTop: 18 }}>
                {[0, 1, 2].map((i) => (
                  <div key={i} className="row-card">
                    <span className="sk row-ico" />
                    <div className="grow">
                      <span className="sk sk-line" style={{ width: '45%' }} />
                    </div>
                  </div>
                ))}
              </div>
            )}
            {list && (
              <div className="rows" style={{ marginTop: 18 }}>
                {list.map((r, i) => (
                  <div key={r.id} className={`row-card ${i === 0 ? 'current' : ''}`}>
                    <span className="row-ico">
                      <Icon name={i === 0 ? 'check' : 'history'} />
                    </span>
                    <div className="grow">
                      <b>{i === 0 ? 'Copia actual' : formatAgo(r.modifiedTime)}</b>
                      <small>
                        {formatWhen(r.modifiedTime)}
                        {r.size ? ` · ${formatBytes(Number(r.size))}` : ''}
                      </small>
                    </div>
                    {i > 0 && (
                      <button className="chip-btn" disabled={busy} onClick={() => restore(r)}>
                        Restaurar
                      </button>
                    )}
                  </div>
                ))}
              </div>
            )}
          </>
        )
      }}
    </Sheet>
  )
}
