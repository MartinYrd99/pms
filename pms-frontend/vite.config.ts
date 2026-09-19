import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      // The app only ever calls the relative /api/v1 path, so dev stays same-origin and the
      // backend needs no CORS configuration.
      "/api/v1": {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});