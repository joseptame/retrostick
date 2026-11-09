import { useEffect, useState } from 'react'
import type { AppState } from '../App'
import { raAccount, removeRaAccount, saveRaAccount } from '../lib/drive'
import { Icon } from './icons'

/**
 * Stick → RetroAchievements: log in with the user and password (api/ra.ts?login=1 turns them into a token; the
 * password isn't kept anywhere) and the token goes to Retro/Config/ra.json for the stick. Also to log in again
 * after changing the password, or to switch achievements off.
 */
export function RaSection({ state }: { state: AppState }) {
  const folders = state.folders
  const [account, setAccount] = useState<{ user: string } | null | undefined>(undefined)
  // Opened from the TV's QR (…/?ra=1): the form is open at once
  const [open, setOpen] = useState(() => new URL(location.href).searchParams.has('ra'))
  const [user, setUser] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!folders) return
    raAccount(folders).then(setAccount).catch(() => setAccount(null))
  }, [folders])

  const submit = async () => {
    if (!folders) return
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
      await saveRaAccount(folders, j.user, j.token)
      setAccount({ user: j.user })
      setOpen(false)
      setPassword('')
      const fromTv = new URL(location.href).searchParams.has('ra')
      if (fromTv) history.replaceState(null, '', location.pathname)
      state.notify(
        fromTv
          ? `Sesión iniciada como ${j.user}. La tele la recogerá en unos segundos`
          : `Sesión iniciada como ${j.user}. El stick la recibirá en la próxima sincronización`,
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    if (!folders || !confirm('¿Desactivar los logros en el stick?')) return
    try {
      await removeRaAccount(folders)
      setAccount(null)
      state.notify('Logros desactivados. El stick lo aplicará en la próxima sincronización')
    } catch (e) {
      state.notify(`No se pudo: ${e instanceof Error ? e.message : e}`)
    }
  }

  return (
    <>
      <h3 className="sec2">RetroAchievements</h3>
      <div className="rows">
        <button className="row-card row-btn" disabled={!folders} onClick={() => setOpen((o) => !o)}>
          <span className="row-ico">
            <Icon name="sparkle" />
          </span>
          <div className="grow">
            <b>{account === undefined ? 'Comprobando…' : account ? `Conectado como ${account.user}` : 'Sin conectar'}</b>
            <small>{account ? 'Volver a iniciar sesión (tras cambiar la contraseña) o cambiar de cuenta' : 'Inicia sesión para ganar logros en el stick'}</small>
          </div>
          <span className="chip">{account ? 'Cambiar' : 'Entrar'}</span>
        </button>
        {open && (
          <form
            className="ra-login"
            onSubmit={(e) => {
              e.preventDefault()
              if (user.trim() && password) void submit()
            }}
          >
            <input
              value={user}
              onChange={(e) => setUser(e.target.value)}
              placeholder="Usuario"
              autoComplete="username"
              autoCapitalize="none"
              autoCorrect="off"
              aria-label="Usuario de RetroAchievements"
            />
            <input
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="Contraseña"
              type="password"
              autoComplete="current-password"
              aria-label="Contraseña de RetroAchievements"
            />
            {error && <p className="pair-error">{error}</p>}
            <button className="cta" type="submit" disabled={busy || !user.trim() || !password}>
              {busy ? 'Entrando…' : 'Iniciar sesión'}
            </button>
            <small className="ra-note">
              La contraseña solo se usa para pedir el acceso a RetroAchievements: no se guarda ni en Drive ni en el stick.
            </small>
          </form>
        )}
        {account && (
          <button className="row-card row-btn warn" onClick={() => void remove()}>
            <span className="row-ico">
              <Icon name="x" />
            </span>
            <div className="grow">
              <b>Desactivar los logros</b>
              <small>Quita la cuenta del stick</small>
            </div>
          </button>
        )}
      </div>
    </>
  )
}
