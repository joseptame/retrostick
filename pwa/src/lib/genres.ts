// Shared by the webapp and the API functions (api/shop.ts, api/igdb.ts): no other imports here.
/** Spanish names for IGDB's genres (anything else keeps its English name). */
const GENRES_ES: Record<string, string> = {
  Adventure: 'Aventura',
  Arcade: 'Arcade',
  'Card & Board Game': 'Cartas y tablero',
  Fighting: 'Lucha',
  "Hack and slash/Beat 'em up": "Beat 'em up",
  Indie: 'Indie',
  MOBA: 'MOBA',
  Music: 'Música',
  Pinball: 'Pinball',
  Platform: 'Plataformas',
  'Point-and-click': 'Point and click',
  Puzzle: 'Puzle',
  'Quiz/Trivia': 'Preguntas',
  Racing: 'Carreras',
  'Real Time Strategy (RTS)': 'Estrategia en tiempo real',
  'Role-playing (RPG)': 'Rol (RPG)',
  Shooter: 'Disparos',
  Simulator: 'Simulación',
  Sport: 'Deportes',
  Strategy: 'Estrategia',
  Tactical: 'Táctico',
  'Turn-based strategy (TBS)': 'Estrategia por turnos',
  'Visual Novel': 'Novela visual',
}

export const genreEs = (g: string) => GENRES_ES[g] ?? g
