import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Dev: Vite on :5173 proxies API + WebSocket to the gateway on :8080.
// Build: output lands in the gateway's static resources, so one jar serves the whole product.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/ws': { target: 'ws://localhost:8080', ws: true },
    },
  },
  build: {
    outDir: '../gateway/src/main/resources/static',
    emptyOutDir: true,
  },
});
