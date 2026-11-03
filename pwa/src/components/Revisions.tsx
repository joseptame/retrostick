import { useEffect, useState } from 'react'
import { download, listRevisions, restoreRevision, type DriveFile, type Revision } from '../lib/drive'
import { formatBytes, formatWhen } from '../lib/format'
import { saveLabel } from '../lib/names'

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

  async function restore(r: Revision) {
    const ok = confirm(
      `¿Restaurar la copia del ${formatWhen(r.modifiedTime)}?\n\n` +
        'Cierra antes el juego en el stick. La copia actual seguirá en el historial de Drive.',
    )
    if (!ok) return
    setBusy(true)
    try {
      await restoreRevision(file.id, r.id)
      onRestored()
      onClose()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h2>{saveLabel(file.name)}</h2>
        <p className="sub">Copias guardadas en Drive</p>
        {error && <p className="error">{error}</p>}
        {!revisions && !error && <p className="sub">Cargando…</p>}
        {revisions?.map((r, i) => (
          <div key={r.id} className="save">
            <div>
              <b>{i === 0 ? 'Copia actual' : 'Copia anterior'}</b>
              <small>
                {formatWhen(r.modifiedTime)}
                {r.size ? ` · ${formatBytes(Number(r.size))}` : ''}
              </small>
            </div>
            {i > 0 && (
              <button className="pill" disabled={busy} onClick={() => restore(r)}>
                Restaurar
              </button>
            )}
          </div>
        ))}
        <button className="btn secondary" onClick={onClose}>
          Cerrar
        </button>
      </div>
    </div>
  )
}
