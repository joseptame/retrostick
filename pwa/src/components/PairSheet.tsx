import jsQR from 'jsqr'
import { useEffect, useRef, useState } from 'react'
import type { AppState } from '../App'
import { savePairing } from '../lib/drive'
import { Icon } from './icons'
import { Sheet, SheetHead } from './Sheet'

/** 8 letters/digits shown by RetroShop on the TV, inside its QR as …/?vincular=CODE */
const CODE = /^[A-Z0-9]{8}$/

/** Code from what the camera read: the pairing link or the bare code. */
export function pairingCode(text: string): string | null {
  const raw = text.trim()
  try {
    const c = new URL(raw).searchParams.get('vincular')?.toUpperCase() ?? ''
    if (CODE.test(c)) return c
  } catch {
    // not a URL
  }
  const bare = raw.toUpperCase().replace(/[\s-]/g, '')
  return CODE.test(bare) ? bare : null
}

type Phase = 'scan' | 'confirm' | 'pairing' | 'done' | 'error'

/**
 * Pairs RetroShop on the stick: scan the QR on the TV (or type its code), then the app asks the server for
 * a pass (api/pair.ts) and leaves it in Drive for the stick.
 */
export function PairSheet({ state, initialCode, onClose }: { state: AppState; initialCode?: string; onClose: () => void }) {
  const [code, setCode] = useState(initialCode ?? '')
  const [phase, setPhase] = useState<Phase>(initialCode ? 'confirm' : 'scan')
  const [error, setError] = useState<string | null>(null)
  const [camera, setCamera] = useState<'starting' | 'on' | 'off'>('starting')
  const video = useRef<HTMLVideoElement>(null)

  // Camera + jsQR (iOS has no BarcodeDetector): reads a frame every ~200 ms until a pairing code shows up.
  useEffect(() => {
    if (phase !== 'scan') return
    let stream: MediaStream | null = null
    let timer = 0
    let alive = true
    const canvas = document.createElement('canvas')
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    navigator.mediaDevices
      ?.getUserMedia({ video: { facingMode: 'environment' }, audio: false })
      .then((s) => {
        if (!alive) return s.getTracks().forEach((t) => t.stop())
        stream = s
        const v = video.current!
        v.srcObject = s
        v.play().catch(() => {})
        setCamera('on')
        const tick = () => {
          if (!alive) return
          if (v.readyState >= 2 && ctx) {
            const w = 480
            const h = Math.round((v.videoHeight / v.videoWidth) * w) || 360
            canvas.width = w
            canvas.height = h
            ctx.drawImage(v, 0, 0, w, h)
            const found = jsQR(ctx.getImageData(0, 0, w, h).data, w, h, { inversionAttempts: 'dontInvert' })
            const c = found && pairingCode(found.data)
            if (c) {
              navigator.vibrate?.(40)
              setCode(c)
              setPhase('confirm')
              return
            }
          }
          timer = window.setTimeout(tick, 200)
        }
        tick()
      })
      .catch(() => alive && setCamera('off'))
    if (!navigator.mediaDevices) setCamera('off')
    return () => {
      alive = false
      window.clearTimeout(timer)
      stream?.getTracks().forEach((t) => t.stop())
    }
  }, [phase])

  async function pair() {
    const c = pairingCode(code)
    if (!c) return setError('El código tiene 8 letras o números, como aparece en la tele.')
    if (!state.folders) return setError('Espera a que cargue tu Drive y vuelve a intentarlo.')
    setPhase('pairing')
    setError(null)
    try {
      const res = await fetch('/api/pair', { method: 'POST' })
      if (!res.ok || !res.headers.get('content-type')?.includes('json')) throw new Error(`La webapp respondió ${res.status}`)
      const { token } = (await res.json()) as { token: string }
      await savePairing(state.folders, c, token)
      setPhase('done')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setPhase('error')
    }
  }

  return (
    <Sheet label="Vincular RetroShop" onClose={onClose} className="pair-sheet">
      {(close) => (
        <>
          <SheetHead
            title="Vincular RetroShop"
            sub="En la tele: Retro Ajustes → RetroShop. Escanea el QR que aparece."
            onClose={() => close()}
          />

          {phase === 'scan' && (
            <>
              <div className={`pair-cam ${camera}`}>
                <video ref={video} playsInline muted />
                <span className="pair-frame" aria-hidden />
                {camera !== 'on' && (
                  <p className="pair-cam-msg">
                    {camera === 'starting' ? 'Abriendo la cámara…' : 'Sin acceso a la cámara: escribe el código de la tele.'}
                  </p>
                )}
              </div>
              <form
                className="pair-manual"
                onSubmit={(e) => {
                  e.preventDefault()
                  if (pairingCode(code)) setPhase('confirm')
                  else setError('El código tiene 8 letras o números, como aparece en la tele.')
                }}
              >
                <input
                  value={code}
                  onChange={(e) => {
                    setCode(e.target.value.toUpperCase())
                    setError(null)
                  }}
                  placeholder="O escribe el código"
                  autoCapitalize="characters"
                  autoComplete="off"
                  maxLength={9}
                  aria-label="Código de la tele"
                />
                <button className="cta ghost" type="submit" disabled={!code.trim()}>
                  Seguir
                </button>
              </form>
              {error && <p className="pair-error">{error}</p>}
            </>
          )}

          {(phase === 'confirm' || phase === 'pairing') && (
            <div className="pair-confirm">
              <span className="pair-ico">
                <Icon name="tv" />
              </span>
              <p>¿Vincular el stick que muestra este código?</p>
              <b className="pair-code">
                {pairingCode(code)?.replace(/^(.{4})/, '$1 ') ?? code}
              </b>
              <small>RetroShop podrá ver el catálogo y descargar juegos con tu cuenta.</small>
              <button className="cta wide" onClick={pair} disabled={phase === 'pairing'}>
                {phase === 'pairing' ? 'Vinculando…' : 'Vincular'}
              </button>
              {!initialCode && phase === 'confirm' && (
                <button className="text-btn" onClick={() => setPhase('scan')}>
                  Escanear otra vez
                </button>
              )}
            </div>
          )}

          {phase === 'done' && (
            <div className="pair-confirm">
              <span className="pair-ico ok">
                <Icon name="check" strokeWidth={3} />
              </span>
              <p>
                <b>Listo.</b> En unos segundos la tele abrirá la tienda.
              </p>
              <button className="cta wide" onClick={() => close()}>
                Cerrar
              </button>
            </div>
          )}

          {phase === 'error' && (
            <div className="pair-confirm">
              <p className="pair-error">No se pudo vincular: {error}</p>
              <button className="cta wide" onClick={pair}>
                Reintentar
              </button>
            </div>
          )}
        </>
      )}
    </Sheet>
  )
}
