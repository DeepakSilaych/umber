import path from 'node:path'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    // import.meta.dirname (not __dirname) — Vite 8's native ESM config loader warns on __dirname.
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
  server: {
    proxy: {
      // Umber's FastAPI dev server runs on :8000. Proxying here means the app can use plain
      // relative fetch('/v1/...') calls that work unchanged in production, where the same
      // process serves both the API and this build from a single origin.
      '/v1': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
      '/healthz': {
        target: 'http://localhost:8000',
        changeOrigin: true,
      },
    },
  },
})
