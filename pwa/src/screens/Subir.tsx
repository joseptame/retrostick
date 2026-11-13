import { useState } from 'react'
import type { AppState } from '../App'
import { Icon } from '../components/icons'
import { defaultTarget, StickTargetPicker } from '../components/StickTargetPicker'
import { requestForTarget, targetParent, type StickTarget } from '../lib/library'
import { Banner, Cover } from '../components/ui'
import { UploadsPanel } from '../components/UploadsPanel'
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
  const sticks = state.library?.sticks ?? []
  const [target, setTarget] = useState<StickTarget>(() => defaultTarget(sticks, state.library?.status ?? null, true))
  const toStick = target !== 'drive'
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
      const parent = targetParent(state.library, state.folders, item.console, target)
      const fileName = `${item.name}.${extension(item.file.name)}`
      update(item.id, { status: 'uploading', progress: 0 })
      const uid = state.uploadsApi.start({
        key: `local/${item.id}`,
        title: displayTitle(item.name),
        console: item.console,
        coverName: item.name,
        total: item.file.size,
        toStick,
        detail: fileName !== item.file.name ? `renombrado a ${fileName}` : undefined,
      })
      try {
        await uploadResumable(item.file, fileName, parent, (sent, total) => {
          update(item.id, { progress: total ? sent / total : 1 })
          state.uploadsApi.progress(uid, sent)
        })
        const full = await requestForTarget(state.library, state.folders, item.console, fileName, target, item.file.size)
        if (full.length) state.notify(`${fileName} no cabe en ${full.join(' ni en ')}: se queda en tu Drive`)
        update(item.id, { status: 'done', progress: 1 })
        state.uploadsApi.finish(uid)
      } catch (e) {
        const msg = e instanceof Error ? e.message : String(e)
        update(item.id, { status: 'error', error: msg })
        state.uploadsApi.finish(uid, msg)
      }
    }
    setUploading(false)
    state.refresh()
  }

  const pending = items.filter((i) => i.status === 'ready' && (!i.duplicate || i.force)).length

  return (
    <main className="screen">
      <UploadsPanel uploads={state.uploads} onClear={state.uploadsApi.clear} />

      <label className={`dropzone ${!state.online || uploading ? 'disabled' : ''}`}>
        <span className="dropzone-ico">
          <Icon name="upload" strokeWidth={2.4} />
        </span>
        <b>Elige tus copias</b>
        <small>Detecta la consola y pone el nombre correcto</small>
        <span className="formats">
          {['gb', 'gbc', 'gba', 'chd', 'cue', 'bin', 'm3u', 'pbp'].map((f) => (
            <i key={f}>.{f}</i>
          ))}
        </span>
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

      {items.length > 0 && (
        <h3 className="sec2">
          Archivos <span>{items.length}</span>
        </h3>
      )}
      <div className="rows">
        {items.map((item) => {
          const ext = extension(item.file.name)
          const renamed = item.name !== baseName(item.file.name)
          return (
            <div key={item.id} className={`row-card up-row ${item.status}`}>
              {item.console ? (
                <Cover console={item.console} name={item.name} title={displayTitle(item.name)} className="row-thumb big" />
              ) : (
                <span className="row-ico big">
                  <Icon name="file" />
                </span>
              )}
              <div className="grow">
                {renamed && <div className="up-old">{item.file.name}</div>}
                {item.status === 'ready' && !item.ident && !KEEP_NAME.has(ext) ? (
                  <input
                    className="up-name-input"
                    value={item.name}
                    aria-label="Nombre del juego"
                    onChange={(e) => update(item.id, { name: e.target.value.trim() || baseName(item.file.name) })}
                  />
                ) : (
                  <b className="up-name">{`${item.name}.${ext}`}</b>
                )}
                <div className="chips">
                  {item.console && <span className="chip">{consoleById(item.console).short}</span>}
                  {item.ident && item.ident.by !== 'name' && <span className="chip g">✓ Reconocido</span>}
                  {item.ident?.by === 'name' && <span className="chip w">≈ Por nombre</span>}
                  {item.status === 'ready' && !item.ident && <span className="chip w">Revisar nombre</span>}
                  {item.duplicate && <span className="chip w">Ya en la colección</span>}
                </div>
                {item.duplicate && item.status === 'ready' && !item.force && (
                  <button className="text-btn" onClick={() => update(item.id, { force: true })}>
                    Subir igualmente
                  </button>
                )}
                {(item.status === 'uploading' || item.status === 'done') && (
                  <div className={`line-prog ${item.status === 'done' ? 'done' : ''}`}>
                    <i style={{ width: `${item.progress * 100}%` }} />
                  </div>
                )}
                <div className={`up-status ${item.status}`}>
                  {item.status === 'reading' && 'Identificando…'}
                  {item.status === 'ready' && `Listo · ${formatBytes(item.file.size)}`}
                  {item.status === 'uploading' && `Subiendo · ${Math.round(item.progress * 100)} % de ${formatBytes(item.file.size)}`}
                  {item.status === 'done' && `Subido · ${formatBytes(item.file.size)}`}
                  {item.status === 'error' && item.error}
                </div>
              </div>
              {item.status !== 'uploading' && item.status !== 'done' && !uploading && (
                <button className="icon-btn" aria-label={`Quitar ${item.file.name}`} onClick={() => setItems((l) => l.filter((i) => i.id !== item.id))}>
                  <Icon name="x" />
                </button>
              )}
              {item.status === 'done' && (
                <span className="gp-btn ok sm" role="img" aria-label="Subido">
                  <Icon name="check" strokeWidth={3.2} />
                </span>
              )}
            </div>
          )
        })}
      </div>

      {items.some((i) => KEEP_NAME.has(extension(i.file.name))) && (
        <Banner>Los .cue, .bin y .m3u se suben con su nombre original: el .cue y el .m3u nombran a los otros archivos.</Banner>
      )}

      {items.length > 0 && (
        <div className="action-bar">
          <StickTargetPicker sticks={sticks} value={target} onChange={setTarget} disabled={uploading} />
          <button className="cta grow" disabled={!pending || uploading || !state.online} onClick={uploadAll}>
            <Icon name="upload" strokeWidth={2.4} />
            {uploading ? 'Subiendo…' : pending ? `Subir ${pending} ${pending === 1 ? 'archivo' : 'archivos'}` : 'Nada que subir'}
          </button>
        </div>
      )}
    </main>
  )
}
