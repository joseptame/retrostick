import { useEffect, useRef, useState } from 'react'
import { Icon, type IconName } from '../components/icons'
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
      <div className="login-mark" aria-hidden>
        <Icon name="gamepad" strokeWidth={2} />
      </div>
      <p className="brand">
        Retro<span>Stick</span>
      </p>
      <h1>Tu colección retro, en el móvil</h1>

      <div className="rows login-feats">
        <Feat icon="search" title="Busca y sube juegos" text="De archive.org o desde tus propias copias." />
        <Feat icon="tv" title="Elige qué va al stick" text="Se sincroniza solo con la TV." />
        <Feat icon="save" title="Tus partidas, a salvo" text="Copias en Drive con historial de versiones." />
      </div>

      {message && <Banner tone="warn">{message}</Banner>}

      {!code ? (
        <button className="cta wide" onClick={start}>
          <Icon name="cloud" strokeWidth={2.4} /> Conectar con Google Drive
        </button>
      ) : (
        <div className="code-card">
          <small>Abre google.com/device e introduce este código</small>
          <p className="code">{code.userCode}</p>
          <button className="cta wide" onClick={copyAndOpen}>
            {copied ? 'Copiado · abrir google.com/device' : 'Copiar código y abrir google.com/device'}
          </button>
          <p className="waiting">
            <span className="dots" aria-hidden>
              <i />
              <i />
              <i />
            </span>
            Elige tu cuenta y acepta; esta pantalla seguirá sola
          </p>
        </div>
      )}

      <p className="hint center">
        Todo se guarda en tu Google Drive, en la carpeta <b>Retro</b>. RetroStick solo ve los archivos que crea él mismo.{' '}
        <a href="https://joseptame.github.io/retrostick/privacidad.html">Política de privacidad</a>
      </p>
    </main>
  )
}

function Feat({ icon, title, text }: { icon: IconName; title: string; text: string }) {
  return (
    <div className="row-card">
      <span className="row-ico">
        <Icon name={icon} />
      </span>
      <div className="grow">
        <b>{title}</b>
        <small>{text}</small>
      </div>
    </div>
  )
}
