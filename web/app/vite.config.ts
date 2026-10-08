import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// La web vive en /web del MISMO origen que la API (ver server/Web.kt). En
// desarrollo, Vite hace de ese origen: lo que va a /v1 (HTTP y WebSocket) lo
// pasa al servidor local, y asi tampoco hace falta CORS.
export default defineConfig({
  base: '/web/',
  plugins: [react()],
  server: {
    port: 5180,
    strictPort: true,
    proxy: {
      '/v1': { target: 'http://localhost:8300', ws: true, changeOrigin: false },
    },
  },
  build: {
    // Sin mapas de fuente en lo publicado: no hacen falta para depurar en la
    // PC y solo agrandan lo que se sirve.
    sourcemap: false,
    assetsInlineLimit: 0,
  },
});
