import { useState, type CSSProperties, type ReactNode } from 'react'
import type { ConsoleId } from '../lib/consoles'
import { Icon } from './icons'
import { coverGradient, useCoverUrl } from './ui'

interface CoverCardProps {
  console: ConsoleId
  /** File name used to look up the box art */
  fileName: string
  title: string
  meta: ReactNode
  /** Extra class for the meta line: running | failed | ok */
  metaTone?: string
  /** Progress line on top of the bar: undefined = none, null = indeterminate, 0..1 = determinate */
  progress?: number | null
  /** The round action on the right of the bar */
  action?: ReactNode
  className?: string
  style?: CSSProperties
  onOpen: () => void
}

/**
 * Game card ("Game Pass" style): square box art edge to edge, and under it a dark glass bar with the
 * title, one meta line and one round action. The bar's backdrop is the same box art blurred, so each
 * card takes its game's colours. Shows a shimmer until the box art is downloaded.
 */
export function CoverCard({ console, fileName, title, meta, metaTone, progress, action, className, style, onOpen }: CoverCardProps) {
  const { url, resolved, failed, setFailed } = useCoverUrl(console, fileName)
  /** src of the box art that finished downloading (a cached image may finish before React notices). */
  const [loadedSrc, setLoadedSrc] = useState<string | null>(null)
  const art = url && !failed ? url : null
  const ready = resolved && (!art || loadedSrc === art)

  return (
    <div className={`gp-card card-in ${ready ? 'ready' : 'pending'} ${className ?? ''}`} style={style} onClick={onOpen}>
      {ready &&
        (art ? (
          <img className="gp-backdrop" src={art} alt="" aria-hidden />
        ) : (
          <div className="gp-backdrop" style={{ background: coverGradient(title) }} />
        ))}

      <div className={`gp-art ${ready ? '' : 'sk'}`} style={ready && !art ? { background: coverGradient(title) } : undefined}>
        {art ? (
          <img
            ref={(img) => {
              if (img?.complete && img.naturalWidth > 0 && loadedSrc !== art) setLoadedSrc(art)
            }}
            src={art}
            alt=""
            loading="lazy"
            onLoad={() => setLoadedSrc(art)}
            onError={() => setFailed(true)}
          />
        ) : (
          ready && <span className="gp-initial">{title.replace(/^The /, '').charAt(0)}</span>
        )}
      </div>

      <div className="gp-bar">
        {progress !== undefined && (
          <div className="gp-prog">{progress === null ? <i className="indet" /> : <i style={{ width: `${progress * 100}%` }} />}</div>
        )}
        <div className="gp-row">
          <div className="gp-txt">
            <div className="gp-title">{title}</div>
            <div className={`gp-meta ${metaTone ?? ''}`}>{meta}</div>
          </div>
          {action}
        </div>
      </div>
    </div>
  )
}

export type CardState =
  | { kind: 'idle' }
  | { kind: 'owned' }
  | { kind: 'downloading' }
  | { kind: 'uploading'; progress: number }
  | { kind: 'failed'; error: string }

interface SearchCardProps {
  console: ConsoleId
  fileName: string
  title: string
  /** "EU · 4 MB" */
  meta: string
  state: CardState
  /** Another game is uploading: this card's button waits. */
  locked: boolean
  online: boolean
  style?: CSSProperties
  onOpen: () => void
  onUpload: () => void
}

/** Buscar: a game in an archive.org collection, with its upload button and progress. */
export function SearchCard({ console, fileName, title, meta, state, locked, online, style, onOpen, onUpload }: SearchCardProps) {
  const running = state.kind === 'downloading' || state.kind === 'uploading'

  let status = meta
  if (state.kind === 'downloading') status = 'Descargando…'
  else if (state.kind === 'uploading') status = `Subiendo · ${Math.round(state.progress * 100)} %`
  else if (state.kind === 'failed') status = 'Error · toca para reintentar'

  return (
    <CoverCard
      console={console}
      fileName={fileName}
      title={title}
      meta={status}
      metaTone={running ? 'running' : state.kind === 'failed' ? 'failed' : undefined}
      progress={state.kind === 'uploading' ? state.progress : state.kind === 'downloading' ? null : undefined}
      style={style}
      onOpen={onOpen}
      action={
        state.kind === 'owned' ? (
          <span className="gp-btn ok" role="img" aria-label="En tu colección">
            <Icon name="check" strokeWidth={3.2} />
          </span>
        ) : (
          <button
            className={`gp-btn ${running ? 'busy' : ''} ${state.kind === 'failed' ? 'err' : ''}`}
            aria-label={state.kind === 'failed' ? `Reintentar subida: ${state.error}` : running ? 'Subiendo' : `Subir ${title} a Drive`}
            title={state.kind === 'failed' ? state.error : undefined}
            disabled={!online || running || locked}
            onClick={(ev) => {
              ev.stopPropagation()
              onUpload()
            }}
          >
            <Icon name={state.kind === 'failed' ? 'retry' : 'up'} strokeWidth={2.6} />
          </button>
        )
      }
    />
  )
}
