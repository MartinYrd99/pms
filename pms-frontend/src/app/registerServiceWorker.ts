import { registerSW } from "virtual:pwa-register";

/**
 * Registers the app-shell service worker. This only ever affects the precached shell
 * (HTML/JS/CSS/icons) — every /api/v1 call still goes straight to the network, so there is
 * nothing here for a screen to await or roll back.
 */
export function registerServiceWorker(): void {
  if (!("serviceWorker" in navigator)) {
    return;
  }
  registerSW({ immediate: true });
}
