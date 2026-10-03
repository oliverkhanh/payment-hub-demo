import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Dev server chạy cổng 5173; backend merchant-demo ở http://localhost:8081 (CORS đã mở).
export default defineConfig({
  plugins: [react()],
  server: { port: 5173 }
})
