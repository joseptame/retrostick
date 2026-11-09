import { useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { saveRaAccount } from '../lib/drive'

/**
 * Where the TV's QR lands (…/?ra=1, Retro Ajustes → Mis logros): a page of its own to log in to RetroAchievements,
 * reached after the app's own login if needed. It never goes on into the app: it ends with a message (logged in,
 * with what to do on the TV) or, cancelled, a note to close it. The password only goes to api/ra.ts?login=1 for the
 * token (never kept); {user, token} goes to Retro/Config/ra.json and the TV, which is watching Drive, picks it up.
 */
export function RaLanding({ state }: { state: AppState }) {
  const [user, setUser] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<{ user: string } | 'cancelled' | null>(null)
  const ready = !!state.folders

  const submit = async () => {
    if (!state.folders) return
    setBusy(true)
    setError(null)
    try {
      const res = await fetch('/api/ra?login=1', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ user: user.trim(), password }),
      })
      const j = (await res.json().catch(() => ({}))) as { user?: string; token?: string; error?: string }
      if (!res.ok || !j.token || !j.user) throw new Error(j.error ?? `Error ${res.status}`)
      await saveRaAccount(state.folders, j.user, j.token)
      setPassword('')
      setResult({ user: j.user })
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="screen ra-landing">
      <div className="ra-landing-brand">
        Retro<span>Stick</span>
      </div>
      {result === 'cancelled' ? (
        <section className="ra-landing-card">
          <span className="ra-landing-ico">
            <Icon name="x" />
          </span>
          <h1>No se ha iniciado sesión</h1>
          <p>Los logros siguen desactivados en el stick. Ya puedes cerrar esta página.</p>
          <p className="ra-landing-steps">Para volver a intentarlo, escanea otra vez el QR de la tele.</p>
        </section>
      ) : result ? (
        <section className="ra-landing-card">
          <span className="ra-landing-ico ok">
            <Icon name="check" />
          </span>
          <h1>¡Sesión iniciada!</h1>
          <p>
            Has entrado en RetroAchievements como <b>{result.user}</b>.
          </p>
          <ol className="ra-landing-steps">
            <li>Vuelve a la tele: en unos segundos pasará sola a <b>Mis logros</b>.</li>
            <li>Juega: cada logro que consigas te avisará en la partida, con su insignia.</li>
            <li>Ya puedes cerrar esta página.</li>
          </ol>
        </section>
      ) : (
        <section className="ra-landing-card">
          <span className="ra-landing-ico">
            <Icon name="sparkle" />
          </span>
          <h1>Inicia sesión en RetroAchievements</h1>
          <p>
            Para ganar logros en los juegos del stick. Usa tu cuenta de <b>RetroAchievements</b> (retroachievements.org),
            no la de RetroStick ni la de Google.
          </p>
          <form
            className="ra-landing-form"
            onSubmit={(e) => {
              e.preventDefault()
              if (user.trim() && password) void submit()
            }}
          >
            <input
              value={user}
              onChange={(e) => setUser(e.target.value)}
              placeholder="Usuario de RetroAchievements"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              aria-label="Usuario de RetroAchievements"
            />
            <input
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Contraseña de RetroAchievements"
              type="password"
              autoComplete="current-password"
              aria-label="Contraseña de RetroAchievements"
            />
            {error && <p className="pair-error">{error}</p>}
            <button className="cta" type="submit" disabled={!ready || busy || !user.trim() || !password}>
              {!ready ? 'Conectando con Drive…' : busy ? 'Entrando…' : 'Entrar en RetroAchievements'}
            </button>
            <button className="cta ghost" type="button" disabled={busy} onClick={() => setResult('cancelled')}>
              Cancelar
            </button>
          </form>
          <small className="ra-note">
            ¿No tienes cuenta? Créala gratis en retroachievements.org. La contraseña solo se usa para pedir el acceso a
            RetroAchievements: no se guarda ni en Drive ni en el stick.
          </small>
        </section>
      )}
    </main>
  )
}
