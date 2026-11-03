import { useState } from 'react'
import type { AppState } from '../App'
import { Banner, Cover, Switch } from '../components/ui'
import { consoleById, consoleForFile, extension, isCartridge, UPLOAD_ACCEPT, type ConsoleId } from '../lib/consoles'
import { uploadResumable } from '../lib/drive'
import { formatBytes } from '../lib/format'
import { baseName, displayTitle } from '../lib/names'
import { identify, type Identification } from '../lib/romdb'

/** Hashing is only done for cartridges; bigger files are matched by name. */
const MAX_HASH_BYTES = 64 * 1024 * 1024
/** .cue / .m3u list other files by name inside: renaming them would break the game */
const KEEP_NAME = new Set(['cue', 'bin', 'm3u'])

interface Item {
  id: string
  file: File
  console: ConsoleId | null
  ident: Identification | null
  /** Final name without extension */
  name: string
  status: 'reading' | 'ready' | 'uploading' | 'done' | 'error'
  progress: number
  error?: string
  duplicate: boolean
  force: boolean
}

let nextId = 0

export function Subir({ state }: { state: AppState }) {
  const [items, setItems] = useState<Item[]>([])
  const [toStick, setToStick] = useState(true)
  const [uploading, setUploading] = useState(false)

  const update = (id: string, patch: Partial<Item>) => setItems((list) => list.map((i) => (i.id === id ? { ...i, ...patch } : i)))

  const existingNames = (c: ConsoleId) =>
    new Set(state.library?.games.filter((g) => g.console === c).flatMap((g) => g.files.map((f) => f.name)) ?? [])

  async function addFiles(files: FileList | null) {
    if (!files) return
    const added: Item[] = [...files].map((file) => ({
      id: String(nextId++),
      file,
      console: consoleForFile(file.name),
      ident: null,
      name: baseName(file.name),
      status: 'reading',
      progress: 0,
      duplicate: false,
      force: false,
    }))
    setItems((list) => [...list, ...added])
    for (const item of added) {
      if (!item.console) {
        update(item.id, { status: 'error', error: 'Formato no admitido' })
        continue
      }
      try {
        const data =
          isCartridge(item.console) && item.file.size <= MAX_HASH_BYTES ? new Uint8Array(await item.file.arrayBuffer()) : null
        const ident = await identify(item.console, item.file.name, data)
        const ext = extension(item.file.name)
        const name = ident && !KEEP_NAME.has(ext) ? ident.name : baseName(item.file.name)
        update(item.id, {
          ident,
          name,
          status: 'ready',
          duplicate: existingNames(item.console).has(`${name}.${ext}`),
        })
      } catch (e) {
        update(item.id, { status: 'ready', error: `No se pudo identificar: ${e instanceof Error ? e.message : e}` })
      }
    }
  }

  async function uploadAll() {
    if (!state.folders) return
    setUploading(true)
    for (const item of items) {
      if (item.status !== 'ready' || !item.console || (item.duplicate && !item.force)) continue
      const parent = toStick ? state.folders.jugando[item.console] : state.folders.coleccion[item.console]
      const fileName = `${item.name}.${extension(item.file.name)}`
      update(item.id, { status: 'uploading', progress: 0 })
      try {
        await uploadResumable(item.file, fileName, parent, (sent, total) => update(item.id, { progress: total ? sent / total : 1 }))
        update(item.id, { status: 'done', progress: 1 })
      } catch (e) {
        update(item.id, { status: 'error', error: e instanceof Error ? e.message : String(e) })
      }
    }
    setUploading(false)
    state.refresh()
  }

  const pending = items.filter((i) => i.status === 'ready' && (!i.duplicate || i.force)).length

  return (
    <main className="screen">
      <h1>Subir copias</h1>
      <p className="sub">Detecta la consola y pone el nombre correcto</p>

      <label className={`drop ${!state.online ? 'disabled' : ''}`}>
        <svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#9d8cff" strokeWidth="2">
          <path d="M12 16V4M7 9l5-5 5 5M4 20h16" />
        </svg>
        <b>Elegir archivos</b>
        .gb · .gbc · .gba · .chd · .cue · .bin · .m3u · .pbp
        <input
          type="file"
          multiple
          accept={UPLOAD_ACCEPT}
          disabled={!state.online || uploading}
          onChange={(e) => {
            addFiles(e.target.files)
            e.target.value = ''
          }}
        />
      </label>

      {items.map((item) => {
        const ext = extension(item.file.name)
        const renamed = item.name !== baseName(item.file.name)
        return (
          <div key={item.id} className="up">
            {item.console ? (
              <Cover console={item.console} name={item.name} title={displayTitle(item.name)} className="thumb" />
            ) : (
              <div className="cover thumb" />
            )}
            <div className="i">
              {renamed && <div className="f">{item.file.name}</div>}
              {item.status === 'ready' && !item.ident && !KEEP_NAME.has(ext) ? (
                <input
                  className="name-input"
                  value={item.name}
                  aria-label="Nombre del juego"
                  onChange={(e) => update(item.id, { name: e.target.value.trim() || baseName(item.file.name) })}
                />
              ) : (
                <div className="n">{`${item.name}.${ext}`}</div>
              )}
              <div className="chips">
                {item.console && <span className="chip">{consoleById(item.console).short}</span>}
                {item.ident && item.ident.by !== 'name' && <span className="chip g">✓ Reconocido</span>}
                {item.ident?.by === 'name' && <span className="chip w">≈ Por nombre</span>}
                {item.status === 'ready' && !item.ident && <span className="chip w">⚠ Revisar</span>}
                {item.duplicate && <span className="chip w">Ya en la colección</span>}
              </div>
              {item.duplicate && item.status === 'ready' && !item.force && (
                <button className="link" onClick={() => update(item.id, { force: true })}>
                  Subir igualmente
                </button>
              )}
              <div className="prog">
                <i style={{ width: `${item.progress * 100}%` }} className={item.status === 'done' ? 'done' : ''} />
              </div>
              <div className="pt">
                {item.status === 'reading' && 'Identificando…'}
                {item.status === 'ready' && `Listo · ${formatBytes(item.file.size)}`}
                {item.status === 'uploading' && `Subiendo · ${Math.round(item.progress * 100)} % de ${formatBytes(item.file.size)}`}
                {item.status === 'done' && `Subido · ${formatBytes(item.file.size)}`}
                {item.status === 'error' && <span className="error">{item.error}</span>}
              </div>
            </div>
          </div>
        )
      })}

      {items.some((i) => KEEP_NAME.has(extension(i.file.name))) && (
        <Banner>Los .cue, .bin y .m3u se suben con su nombre original: el .cue y el .m3u nombran a los otros archivos.</Banner>
      )}

      <div className="opt">
        <span>Enviar también al stick</span>
        <Switch on={toStick} onChange={setToStick} label="Enviar también al stick" disabled={uploading} />
      </div>

      <button className="btn" disabled={!pending || uploading || !state.online} onClick={uploadAll}>
        {uploading ? 'Subiendo…' : pending ? `Subir ${pending} ${pending === 1 ? 'archivo' : 'archivos'}` : 'Elige archivos para subir'}
      </button>
    </main>
  )
}
