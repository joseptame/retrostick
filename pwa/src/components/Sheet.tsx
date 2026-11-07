import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'

const EXIT_MS = 280

interface Props {
  label: string
  onClose: () => void
  className?: string
  /** Content; a function receives `close(after?)`, which plays the exit animation first. */
  children: ReactNode | ((close: (after?: () => void) => void) => ReactNode)
}

/** Bottom sheet with the app's slide-up / slide-down animation, scroll lock and Escape to close. */
export function Sheet({ label, onClose, className, children }: Props) {
  const [closing, setClosing] = useState(false)
  const closingRef = useRef(false)

  const close = useCallback(
    (after?: () => void) => {
      if (closingRef.current) return
      closingRef.current = true
      setClosing(true)
      window.setTimeout(() => {
        onClose()
        after?.()
      }, EXIT_MS)
    },
    [onClose],
  )

  useEffect(() => {
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    document.body.classList.add('modal-open')
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && close()
    window.addEventListener('keydown', onKey)
    return () => {
      document.body.style.overflow = prev
      document.body.classList.remove('modal-open')
      window.removeEventListener('keydown', onKey)
    }
  }, [close])

  return (
    <div className={`gs-root ${closing ? 'closing' : ''}`} role="dialog" aria-modal="true" aria-label={label}>
      <div className="gs-backdrop" onClick={() => close()} />
      <div className={`gs-sheet cs-sheet ${className ?? ''}`}>
        <div className="cs-handle" aria-hidden />
        {typeof children === 'function' ? children(close) : children}
      </div>
    </div>
  )
}

/** Title row of a sheet with an optional subtitle and a round close button. */
export function SheetHead({ title, sub, onClose }: { title: string; sub?: ReactNode; onClose: () => void }) {
  return (
    <div className="cs-head">
      <div>
        <h2>{title}</h2>
        {sub && <p>{sub}</p>}
      </div>
      <button className="cs-x" onClick={onClose} aria-label="Cerrar">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" aria-hidden>
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      </button>
    </div>
  )
}
