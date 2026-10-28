import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// En local, el navegador llama a /api/... en el mismo origen (localhost:5173) y Vite
// reenvía cada ruta a su microservicio. Así no hace falta configurar CORS en desarrollo.
// En AWS ese papel lo cumple API Gateway (VITE_API_BASE_URL).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true, // el Redirect URI de Entra es exactamente http://localhost:5173
    proxy: {
      '/api/orders': 'http://localhost:8081',
      '/api/catalog': 'http://localhost:8082',
      '/api/mq-admin': 'http://localhost:8085',
    },
  },
})
