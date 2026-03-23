import { defineConfig } from 'astro/config';
import react from '@astrojs/react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  integrations: [react()],
  server: {
    port: 4321
  },
  vite: {
    plugins: [tailwindcss()],
    server: {
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/find': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/pst': {
          target: 'http://localhost:8080',
          changeOrigin: true
        },
        '/pdf': {
          target: 'http://localhost:8080',
          changeOrigin: true
        }
      }
    }
  }
});
