import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { Icon } from './icons'
import type { ConsoleId } from '../lib/consoles'
import { coverFor } from '../lib/romdb'
import { unproxiedCover } from '../lib/names'

const GRADIENTS = [
  ['#0f9b6e', '#022c22'], ['#16a34a', '#14532d'], ['#f97316', '#7c2d12'], ['#eab308', '#713f12'],
  ['#ef4444', '#7f1d1d'], ['#3b82f6', '#1e3a8a'], ['#8b5cf6', '#2e1065'],
]

function hash(s: string) {
  let h = 0
  for (const ch of s) h = (h * 31 + ch.charCodeAt(0)) | 0
  return Math.abs(h)
}

/** URL of the box art for a game (null while loading or when there is none). */
export function useCoverUrl(
  console: ConsoleId,
  name: string,
): { url: string | null; resolved: boolean; failed: boolean; setFailed: (f: boolean) => void } {
  const [url, setUrl] = useState<string | null>(null)
  const [resolved, setResolved] = useState(false)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    let alive = true
    setUrl(null)
    setResolved(false)
    setFailed(false)
    coverFor(console, name)
      .then((u) => alive && setUrl(u))
      .catch(() => alive && setFailed(true))
      .finally(() => alive && setResolved(true))
    return () => {
      alive = false
    }
  }, [console, name])
  // The resizing CDN failed: try the original image before giving up.
  const fail = (f: boolean) => {
    const direct = f && url ? unproxiedCover(url) : null
    if (direct) setUrl(direct)
    else setFailed(f)
  }
  /** `resolved`: the lookup finished (url found, none, or error). */
  return { url, resolved, failed, setFailed: fail }
}

/**
 * Wide box art (European SNES, N64…) loses its sides when cropped to a square slot. When the image is
 * clearly wider than tall it is shown whole; with `fill`, its container also paints a blurred copy of it
 * behind (CSS .art-fill), so there are no empty bands.
 */
export function fitWideArt(img: HTMLImageElement, fill = true) {
  const wide = img.naturalHeight > 0 && img.naturalWidth / img.naturalHeight > 1.2
  img.classList.toggle('art-wide', wide)
  const box = img.parentElement
  if (!fill || !box) return
  box.classList.toggle('art-fill', wide)
  if (wide) box.style.setProperty('--art', `url("${img.currentSrc || img.src}")`)
}

/** Gradient used when a game has no box art, stable per title. */
export function coverGradient(title: string): string {
  const [from, to] = GRADIENTS[hash(title) % GRADIENTS.length]
  return `linear-gradient(160deg, ${from}, ${to})`
}

/** Box art from libretro-thumbnails, or a gradient card with the initial (README 7.5). */
export function Cover({ console, name, title, className }: { console: ConsoleId; name: string; title: string; className?: string }) {
  const { url, failed, setFailed } = useCoverUrl(console, name)
  return (
    <div className={`cover ${className ?? ''}`} style={{ background: coverGradient(title) }}>
      {(!url || failed) && (
        <>
          <span className="cover-initial">{title.replace(/^The /, '').charAt(0)}</span>
          <span className="cover-title">{title}</span>
        </>
      )}
      {url && !failed && <img src={url} alt="" loading="lazy" onLoad={(e) => fitWideArt(e.currentTarget)} onError={() => setFailed(true)} />}
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

export type Tab = 'coleccion' | 'buscar' | 'subir' | 'partidas' | 'stick'

const ICONS: Record<Tab, ReactNode> = {
  coleccion: (
    <>
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="3" width="7" height="7" rx="1.5" />
      <rect x="3" y="14" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
    </>
  ),
  buscar: (
    <>
      <circle cx="11" cy="11" r="7" />
      <path d="m21 21-4.3-4.3" />
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

const LABELS: Record<Tab, string> = { coleccion: 'Colección', buscar: 'Buscar', subir: 'Subir', partidas: 'Partidas', stick: 'Stick' }

/** Live state shown on the tab bar: an upload in progress, new saves, and the stick's sync. */
export interface NavBadges {
  /** progress 0..1, or null while the size is unknown (downloading from archive.org) */
  upload?: { progress: number | null } | null
  newSaves?: number
  /** ok: synced in the last 24 h; stale: older */
  stick?: 'ok' | 'stale' | null
}

const TABS = Object.keys(LABELS) as Tab[]
const RING = 2 * Math.PI * 13

export function BottomNav({ tab, onTab, badges = {} }: { tab: Tab; onTab: (t: Tab) => void; badges?: NavBadges }) {
  const { upload, newSaves = 0, stick } = badges
  return (
    <nav className="nav" style={{ '--n': TABS.length, '--i': TABS.indexOf(tab) } as CSSProperties}>
      <span className="nav-pill" aria-hidden />
      {TABS.map((t) => {
        const uploading = t === 'subir' && upload
        let label = LABELS[t]
        let aria = LABELS[t]
        if (uploading) {
          label = upload.progress == null ? 'Bajando' : `${Math.round(upload.progress * 100)} %`
          aria = `Subir: ${upload.progress == null ? 'descargando' : `subiendo ${label}`}`
        } else if (t === 'partidas' && newSaves > 0) aria = `Partidas: ${newSaves} nuevas`
        else if (t === 'stick' && stick) aria = `Stick: ${stick === 'ok' ? 'sincronizado' : 'sin sincronizar'}`
        return (
          <button
            key={t}
            className={`${t === tab ? 'on' : ''} ${uploading ? 'busy' : ''}`}
            aria-current={t === tab ? 'page' : undefined}
            aria-label={aria}
            onClick={() => onTab(t)}
          >
            <span className="nav-ico">
              {uploading ? (
                <>
                  <svg className={`nav-ring ${upload.progress == null ? 'spin' : ''}`} viewBox="0 0 30 30" aria-hidden>
                    <circle cx="15" cy="15" r="13" />
                    <circle
                      cx="15"
                      cy="15"
                      r="13"
                      strokeDasharray={RING}
                      strokeDashoffset={upload.progress == null ? RING * 0.7 : RING * (1 - upload.progress)}
                    />
                  </svg>
                  <svg className="nav-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                    <path d="M12 19V6M6 11.5L12 5.5l6 6" />
                  </svg>
                </>
              ) : (
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
                  {ICONS[t]}
                </svg>
              )}
              {t === 'partidas' && newSaves > 0 && <span className="nav-count">{newSaves > 9 ? '9+' : newSaves}</span>}
              {t === 'stick' && stick && <span className={`nav-dot ${stick}`} />}
            </span>
            {label}
          </button>
        )
      })}
    </nav>
  )
}

/** Segmented control with a sliding white pill (console filters). */
export function Segmented<T extends string>({
  options,
  value,
  onChange,
  label,
}: {
  options: { id: T; label: string }[]
  value: T
  onChange: (v: T) => void
  label: string
}) {
  const i = Math.max(0, options.findIndex((o) => o.id === value))
  return (
    <div className="seg" role="tablist" aria-label={label} style={{ '--n': options.length, '--i': i } as CSSProperties}>
      <span className="seg-pill" aria-hidden />
      {options.map((o) => (
        <button key={o.id} role="tab" aria-selected={o.id === value} className={o.id === value ? 'on' : ''} onClick={() => onChange(o.id)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

/** Search field that sticks to the top while scrolling; once stuck it gets its own blurred band. */
export function StickySearch({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: string
  onChange: (v: string) => void
  placeholder: string
  label: string
}) {
  const sentinel = useRef<HTMLDivElement>(null)
  const [stuck, setStuck] = useState(false)
  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver(([e]) => setStuck(!e.isIntersecting), { threshold: 0 })
    io.observe(el)
    return () => io.disconnect()
  }, [])
  return (
    <>
      <div ref={sentinel} className="bx-sentinel" aria-hidden />
      <div className={`bx-sticky ${stuck ? 'stuck' : ''}`}>
        <div className="bx-search">
          <Icon name="search" strokeWidth={2.4} />
          <input
            value={value}
            placeholder={placeholder}
            aria-label={label}
            inputMode="search"
            enterKeyHint="search"
            autoCorrect="off"
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && (e.target as HTMLInputElement).blur()}
          />
          {value && (
            <button className="bx-clear" onClick={() => onChange('')} aria-label="Borrar búsqueda">
              <Icon name="x" strokeWidth={2.8} />
            </button>
          )}
        </div>
      </div>
    </>
  )
}
