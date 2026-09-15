import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    // Spring Boot's WebFlux auto-configuration already serves static content from
    // this path — no backend code needed to serve the built app. Single deployable
    // artifact, same origin as the API, so no CORS config anywhere.
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    // `npm run dev` proxies API/WS calls to the backend (assumed running via
    // docker-compose on :8080), so the dev server is same-origin too — no separate
    // CORS setup needed even before the app is built.
    proxy: {
      '/auth': 'http://localhost:8080',
      '/channels': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
    pool: 'threads',
  },
})
