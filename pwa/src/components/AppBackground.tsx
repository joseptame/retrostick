/** Gamepad outline from the app icon (512×512 grid). */
const PAD = 'M160 170 H352 Q402 170 402 220 V296 C402 330 384 372 340 374 C310 376 296 360 286 346 H226 C216 360 202 376 172 374 C128 372 110 330 110 296 V220 Q110 170 160 170 Z'

/**
 * App background ("I1"): the icon's gamepad as a faint neon sign in the middle of the screen, a soft violet
 * halo and fine scanlines. Fixed behind every screen; purely decorative.
 */
export function AppBackground() {
  return (
    <div className="app-bg" aria-hidden>
      <div className="app-bg-halo" />
      <svg className="app-bg-pad" viewBox="0 0 512 512">
        <g fill="none" strokeLinecap="round" strokeLinejoin="round">
          <g className="app-bg-glow">
            <path d={PAD} />
          </g>
          <g className="app-bg-line">
            <path d={PAD} />
            <path d="M185 233 h20 v19 h19 v20 h-19 v19 h-20 v-19 h-19 v-20 h19 Z" />
            <circle cx="310" cy="244" r="13" />
            <circle cx="341" cy="276" r="13" />
          </g>
        </g>
      </svg>
      <div className="app-bg-scan" />
    </div>
  )
}
