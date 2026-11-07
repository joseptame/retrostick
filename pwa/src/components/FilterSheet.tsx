import { genreEs } from '../lib/igdbdb'
import { Sheet, SheetHead } from './Sheet'

export type SortBy = 'name' | 'rating' | 'year'

export interface Filters {
  genres: string[]
  /** 0 = any; 6..9 = at least that rating; UNRATED = only games without a rating */
  minRating: number
  sort: SortBy
}

export type FilterSection = 'genre' | 'rating' | 'sort'

const TITLES: Record<FilterSection, string> = { genre: 'Género', rating: 'Valoración', sort: 'Ordenar' }

export const UNRATED = -1

export const NO_FILTERS: Filters = { genres: [], minRating: 0, sort: 'name' }

export const filtersActive = (f: Filters) => f.genres.length > 0 || f.minRating !== 0 || f.sort !== 'name'

const isSet = (s: FilterSection, f: Filters) => (s === 'genre' ? f.genres.length > 0 : s === 'rating' ? f.minRating !== 0 : f.sort !== 'name')
const reset = (s: FilterSection, f: Filters): Filters =>
  s === 'genre' ? { ...f, genres: [] } : s === 'rating' ? { ...f, minRating: 0 } : { ...f, sort: 'name' }

const SORTS: [SortBy, string][] = [
  ['name', 'Nombre'],
  ['rating', 'Mejor valorados'],
  ['year', 'Más recientes'],
]
const RATINGS = [0, 6, 7, 8, 9, UNRATED]

interface Props {
  /** Which filter the sheet edits */
  section: FilterSection
  value: Filters
  onChange: (f: Filters) => void
  /** Genres present in the current list, with how many games have each */
  genres: { name: string; count: number }[]
  /** Games shown with the current filters */
  results: number
  /** Games of the current list that have IGDB data */
  withData: number
  total: number
  onClose: () => void
}

/** One of Buscar's filters (genre, rating or sort) in a sheet; changes apply live. */
export function FilterSheet({ section, value, onChange, genres, results, withData, total, onClose }: Props) {
  const toggleGenre = (g: string) =>
    onChange({ ...value, genres: value.genres.includes(g) ? value.genres.filter((x) => x !== g) : [...value.genres, g] })

  return (
    <Sheet label={TITLES[section]} onClose={onClose}>
      {(close) => (
        <>
          <SheetHead title={TITLES[section]} sub={section === 'genre' ? `Géneros de IGDB: ${withData} de ${total} juegos tienen datos.` : 'Notas de prensa: Metacritic, GameRankings y críticas (vía Wikipedia e IGDB).'} onClose={() => close()} />

          {section === 'sort' && (
          <div className="pills wrap filter-body" role="radiogroup" aria-label="Ordenar por">
            {SORTS.map(([id, label]) => (
              <button key={id} role="radio" aria-checked={value.sort === id} className={`pill2 ${value.sort === id ? 'on' : ''}`} onClick={() => onChange({ ...value, sort: id })}>
                {label}
              </button>
            ))}
          </div>
          )}

          {section === 'rating' && (
          <>
          <div className="pills wrap filter-body" role="radiogroup" aria-label="Valoración mínima">
            {RATINGS.map((r) => (
              <button key={r} role="radio" aria-checked={value.minRating === r} className={`pill2 ${value.minRating === r ? 'on' : ''}`} onClick={() => onChange({ ...value, minRating: r })}>
                {r === UNRATED ? 'Sin nota' : r ? `★ ${r}+` : 'Todas'}
              </button>
            ))}
          </div>
          <p className="filter-note">
            {value.minRating === UNRATED
              ? 'Juegos sin nota de prensa (o que no se han encontrado).'
              : 'Muchos juegos retro no tienen nota de prensa: con una nota mínima, esos se ocultan.'}
          </p>
          </>
          )}

          {section === 'genre' && (genres.length === 0 ? (
            <p className="filter-note">Ningún juego de esta lista tiene géneros en IGDB.</p>
          ) : (
            <div className="pills wrap filter-body" aria-label="Géneros">
              {genres.map((g) => {
                const on = value.genres.includes(g.name)
                return (
                  <button key={g.name} aria-pressed={on} className={`pill2 ${on ? 'on' : ''}`} onClick={() => toggleGenre(g.name)}>
                    {genreEs(g.name)} <span className="pill-count">{g.count}</span>
                  </button>
                )
              })}
            </div>
          ))}

          <div className="filter-actions">
            <button className="cta ghost" onClick={() => onChange(reset(section, value))} disabled={!isSet(section, value)}>
              Quitar
            </button>
            <button className="cta grow" onClick={() => close()}>
              Ver {results} {results === 1 ? 'juego' : 'juegos'}
            </button>
          </div>
        </>
      )}
    </Sheet>
  )
}
