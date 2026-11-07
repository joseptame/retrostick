import { useState } from 'react'
import type { RecentCollection } from '../lib/recents'
import { Sheet, SheetHead } from './Sheet'
import { Icon } from './icons'

interface Props {
  current: string
  recents: RecentCollection[]
  onLoad: (id: string) => void
  onForget: (id: string) => void
  onClose: () => void
}

/** Bottom sheet to pick the archive.org collection Buscar explores. */
export function CollectionSheet({ current, recents, onLoad, onForget, onClose }: Props) {
  const [value, setValue] = useState(current)

  return (
    <Sheet label="Cambiar colección" onClose={onClose}>
      {(close) => {
        const submit = (id: string) => {
          const clean = id.trim()
          if (clean) close(() => onLoad(clean))
        }
        return (
          <>
            <SheetHead
              title="Colección de archive.org"
              sub={
                <>
                  Escribe el identificador de la colección: es la última parte de su dirección,{' '}
                  <span className="cs-url">archive.org/details/…</span>
                </>
              }
              onClose={() => close()}
            />

            <label className="cs-label" htmlFor="cs-id">
              Identificador
            </label>
            <input
              id="cs-id"
              className="cs-input"
              value={value}
              placeholder="ej. gba-pal"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              enterKeyHint="go"
              onChange={(e) => setValue(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && submit(value)}
            />

            {recents.length > 0 && (
              <>
                <div className="cs-label">Recientes</div>
                <div className="cs-recents">
                  {recents.map((r) => (
                    <div key={r.id} className={`cs-recent ${r.id === current ? 'on' : ''}`}>
                      <button className="cs-recent-main" onClick={() => submit(r.id)}>
                        <span className="cs-ico">
                          <Icon name="box" />
                        </span>
                        <span className="cs-txt">
                          <b>{r.title || r.id}</b>
                          <small>
                            {r.id}
                            {r.count ? ` · ${r.count} ${r.count === 1 ? 'juego' : 'juegos'}` : ''}
                          </small>
                        </span>
                        {r.id === current && <Icon name="check" className="cs-check" label="Actual" />}
                      </button>
                      {r.id !== current && (
                        <button className="cs-forget" onClick={() => onForget(r.id)} aria-label={`Quitar ${r.title || r.id} de recientes`}>
                          <Icon name="x" />
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              </>
            )}

            <button className="cs-go" onClick={() => submit(value)} disabled={!value.trim()}>
              Cargar colección
            </button>
          </>
        )
      }}
    </Sheet>
  )
}
