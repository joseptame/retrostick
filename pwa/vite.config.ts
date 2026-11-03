import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { VitePWA } from 'vite-plugin-pwa'

// Deployed on Vercel at the domain root (README 7.1).
export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['apple-touch-icon.png'],
      manifest: {
        name: 'RetroStick',
        short_name: 'RetroStick',
        description: 'Tu colección retro, tus partidas y tu stick, desde el móvil.',
        lang: 'es',
        start_url: '/',
        display: 'standalone',
        background_color: '#0d0b1f',
        theme_color: '#0d0b1f',
        icons: [
          { src: 'icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: 'icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        // The game databases (public/db) are fetched on demand and cached at runtime, not precached.
        globPatterns: ['**/*.{js,css,html,png,svg}'],
        // /__login and /__logout are handled by the server-side access gate (middleware.ts)
        navigateFallbackDenylist: [/^\/__/],
        runtimeCaching: [
          {
            // Box art (README 7.5): CacheFirst, 30 days
            urlPattern: /^https:\/\/raw\.githubusercontent\.com\/libretro-thumbnails\//,
            handler: 'CacheFirst',
            options: {
              cacheName: 'covers',
              expiration: { maxEntries: 1000, maxAgeSeconds: 30 * 24 * 3600 },
              cacheableResponse: { statuses: [0, 200] },
            },
          },
          {
            urlPattern: ({ url }) => url.pathname.startsWith('/db/'),
            handler: 'StaleWhileRevalidate',
            options: { cacheName: 'game-db' },
          },
          {
            urlPattern: /^https:\/\/fonts\.(googleapis|gstatic)\.com\//,
            handler: 'CacheFirst',
            options: { cacheName: 'fonts', expiration: { maxAgeSeconds: 365 * 24 * 3600 } },
          },
        ],
      },
    }),
  ],
})
