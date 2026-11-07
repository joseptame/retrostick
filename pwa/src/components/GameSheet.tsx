import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import type { ConsoleId } from '../lib/consoles'
import type { IgdbGame } from '../lib/igdb'
import { Banner, Cover, useCoverUrl } from './ui'

const EXIT_MS = 280
/** Drag distance (px) past which releasing the sheet closes it. */
const DISMISS_PX = 110

interface Props {
  consoleId: ConsoleId
  fileName: string
  fallbackTitle: string
  sizeLabel: string
  data: IgdbGame | null
  loading: boolean
  error: string | null
  online: boolean
  busy: boolean
  /** Progress 0..1 while this game is being uploaded; null otherwise. */
  progress: number | null
  uploaded: boolean
  uploadError: string | null
  archiveHref: string
  onUpload: () => void
  onClose: () => void
}

/** Bottom sheet with the IGDB card of a game: hero, description, trailer, screenshots and actions. */
export function GameSheet(p: Props) {
  const [closing, setClosing] = useState(false)
  const [drag, setDrag] = useState(0)
  const [dragging, setDragging] = useState(false)
  const [expanded, setExpanded] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [shot, setShot] = useState<number | null>(null)
  const sheetRef = useRef<HTMLDivElement>(null)
  const touch = useRef<{ y: number; t: number; active: boolean } | null>(null)

  const close = useCallback(() => {
    if (closing) return
    setClosing(true)
    window.setTimeout(p.onClose, EXIT_MS)
  }, [closing, p.onClose])

  // Lock page scroll (and pull-to-refresh) while the sheet is open.
  useEffect(() => {
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    document.body.classList.add('modal-open')
    return () => {
      document.body.style.overflow = prev
      document.body.classList.remove('modal-open')
    }
  }, [])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (shot != null) setShot(null)
      else close()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [shot, close])

  // New game → reset per-game UI.
  useEffect(() => {
    setExpanded(false)
    setPlaying(false)
    setShot(null)
    sheetRef.current?.scrollTo({ top: 0 })
  }, [p.fileName])

  function onTouchStart(e: React.TouchEvent) {
    const atTop = (sheetRef.current?.scrollTop ?? 0) <= 0
    touch.current = { y: e.touches[0].clientY, t: Date.now(), active: atTop }
  }
  function onTouchMove(e: React.TouchEvent) {
    const t = touch.current
    if (!t?.active) return
    const dy = e.touches[0].clientY - t.y
    if (dy <= 0) {
      if (dragging) {
        setDrag(0)
        setDragging(false)
      }
      return
    }
    if ((sheetRef.current?.scrollTop ?? 0) > 0) return
    setDragging(true)
    // Rubber band: follows the finger, a bit heavier the further it goes.
    setDrag(dy < 200 ? dy : 200 + (dy - 200) * 0.4)
  }
  function onTouchEnd() {
    const t = touch.current
    touch.current = null
    if (!dragging || !t) return
    const velocity = drag / Math.max(1, Date.now() - t.t)
    setDragging(false)
    if (drag > DISMISS_PX || velocity > 0.6) close()
    else setDrag(0)
  }

  const boxart = useCoverUrl(p.consoleId, p.fileName)
  const d = p.data
  const title = d?.name || p.fallbackTitle
  const heroImg = d?.screenshots?.[0] ?? d?.coverUrl ?? null
  const longDesc = (d?.description?.length ?? 0) > 220
  const sheetStyle: CSSProperties = drag
    ? { transform: `translateY(${drag}px)`, transition: dragging ? 'none' : undefined }
    : {}
  const backdropStyle: CSSProperties = drag ? { opacity: Math.max(0.2, 1 - drag / 400) } : {}

  return (
    <div className={`gs-root ${closing ? 'closing' : ''}`} role="dialog" aria-modal="true" aria-label={title}>
      <div className="gs-backdrop" style={backdropStyle} onClick={close} />
      <div
        className="gs-sheet"
        ref={sheetRef}
        style={sheetStyle}
        onTouchStart={onTouchStart}
        onTouchMove={onTouchMove}
        onTouchEnd={onTouchEnd}
        onTouchCancel={onTouchEnd}
      >
        <div className="gs-hero">
          {heroImg && <img className="gs-hero-bg" src={heroImg} alt="" />}
          <div className="gs-hero-fade" />
          <div className="gs-handle" aria-hidden />
          <button className="gs-close" onClick={close} aria-label="Cerrar">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>

        <div className="gs-body">
          <div className="gs-head">
            {/* Same box art as the card (libretro, European when it exists); IGDB's cover only as a fallback. */}
            {boxart.url && !boxart.failed ? (
              <img className="gs-cover" src={boxart.url} alt="" onError={() => boxart.setFailed(true)} />
            ) : !boxart.resolved || p.loading ? (
              <div className="gs-cover sk" />
            ) : d?.coverUrl ? (
              <img className="gs-cover" src={d.coverUrl} alt="" />
            ) : (
              <Cover console={p.consoleId} name={p.fileName} title={p.fallbackTitle} className="gs-cover" />
            )}
            <div className="gs-title">
              <h2>{title}</h2>
              {p.loading ? (
                <div className="gs-meta">
                  <span className="sk sk-line" style={{ width: 120 }} />
                </div>
              ) : (
                <div className="gs-meta">
                  {d?.rating != null && <Rating value={d.rating} />}
                  {d?.year && <span className="gs-tag year">{d.year}</span>}
                  <span className="gs-tag">{p.sizeLabel}</span>
                </div>
              )}
            </div>
          </div>

          {p.loading ? (
            <div className="gs-skel">
              <span className="sk sk-line" />
              <span className="sk sk-line" />
              <span className="sk sk-line" style={{ width: '70%' }} />
              <span className="sk sk-block" />
            </div>
          ) : (
            <div className="gs-content">
              {d?.genres && d.genres.length > 0 && (
                <div className="gs-genres">
                  {d.genres.map((g) => (
                    <span key={g} className="gs-genre">
                      {g}
                    </span>
                  ))}
                </div>
              )}

              {p.error && <Banner tone="error">{p.error}</Banner>}

              {d?.description ? (
                <div className="gs-desc-wrap">
                  <p className={`gs-desc ${longDesc && !expanded ? 'clamped' : ''}`}>{d.description}</p>
                  {longDesc && (
                    <button className="gs-more" onClick={() => setExpanded((x) => !x)}>
                      {expanded ? 'Ver menos' : 'Leer más'}
                    </button>
                  )}
                </div>
              ) : (
                !p.error && <p className="hint">{d ? 'Sin descripción en IGDB.' : 'No se ha encontrado este juego en IGDB.'}</p>
              )}

              {d?.trailerId && (
                <section>
                  <div className="gs-sec">Tráiler</div>
                  <div className="gs-trailer">
                    {playing ? (
                      <iframe
                        src={`https://www.youtube-nocookie.com/embed/${d.trailerId}?autoplay=1&playsinline=1&rel=0&modestbranding=1`}
                        title="Tráiler"
                        allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture; fullscreen"
                        allowFullScreen
                      />
                    ) : (
                      <button className="gs-play" onClick={() => setPlaying(true)} aria-label="Reproducir tráiler">
                        <img src={`https://i.ytimg.com/vi/${d.trailerId}/hqdefault.jpg`} alt="" loading="lazy" />
                        <span className="gs-play-btn">
                          <svg viewBox="0 0 24 24" fill="currentColor">
                            <path d="M8 5.5v13l11-6.5z" />
                          </svg>
                        </span>
                      </button>
                    )}
                  </div>
                </section>
              )}

              {d?.screenshots && d.screenshots.length > 0 && (
                <section>
                  <div className="gs-sec">
                    Capturas <span>{d.screenshots.length}</span>
                  </div>
                  <div className="gs-shots">
                    {d.screenshots.map((s, i) => (
                      <button key={s} onClick={() => setShot(i)} aria-label={`Captura ${i + 1}`}>
                        <img src={s} alt="" loading="lazy" />
                      </button>
                    ))}
                  </div>
                </section>
              )}
            </div>
          )}
        </div>

        <div className="gs-actions">
          {p.progress != null && (
            <div className="prog">
              <i style={{ width: `${p.progress * 100}%` }} />
            </div>
          )}
          {p.uploadError && <p className="hint error">{p.uploadError}</p>}
          <div className="gs-actions-row">
            <a className="gs-btn ghost" href={p.archiveHref} target="_blank" rel="noreferrer">
              archive.org
            </a>
            <button
              className={`gs-btn primary ${p.uploaded ? 'done' : ''}`}
              onClick={p.onUpload}
              disabled={!p.online || p.busy || p.uploaded}
            >
              {p.progress != null
                ? `Subiendo… ${Math.round(p.progress * 100)} %`
                : p.uploaded
                  ? '✓ Subido a Drive'
                  : 'Subir a Drive'}
            </button>
          </div>
        </div>
      </div>

      {shot != null && d?.screenshots && (
        <Lightbox images={d.screenshots} index={shot} onIndex={setShot} onClose={() => setShot(null)} />
      )}
    </div>
  )
}

function Rating({ value }: { value: number }) {
  const tone = value >= 7.5 ? 'hi' : value >= 5.5 ? 'mid' : 'lo'
  return (
    <span className={`gs-rating ${tone}`}>
      <svg viewBox="0 0 24 24" fill="currentColor">
        <path d="M12 2.8l2.8 5.9 6.4.8-4.7 4.4 1.2 6.4L12 17.2l-5.7 3.1 1.2-6.4L2.8 9.5l6.4-.8z" />
      </svg>
      {value.toFixed(1)}
    </span>
  )
}

function Lightbox({
  images,
  index,
  onIndex,
  onClose,
}: {
  images: string[]
  index: number
  onIndex: (i: number) => void
  onClose: () => void
}) {
  const start = useRef<number | null>(null)
  const go = (dir: number) => onIndex((index + dir + images.length) % images.length)
  return (
    <div
      className="gs-lightbox"
      onClick={onClose}
      onTouchStart={(e) => (start.current = e.touches[0].clientX)}
      onTouchEnd={(e) => {
        if (start.current == null) return
        const dx = e.changedTouches[0].clientX - start.current
        start.current = null
        if (Math.abs(dx) > 50) go(dx < 0 ? 1 : -1)
      }}
    >
      <img key={index} src={images[index]} alt="" onClick={(e) => e.stopPropagation()} />
      {images.length > 1 && (
        <div className="gs-dots" onClick={(e) => e.stopPropagation()}>
          {images.map((s, i) => (
            <button key={s} className={i === index ? 'on' : ''} onClick={() => onIndex(i)} aria-label={`Captura ${i + 1}`} />
          ))}
        </div>
      )}
    </div>
  )
}
