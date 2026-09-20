import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";
import { defineConfig } from "vitest/config";
import { pwaOptions } from "./src/app/pwaOptions.ts";

const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react(), VitePWA(pwaOptions)],
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