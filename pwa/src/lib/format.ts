export function formatBytes(bytes: number): string {
  if (bytes >= 1e9) return `${(bytes / 1e9).toLocaleString('es-ES', { maximumFractionDigits: 1 })} GB`
  if (bytes >= 1e6) return `${Math.round(bytes / 1e6).toLocaleString('es-ES')} MB`
  if (bytes >= 1e3) return `${Math.round(bytes / 1e3)} KB`
  return `${bytes} B`
}

export function formatWhen(iso: string | number): string {
  const d = new Date(iso)
  const today = new Date()
  const time = d.toLocaleTimeString('es-ES', { hour: '2-digit', minute: '2-digit' })
  const sameDay = (a: Date, b: Date) => a.toDateString() === b.toDateString()
  if (sameDay(d, today)) return `Hoy ${time}`
  const yesterday = new Date(today)
  yesterday.setDate(today.getDate() - 1)
  if (sameDay(d, yesterday)) return `Ayer ${time}`
  return `${d.toLocaleDateString('es-ES', { day: 'numeric', month: 'short' }).replace('.', '')} ${time}`
}

export function formatAgo(iso: string | number): string {
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60_000)
  if (minutes < 1) return 'ahora mismo'
  if (minutes < 60) return `hace ${minutes} min`
  if (minutes < 24 * 60) return `hace ${Math.floor(minutes / 60)} h`
  return `hace ${Math.floor(minutes / (24 * 60))} días`
}
