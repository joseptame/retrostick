import { useEffect, useRef, useState } from 'react'
import { Banner } from '../components/ui'
import { requestDeviceCode, waitForLogin, type DeviceCode } from '../lib/auth'

/** Same sign-in as the stick: a code typed on google.com/device (README 7.3). */
export function Login({ onDone }: { onDone: () => void }) {
  const [code, setCode] = useState<DeviceCode | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const abort = useRef<AbortController | null>(null)

  useEffect(() => () => abort.current?.abort(), [])

  async function start() {
    setMessage(null)
    abort.current?.abort()
    abort.current = new AbortController()
    try {
      const c = await requestDeviceCode()
      setCode(c)
      const outcome = await waitForLogin(c, abort.current.signal)
      if (outcome === 'ok') onDone()
      else if (outcome === 'denied') setMessage('Has rechazado el acceso a Drive.')
      else if (outcome === 'expired') setMessage('El código ha caducado. Pide uno nuevo.')
      if (outcome !== 'ok') setCode(null)
    } catch (e) {
      setCode(null)
      setMessage(`No se pudo conectar con Google: ${e instanceof Error ? e.message : e}`)
    }
  }

  async function copyAndOpen() {
    if (!code) return
    try {
      await navigator.clipboard.writeText(code.userCode)
      setCopied(true)
    } catch {
      // Clipboard not allowed: the code is on screen anyway
    }
    window.open(code.verificationUrl, '_blank', 'noopener')
  }

  return (
    <main className="screen login">
      <p className="brand">
        Retro<span>Stick</span>
      </p>
      <h1>Tu colección retro, en el móvil</h1>
      <p className="sub">
        Sube tus copias, elige qué juegos van al stick y gestiona tus partidas. Todo se guarda en tu Google Drive, en la
        carpeta <b>Retro</b>.
      </p>

      {message && <Banner tone="warn">{message}</Banner>}

      {!code ? (
        <button className="btn" onClick={start}>
          Conectar con Google Drive
        </button>
      ) : (
        <div className="card login-code">
          <p className="sub">Abre google.com/device e introduce este código:</p>
          <p className="code">{code.userCode}</p>
          <button className="btn" onClick={copyAndOpen}>
            {copied ? 'Código copiado · abrir google.com/device' : 'Copiar código y abrir google.com/device'}
          </button>
          <p className="hint">Elige tu cuenta y acepta. Esta pantalla seguirá sola. Esperando…</p>
        </div>
      )}

      <p className="hint">
        RetroStick solo puede ver los archivos que crea él mismo en tu Drive.{' '}
        <a href="https://joseptame.github.io/retrostick/privacidad.html">Política de privacidad</a>
      </p>
    </main>
  )
}
