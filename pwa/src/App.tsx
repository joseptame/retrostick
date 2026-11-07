import { useCallback, useEffect, useMemo, useState } from 'react'
import { BottomNav, Banner, type Tab } from './components/ui'
import { PullToRefreshIndicator, usePullToRefresh } from './components/PullToRefresh'
import { accountEmail, isConfigured, isSignedIn, setAccountEmail, signOut, SignedOutError } from './lib/auth'
import { ensureFolders, fetchAccountEmail, forgetFolders, type Folders } from './lib/drive'
import { cachedLibrary, loadLibrary, type Library } from './lib/library'
import { saveOf } from './lib/names'
import { isActive, newUploadId, withProgress, type UploadItem, type UploadsApi } from './lib/uploads'
import { Coleccion } from './screens/Coleccion'
import { Buscar } from './screens/Buscar'
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
  /** Uploads in progress and recent ones (Subir → "Subidas", tab bar ring) */
  uploads: UploadItem[]
  uploadsApi: UploadsApi
}

const SAVES_SEEN = 'rs.savesSeenAt'

function readSeen(): number {
  try {
    return Number(localStorage.getItem(SAVES_SEEN)) || 0
  } catch {
    return 0
  }
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
  const [uploads, setUploads] = useState<UploadItem[]>([])
  const uploadsApi = useMemo<UploadsApi>(
    () => ({
      start(u) {
        const id = newUploadId()
        const now = Date.now()
        const item: UploadItem = { status: 'uploading', ...u, id, sent: 0, speed: 0, startedAt: now, lastAt: now, lastSent: 0 }
        setUploads((list) => [item, ...list.filter((x) => x.key !== u.key)])
        return id
      },
      progress(id, sent, status) {
        setUploads((list) => list.map((x) => (x.id === id ? withProgress(x, sent, status, Date.now()) : x)))
      },
      finish(id, error) {
        setUploads((list) =>
          list.map((x) =>
            x.id === id ? { ...x, status: error ? 'error' : 'done', error, sent: error ? x.sent : x.total, finishedAt: Date.now() } : x,
          ),
        )
      },
      clear() {
        setUploads((list) => list.filter(isActive))
      },
    }),
    [],
  )
  const activeUpload = uploads.find(isActive)
  const upload = activeUpload
    ? { progress: activeUpload.status === 'downloading' || !activeUpload.total ? null : activeUpload.sent / activeUpload.total }
    : null
  const [savesSeenAt, setSavesSeenAt] = useState(readSeen)
  /** What "new" means inside Partidas: the last visit before this one. */
  const [newSince, setNewSince] = useState(0)

  const markSavesSeen = useCallback(() => {
    const now = Date.now()
    setSavesSeenAt(now)
    try {
      localStorage.setItem(SAVES_SEEN, String(now))
    } catch {
      /* not remembered: the badge just reappears next time */
    }
  }, [])

  // First run: nothing counts as new. Visiting Partidas marks everything as seen.
  useEffect(() => {
    if (library && (savesSeenAt === 0 || tab === 'partidas')) markSavesSeen()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [library, tab])

  const newSaves = useMemo(() => {
    if (!library || savesSeenAt === 0) return 0
    const games = new Set<string>()
    for (const f of library.saves) {
      if (Date.parse(f.modifiedTime) > savesSeenAt) games.add(saveOf(f.name)?.game ?? f.name)
    }
    return games.size
  }, [library, savesSeenAt])

  const stickBadge = library?.status
    ? Date.now() - new Date(library.status.updatedAt).getTime() < 24 * 3600 * 1000
      ? 'ok'
      : 'stale'
    : null

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

  const { pull, refreshing } = usePullToRefresh(refresh)

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
    uploads,
    uploadsApi,
  }
  const game = selected ? library?.games.find((g) => g.key === selected) : undefined

  return (
    <div className="app">
      <PullToRefreshIndicator pull={pull} refreshing={refreshing} />
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
          {tab === 'coleccion' && <Coleccion state={state} onUpload={() => setTab('subir')} onSearch={() => setTab('buscar')} />}
          {tab === 'buscar' && <Buscar state={state} />}
          {tab === 'subir' && <Subir state={state} />}
          {tab === 'partidas' && <Partidas state={state} newSince={newSince} />}
          {tab === 'stick' && <Stick state={state} onSignOut={onSignOut} />}
        </>
      )}

      {toast && <div className="toast">{toast}</div>}
      <BottomNav
        tab={tab}
        badges={{ upload, newSaves, stick: stickBadge }}
        onTab={(t) => {
          if (t === 'partidas' && tab !== 'partidas') setNewSince(savesSeenAt)
          setSelected(null)
          setTab(t)
          window.scrollTo({ top: 0 })
        }}
      />
    </div>
  )
}
