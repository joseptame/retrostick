import { useEffect, useState } from 'react'
import type { AppState } from '../App'
import { Icon } from './icons'

interface Me {
  name: string
  admin: boolean
}

interface UserRow {
  name: string
  created: number
}

async function call<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, { ...init, headers: { 'content-type': 'application/json', ...(init?.headers ?? {}) } })
  const body = (await res.json().catch(() => ({}))) as T & { error?: string }
  if (!res.ok) throw new Error(body.error ?? `Error ${res.status}`)
  return body
}

/**
 * Stick → Cuenta: who is logged in to the app and changing one's password; the admin also creates, resets and
 * deletes the other users (api/users.ts, api/me.ts). Each user then signs in to Google Drive with their own
 * account, so their games and saves stay in their Drive.
 */
export function UsersSection({ state }: { state: AppState }) {
  const [me, setMe] = useState<Me | null>(null)
  const [users, setUsers] = useState<UserRow[] | null>(null)
  const [form, setForm] = useState<'password' | 'new' | null>(null)
  const [name, setName] = useState('')
  const [current, setCurrent] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [invite, setInvite] = useState<string | null | undefined>(undefined)

  const loadUsers = async () => setUsers(await call<UserRow[]>('/api/users').catch(() => []))
  useEffect(() => {
    void call<Me>('/api/me').then((m) => {
      setMe(m)
      if (m.admin) {
        void loadUsers()
        void call<{ code: string | null }>('/api/users?invite').then((r) => setInvite(r.code)).catch(() => setInvite(null))
      }
    }).catch(() => setMe(null))
  }, [])

  const open = (f: 'password' | 'new' | null) => {
    setForm(form === f ? null : f)
    setName(''); setCurrent(''); setPassword(''); setError(null)
  }

  const run = async (task: () => Promise<unknown>, done: string) => {
    setBusy(true); setError(null)
    try {
      await task()
      state.notify(done)
      setForm(null); setName(''); setCurrent(''); setPassword('')
      if (me?.admin) await loadUsers()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const changeInvite = (method: 'POST' | 'DELETE') =>
    void run(async () => setInvite((await call<{ code: string | null }>('/api/users?invite', { method })).code),
      method === 'POST' ? 'Código de invitación nuevo: el anterior ya no sirve' : 'Altas cerradas: nadie más puede crearse una cuenta')

  if (!me) return null
  const date = (ms: number) => new Date(ms).toLocaleDateString('es-ES', { day: 'numeric', month: 'short', year: 'numeric' })

  return (
    <>
      <div className="row-card">
        <span className="row-ico">
          <Icon name="gamepad" />
        </span>
        <div className="grow">
          <b>Has entrado como {me.name}</b>
          <small>{me.admin ? 'Administrador: gestionas los usuarios de RetroStick' : 'Tus juegos y partidas van a tu propio Google Drive'}</small>
        </div>
      </div>

      {!me.admin && (
        <button className="row-card row-btn" onClick={() => open('password')}>
          <span className="row-ico">
            <Icon name="link" />
          </span>
          <div className="grow">
            <b>Cambiar mi contraseña</b>
            <small>La de entrar en RetroStick (no la de Google)</small>
          </div>
        </button>
      )}
      {form === 'password' && (
        <form
          className="ra-login"
          onSubmit={(e) => {
            e.preventDefault()
            void run(() => call('/api/me', { method: 'POST', body: JSON.stringify({ current, password }) }), 'Contraseña cambiada')
          }}
        >
          <input value={current} onChange={(e) => setCurrent(e.target.value)} type="password" placeholder="Contraseña actual"
            autoComplete="current-password" aria-label="Contraseña actual" />
          <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="Contraseña nueva (8 caracteres o más)"
            autoComplete="new-password" aria-label="Contraseña nueva" />
          {error && <p className="pair-error">{error}</p>}
          <button className="cta" type="submit" disabled={busy || !current || password.length < 8}>{busy ? 'Guardando…' : 'Cambiar contraseña'}</button>
        </form>
      )}

      {me.admin && (
        <>
          <h3 className="sec2">Usuarios</h3>
          <p className="lead">
            <Icon name="info" />
            <span>
              Cada usuario entra en esta app con su nombre y contraseña, y conecta su propio Google Drive (aquí y en su stick):
              sus juegos y partidas van a su Drive, no al tuyo. Las colecciones restringidas de archive.org solo funcionan con tu cuenta.
            </span>
          </p>
          <div className="rows" style={{ marginTop: 10 }}>
            <div className="row-card">
              <span className="row-ico">
                <Icon name="link" />
              </span>
              <div className="grow">
                <b>{invite ? <>Código de invitación: <span style={{ letterSpacing: 2, color: '#c9c0ff' }}>{invite}</span></> : 'Altas cerradas'}</b>
                <small>
                  {invite
                    ? `Quien lo tenga se crea su cuenta en ${location.host} → «Crear cuenta». Cámbialo cuando quieras: las cuentas ya creadas siguen.`
                    : invite === undefined ? 'Cargando…' : 'Nadie puede crearse una cuenta. Crea un código para que tus amigos se den de alta solos.'}
                </small>
              </div>
              {invite !== undefined && (
                <button className="chip" disabled={busy} onClick={() => changeInvite('POST')}>{invite ? 'Nuevo' : 'Crear código'}</button>
              )}
              {invite && (
                <button className="chip" style={{ marginLeft: 6 }} disabled={busy} onClick={() => changeInvite('DELETE')}>Cerrar</button>
              )}
            </div>
            {users === null && <div className="row-card"><div className="grow"><small>Cargando usuarios…</small></div></div>}
            {users?.map((u) => (
              <div key={u.name} className="row-card">
                <span className="row-ico">
                  <Icon name="gamepad" />
                </span>
                <div className="grow">
                  <b>{u.name}</b>
                  <small>Desde el {date(u.created)}</small>
                </div>
                <button
                  className="chip"
                  disabled={busy}
                  onClick={() => {
                    const p = prompt(`Contraseña nueva para ${u.name} (8 caracteres o más):`)
                    if (p) void run(() => call('/api/users', { method: 'PATCH', body: JSON.stringify({ name: u.name, password: p }) }),
                      `Contraseña de ${u.name} cambiada: tendrá que volver a entrar`)
                  }}
                >
                  Contraseña
                </button>
                <button
                  className="chip"
                  style={{ marginLeft: 6 }}
                  disabled={busy}
                  onClick={() => {
                    if (confirm(`¿Dar de baja a ${u.name}? No podrá entrar en la app y su stick dejará de poder usar RetroShop. Su Drive no se toca.`))
                      void run(() => call(`/api/users?name=${encodeURIComponent(u.name)}`, { method: 'DELETE' }), `${u.name} dado de baja`)
                  }}
                >
                  <Icon name="trash" />
                </button>
              </div>
            ))}
            {users?.length === 0 && (
              <div className="row-card"><div className="grow"><small>Aún no hay más usuarios.</small></div></div>
            )}
            <button className="row-card row-btn" onClick={() => open('new')}>
              <span className="row-ico">
                <Icon name="up" />
              </span>
              <div className="grow">
                <b>Añadir un usuario</b>
                <small>Un nombre y una contraseña para que entre un amigo</small>
              </div>
            </button>
            {form === 'new' && (
              <form
                className="ra-login"
                onSubmit={(e) => {
                  e.preventDefault()
                  void run(() => call('/api/users', { method: 'POST', body: JSON.stringify({ name, password }) }),
                    `Usuario ${name.trim().toLowerCase()} creado. Pásale la dirección de la app, su usuario y su contraseña`)
                }}
              >
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Usuario (sin espacios: pepe, ana.g…)"
                  autoCapitalize="none" autoCorrect="off" autoComplete="off" aria-label="Usuario nuevo" />
                <input value={password} onChange={(e) => setPassword(e.target.value)} type="text" placeholder="Contraseña (8 caracteres o más)"
                  autoComplete="off" aria-label="Contraseña del usuario nuevo" />
                {error && <p className="pair-error">{error}</p>}
                <button className="cta" type="submit" disabled={busy || !name.trim() || password.length < 8}>{busy ? 'Creando…' : 'Crear usuario'}</button>
                <small className="ra-note">Él podrá cambiar luego su contraseña desde esta misma pantalla.</small>
              </form>
            )}
          </div>
        </>
      )}
    </>
  )
}
