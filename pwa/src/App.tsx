import { useCallback, useEffect, useState } from 'react'
import { BottomNav, Banner, type Tab } from './components/ui'
import { accountEmail, isConfigured, isSignedIn, setAccountEmail, signOut, SignedOutError } from './lib/auth'
import { ensureFolders, fetchAccountEmail, forgetFolders, type Folders } from './lib/drive'
import { cachedLibrary, loadLibrary, type Library } from './lib/library'
import { Coleccion } from './screens/Coleccion'
import { Ficha } from './screens/Ficha'
import { Login } from './screens/Login'
import { Partidas } from './screens/Partidas'
import { Stick } from './screens/Stick'
import { Subir } from './screens/Subir'

export interface AppState {
  library: Library | null
  folders: Folders | null
  online: boolean
  refresh: () => Promise<void>
  setLibrary: (update: (l: Library) => Library) => void
  openGame: (key: string) => void
  notify: (message: string) => void
}

export default function App() {
  const [signedIn, setSignedIn] = useState(isSignedIn())
  if (!isConfigured()) {
    return (
      <main className="screen">
        <Banner tone="error">Falta configurar VITE_GOOGLE_CLIENT_ID y VITE_GOOGLE_CLIENT_SECRET.</Banner>
      </main>
    )
  }
  if (!signedIn) return <Login onDone={() => setSignedIn(true)} />
  return (
    <Main
      onSignOut={() => {
        signOut()
        forgetFolders()
        localStorage.removeItem('retrostick.library')
        setSignedIn(false)
      }}
    />
  )
}

function Main({ onSignOut }: { onSignOut: () => void }) {
  const [tab, setTab] = useState<Tab>('coleccion')
  const [library, setLib] = useState<Library | null>(cachedLibrary())
  const [folders, setFolders] = useState<Folders | null>(null)
  const [online, setOnline] = useState(navigator.onLine)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [selected, setSelected] = useState<string | null>(null)
  const [toast, setToast] = useState<string | null>(null)

  const notify = useCallback((message: string) => {
    setToast(message)
    setTimeout(() => setToast((t) => (t === message ? null : t)), 3500)
  }, [])

  const refresh = useCallback(async () => {
    if (!navigator.onLine) return
    setLoading(true)
    try {
      const f = await ensureFolders()
      setFolders(f)
      setLib(await loadLibrary(f))
      if (!accountEmail()) setAccountEmail(await fetchAccountEmail())
      setError(null)
    } catch (e) {
      if (e instanceof SignedOutError) onSignOut()
      else setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [onSignOut])

  useEffect(() => {
    refresh()
    const up = () => {
      setOnline(true)
      refresh()
    }
    const down = () => setOnline(false)
    const visible = () => document.visibilityState === 'visible' && refresh()
    window.addEventListener('online', up)
    window.addEventListener('offline', down)
    document.addEventListener('visibilitychange', visible)
    return () => {
      window.removeEventListener('online', up)
      window.removeEventListener('offline', down)
      document.removeEventListener('visibilitychange', visible)
    }
  }, [refresh])

  const state: AppState = {
    library,
    folders,
    online,
    refresh,
    setLibrary: (update) => setLib((l) => (l ? update(l) : l)),
    openGame: setSelected,
    notify,
  }
  const game = selected ? library?.games.find((g) => g.key === selected) : undefined

  return (
    <div className="app">
      {!online && <Banner tone="warn">Sin conexión: se muestra la última copia guardada y las acciones están desactivadas.</Banner>}
      {online && error && (
        <Banner tone="error">
          No se pudo leer Drive ({error}). <button className="link" onClick={refresh}>Reintentar</button>
        </Banner>
      )}
      {loading && !library && <p className="loading">Cargando tu colección…</p>}

      {game ? (
        <Ficha game={game} state={state} onBack={() => setSelected(null)} />
      ) : (
        <>
          {tab === 'coleccion' && <Coleccion state={state} onUpload={() => setTab('subir')} />}
          {tab === 'subir' && <Subir state={state} />}
          {tab === 'partidas' && <Partidas state={state} />}
          {tab === 'stick' && <Stick state={state} onSignOut={onSignOut} />}
        </>
      )}

      {toast && <div className="toast">{toast}</div>}
      <BottomNav
        tab={tab}
        onTab={(t) => {
          setSelected(null)
          setTab(t)
        }}
      />
    </div>
  )
}
