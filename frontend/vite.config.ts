import path from "path"
import tailwindcss from "@tailwindcss/vite"
import react from "@vitejs/plugin-react"
import { defineConfig } from "vite"

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    // Same-origin calls to the three services during dev; the prefix is stripped.
    proxy: {
      "/api/identity": { target: "http://localhost:8081", rewrite: (p) => p.replace(/^\/api\/identity/, "") },
      "/api/authz": { target: "http://localhost:8082", rewrite: (p) => p.replace(/^\/api\/authz/, "") },
      "/api/audit": { target: "http://localhost:8083", rewrite: (p) => p.replace(/^\/api\/audit/, "") },
    },
  },
})