import { useEffect, useState } from 'react'
import type { AppState } from '../App'
import { Icon } from './icons'

interface ReportRow {
  id: string
  at: number
  user: string
  device: string
}

interface Report extends ReportRow {
  text: string
}

async function call<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  const body = (await res.json().catch(() => ({}))) as T & { error?: string }
  if (!res.ok) throw new Error(body.error ?? `Error ${res.status}`)
  return body
}

/**
 * Stick → Informes de sticks (admin only): what a stick or TV sent from Retro Ajustes → Enviar informe (api/report.ts,
 * Report.kt), to see what is wrong on a friend's TV without having it at hand. Each one opens to its text, to copy.
 */
export function ReportsSection({ state }: { state: AppState }) {
  const [admin, setAdmin] = useState(false)
  const [rows, setRows] = useState<ReportRow[] | null>(null)
  const [open, setOpen] = useState<Report | null>(null)
  const [busy, setBusy] = useState(false)

  const load = async () => setRows(await call<ReportRow[]>('/api/report').catch(() => []))
  useEffect(() => {
    void call<{ admin: boolean }>('/api/me').then((m) => {
      setAdmin(m.admin)
      if (m.admin) void load()
    }).catch(() => setAdmin(false))
  }, [])

  if (!admin) return null
  const when = (ms: number) => new Date(ms).toLocaleString('es-ES', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })

  const show = async (r: ReportRow) => {
    if (open?.id === r.id) return setOpen(null)
    setBusy(true)
    try {
      setOpen(await call<Report>(`/api/report?id=${encodeURIComponent(r.id)}`))
    } catch (e) {
      state.notify(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h3 className="sec2">Informes de sticks</h3>
      <p className="lead">
        <Icon name="info" />
        <span>Lo que envía un stick o una tele desde Retro Ajustes → Enviar informe. Se guardan 30 días.</span>
      </p>
      <div className="rows" style={{ marginTop: 10 }}>
        {rows === null && <div className="row-card"><div className="grow"><small>Cargando informes…</small></div></div>}
        {rows?.length === 0 && <div className="row-card"><div className="grow"><small>Aún no ha llegado ninguno.</small></div></div>}
        {rows?.map((r) => (
          <div key={r.id}>
            <button className="row-card row-btn" disabled={busy} onClick={() => void show(r)}>
              <span className="row-ico">
                <Icon name="gamepad" />
              </span>
              <div className="grow">
                <b>{r.device || 'Aparato sin nombre'}</b>
                <small>{r.user} · {when(r.at)} · código {r.id}</small>
              </div>
            </button>
            {open?.id === r.id && (
              <div className="row-card" style={{ flexDirection: 'column', alignItems: 'stretch', gap: 10 }}>
                <pre style={{ margin: 0, maxHeight: 360, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', fontSize: 12, lineHeight: 1.45 }}>
                  {open.text}
                </pre>
                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                  <button className="chip" onClick={() => void navigator.clipboard.writeText(open.text).then(() => state.notify('Informe copiado'))}>
                    Copiar
                  </button>
                  <button
                    className="chip"
                    disabled={busy}
                    onClick={() => {
                      if (!confirm('¿Borrar este informe?')) return
                      setBusy(true)
                      void call(`/api/report?id=${encodeURIComponent(r.id)}`, { method: 'DELETE' })
                        .then(() => { setOpen(null); state.notify('Informe borrado'); return load() })
                        .catch((e) => state.notify(e instanceof Error ? e.message : String(e)))
                        .finally(() => setBusy(false))
                    }}
                  >
                    <Icon name="trash" /> Borrar
                  </button>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </>
  )
}
