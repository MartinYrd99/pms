import type { VitePWAOptions } from "vite-plugin-pwa";

/**
 * vite-plugin-pwa / Workbox configuration for the installable app shell.
 *
 * Kept as its own module (instead of inline in vite.config.ts) so the manifest and the
 * service-worker rules below can be asserted directly in a unit test.
 */
export const pwaOptions: Partial<VitePWAOptions> = {
  registerType: "autoUpdate",

  injectRegister: false,
  manifest: {
    name: "Parking Management System",
    short_name: "Parking",
    description: "Start, track and pay for parking sessions from your phone's home screen.",
    display: "standalone",
    start_url: "/",
    scope: "/",
    background_color: "#ffffff",
    theme_color: "#863bff",
    icons: [
      { src: "/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      { src: "/icon-maskable-192.png", sizes: "192x192", type: "image/png", purpose: "maskable" },
      { src: "/icon-maskable-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
    ],
  },
  workbox: {
    globPatterns: ["**/*.{js,css,html,svg,png,ico,webmanifest}"],
    // A relaunch paints the shell from cache first; /api/v1/* is excluded so every API call
    // — including the navigation-style ones a driver's first paint depends on — still goes
    // to the network, never to a cached "active session" or "payment PENDING" snapshot.
    // The same reasoning covers the backend-served docs/health routes nginx proxies on the
    // packaged app's own origin (/swagger-ui.html, /swagger-ui/**, /v3/api-docs, /actuator/**):
    // once the service worker is active, a navigation to one of those must still reach the
    // backend instead of being answered with the cached index.html.
    navigateFallback: "/index.html",
    navigateFallbackDenylist: [
      /^\/api\/v1\//,
      /^\/swagger-ui\.html$/,
      /^\/swagger-ui\//,
      /^\/v3\/api-docs/,
      /^\/actuator\//,
    ],
    // No runtimeCaching entries: nothing here can turn an API response into a cached one, and
    // there is no background-sync/queue plugin — a start/end/pay that can't reach the server
    // fails immediately instead of being queued for later.
    runtimeCaching: [],
    cleanupOutdatedCaches: true,
    // Take over immediately instead of waiting for every open tab to close: a new service
    // worker activates and takes control of the current page as soon as it's installed, so the
    // precached shell is what actually controls the page after an install/update, not just after
    // some later reload.
    skipWaiting: true,
    clientsClaim: true,
  },
};
