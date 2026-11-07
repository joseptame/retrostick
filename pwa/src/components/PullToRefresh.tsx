import { useEffect, useRef, useState } from 'react'

/**
 * Pull-to-refresh that only activates at the top of the page. The app scrolls on the window, so the
 * gesture is tracked on the document: a downward drag with `window.scrollY === 0` pulls the indicator;
 * releasing past the threshold runs `onRefresh`. It never calls `preventDefault`, so it coexists with
 * the browser's own overscroll (which `overscroll-behavior-y: contain` already neutralises).
 */
export function usePullToRefresh(onRefresh: () => Promise<unknown>) {
  const [pull, setPull] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const cb = useRef(onRefresh)
  cb.current = onRefresh

  useEffect(() => {
    const THRESHOLD = 72
    let tracking = false
    let startY = 0
    let amount = 0
    let busy = false

    const onStart = (e: TouchEvent) => {
      if (window.scrollY > 0 || busy || e.touches.length !== 1 || document.body.classList.contains('modal-open')) {
        tracking = false
        return
      }
      tracking = true
      startY = e.touches[0].clientY
      amount = 0
      setPull(0)
    }

    const onMove = (e: TouchEvent) => {
      if (!tracking || e.touches.length !== 1) return
      if (window.scrollY > 0) {
        amount = 0
        setPull(0)
        return
      }
      const delta = e.touches[0].clientY - startY
      amount = delta <= 0 ? 0 : Math.min(1, delta / THRESHOLD)
      setPull(amount)
    }

    const onEnd = () => {
      if (!tracking) return
      tracking = false
      const fire = amount >= 1 && !busy
      amount = 0
      setPull(0)
      if (fire) {
        busy = true
        setRefreshing(true)
        Promise.resolve(cb.current())
          .catch(() => {})
          .finally(() => {
            busy = false
            setRefreshing(false)
          })
      }
    }

    document.addEventListener('touchstart', onStart, { passive: true })
    document.addEventListener('touchmove', onMove, { passive: true })
    document.addEventListener('touchend', onEnd, { passive: true })
    document.addEventListener('touchcancel', onEnd, { passive: true })
    return () => {
      document.removeEventListener('touchstart', onStart)
      document.removeEventListener('touchmove', onMove)
      document.removeEventListener('touchend', onEnd)
      document.removeEventListener('touchcancel', onEnd)
    }
  }, [])

  return { pull, refreshing }
}

/** Small circular indicator that slides down from the top of the screen as the user pulls. */
export function PullToRefreshIndicator({ pull, refreshing }: { pull: number; refreshing: boolean }) {
  if (pull <= 0 && !refreshing) return null
  const offset = refreshing ? 84 : 84 * pull
  return (
    <div className="ptr" style={{ transform: `translate(-50%, ${offset}px)` }} aria-hidden>
      {refreshing ? (
        <span className="ptr-spin" />
      ) : (
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" style={{ transform: pull >= 1 ? 'rotate(180deg)' : 'none' }}>
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
      )}
    </div>
  )
}
