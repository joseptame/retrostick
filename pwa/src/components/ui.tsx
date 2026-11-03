import { useEffect, useState, type ReactNode } from 'react'
import type { ConsoleId } from '../lib/consoles'
import { coverFor } from '../lib/romdb'

const GRADIENTS = [
  ['#0f9b6e', '#022c22'], ['#16a34a', '#14532d'], ['#f97316', '#7c2d12'], ['#eab308', '#713f12'],
  ['#ef4444', '#7f1d1d'], ['#3b82f6', '#1e3a8a'], ['#8b5cf6', '#2e1065'],
]

function hash(s: string) {
  let h = 0
  for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0
  return Math.abs(h)
}

/** Box art from libretro-thumbnails, or a gradient card with the initial (README 7.5). */
export function Cover({ console, name, title, className }: { console: ConsoleId; name: string; title: string; className?: string }) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let alive = true
    setUrl(null)
    setFailed(false)
    coverFor(console, name).then((u) => alive && setUrl(u)).catch(() => alive && setFailed(true))
    return () => {
      alive = false
    }
  }, [console, name])
  const [from, to] = GRADIENTS[hash(title) % GRADIENTS.length]
  return (
    <div className={`cover ${className ?? ''}`} style={{ background: `linear-gradient(160deg, ${from}, ${to})` }}>
      {(!url || failed) && (
        <>
          <span className="cover-initial">{title.replace(/^The /, '').charAt(0)}</span>
          <span className="cover-title">{title}</span>
        </>
      )}
      {url && !failed && <img src={url} alt="" loading="lazy" onError={() => setFailed(true)} />}
    </div>
  )
}

export function Switch({ on, onChange, disabled, label }: { on: boolean; onChange: (v: boolean) => void; disabled?: boolean; label: string }) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={on}
      aria-label={label}
      className={`switch ${on ? 'on' : ''}`}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation()
        onChange(!on)
      }}
    />
  )
}

export function Banner({ tone = 'info', children }: { tone?: 'info' | 'warn' | 'error'; children: ReactNode }) {
  return <div className={`banner ${tone}`}>{children}</div>
}

export type Tab = 'coleccion' | 'subir' | 'partidas' | 'stick'

const ICONS: Record<Tab, ReactNode> = {
  coleccion: (
    <>
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </>
  ),
  subir: <path d="M12 16V4M7 9l5-5 5 5M4 20h16" />,
  partidas: (
    <>
      <path d="M5 3h11l3 3v15H5z" />
      <path d="M8 3v5h7V3M8 21v-6h8v6" />
    </>
  ),
  stick: (
    <>
      <rect x="2" y="5" width="20" height="13" rx="2" />
      <path d="M8 21h8" />
    </>
  ),
}

const LABELS: Record<Tab, string> = { coleccion: 'Colección', subir: 'Subir', partidas: 'Partidas', stick: 'Stick' }

export function BottomNav({ tab, onTab }: { tab: Tab; onTab: (t: Tab) => void }) {
  return (
    <nav className="nav">
      {(Object.keys(LABELS) as Tab[]).map((t) => (
        <button key={t} className={t === tab ? 'on' : ''} onClick={() => onTab(t)}>
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            {ICONS[t]}
          </svg>
          {LABELS[t]}
        </button>
      ))}
    </nav>
  )
}
