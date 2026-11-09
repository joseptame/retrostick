import { useEffect, useRef, useState } from 'react'
import type { AppState } from '../App'
import { BIOS, type BiosEntry } from '../lib/bios'
import { listFolder, trashFile, uploadResumable, type DriveFile } from '../lib/drive'
import { Icon } from './icons'

/** Stick → Pantallas de inicio: the user's own BIOS, one per console, uploaded to Retro/Config/bios. */
export function BiosSection({ state }: { state: AppState }) {
  const folder = state.folders?.bios
  const [files, setFiles] = useState<DriveFile[] | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const input = useRef<HTMLInputElement>(null)
  const target = useRef<BiosEntry | null>(null)

  const load = async () => {
    if (!folder) return
    try {
      setFiles(await listFolder(folder))
    } catch {
      setFiles([])
    }
  }
  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [folder])

  const pick = (e: BiosEntry) => {
    target.current = e
    input.current?.click()
  }

  const upload = async (file: File) => {
    const e = target.current
    if (!e || !folder) return
    if (file.size > 4 * 1024 * 1024) return state.notify('Eso no parece una BIOS: es demasiado grande')
    setBusy(e.file)
    try {
      const old = (files ?? []).filter((f) => f.name === e.file)
      const up = await uploadResumable(file, e.file, folder, () => {})
      await Promise.all(old.map((f) => trashFile(f.id)))
      const known = up.md5Checksum && e.md5.includes(up.md5Checksum)
      state.notify(
        known
          ? `BIOS de ${e.console} subida. Llegará al stick en la próxima sincronización`
          : `BIOS de ${e.console} subida, pero no es una versión conocida: se usará igualmente`,
      )
      await load()
    } catch (err) {
      state.notify(`No se pudo subir: ${err instanceof Error ? err.message : err}`)
    } finally {
      setBusy(null)
    }
  }

  return (
    <>
      <h3 className="sec2">Pantallas de inicio</h3>
      <p className="lead">
        <Icon name="sparkle" />
        <span>
          Antes de cada juego, la pantalla de inicio de su consola. Cada una necesita la BIOS de tu propia consola
          (RetroStick no las descarga). NES y Super Nintendo no tenían.
        </span>
      </p>
      <div className="rows" style={{ marginTop: 10 }}>
        {BIOS.map((e) => {
          const f = files?.find((x) => x.name === e.file)
          const known = f?.md5Checksum ? e.md5.includes(f.md5Checksum) : false
          return (
            <button key={e.file} className="row-card row-btn" disabled={!folder || busy !== null} onClick={() => pick(e)}>
              <span className="row-ico">
                <Icon name={f ? 'check' : 'upload'} />
              </span>
              <div className="grow">
                <b>{e.console}</b>
                <small>
                  {busy === e.file ? (
                    'Subiendo…'
                  ) : f ? (
                    <span className={`chip ${known ? 'g' : 'w'}`}>{known ? 'Subida' : 'Versión no reconocida'}</span>
                  ) : files === null ? (
                    'Comprobando…'
                  ) : (
                    e.shows
                  )}
                </small>
              </div>
              <span className="chip">{f ? 'Cambiar' : 'Subir'}</span>
            </button>
          )
        })}
      </div>
      <input
        ref={input}
        type="file"
        hidden
        onChange={(ev) => {
          const file = ev.target.files?.[0]
          ev.target.value = ''
          if (file) void upload(file)
        }}
      />
    </>
  )
}
