import type { StickStatus, StickTarget } from '../lib/library'
import { Icon } from './icons'

/**
 * «Al stick» when downloading or uploading a game. One stick (or none yet): a switch, Drive only or the stick.
 * Several on the account: the phone's picker, Solo Drive / each stick by name / Todos.
 */
export function StickTargetPicker({
  sticks,
  value,
  onChange,
  disabled,
}: {
  sticks: StickStatus[]
  value: StickTarget
  onChange: (t: StickTarget) => void
  disabled?: boolean
}) {
  const named = sticks.filter((s) => s.id)
  const on = value !== 'drive'
  if (named.length < 2) {
    return (
      <button
        role="switch"
        aria-checked={on}
        aria-label="Enviar también al stick"
        className={`bx-stick ${on ? 'on' : ''}`}
        onClick={() => onChange(on ? 'drive' : 'all')}
        disabled={disabled}
      >
        <Icon name="tv" />
        Al stick
        <span className="bx-sw" aria-hidden>
          <i />
        </span>
      </button>
    )
  }
  const label =
    value === 'drive' ? 'Solo Drive' : value === 'all' ? 'Todos los sticks' : (named.find((s) => s.id === value)?.name ?? 'Stick')
  return (
    <label className={`bx-stick bx-target ${on ? 'on' : ''} ${disabled ? 'disabled' : ''}`}>
      <Icon name="tv" />
      <span className="bx-target-txt">{label}</span>
      <svg className="bx-chev" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M6 9l6 6 6-6" />
      </svg>
      <select aria-label="A qué stick" value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        <option value="drive">Solo Drive</option>
        {named.map((s, i) => (
          <option key={s.id} value={s.id}>
            {s.name ?? `Stick ${i + 1}`}
          </option>
        ))}
        <option value="all">Todos los sticks</option>
      </select>
    </label>
  )
}

/** The starting choice: Drive only, or (when [toStick]) the main stick, which is «all» when there's just one. */
export function defaultTarget(sticks: StickStatus[], main: StickStatus | null, toStick: boolean): StickTarget {
  if (!toStick) return 'drive'
  return sticks.filter((s) => s.id).length > 1 && main?.id ? main.id : 'all'
}
