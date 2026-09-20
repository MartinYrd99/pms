---
exec-order: 32
category: add
depends-on: [24, 31]
status: done
suggested-agents: [frontend-developer]
---

# Package the SPA as an installable PWA with a shell-only service worker

## Business description

The task asks for a mobile application, and this product answers it without a native app: the driver
installs the web app to their phone's home screen and it launches like an app — full screen, own
icon, instant shell. This ticket adds that packaging with **vite-plugin-pwa** (Workbox) on top of
the finished SPA.

**What installable means here:** a web app manifest with the product name and short name,
`display: "standalone"`, a `start_url` of `/`, theme and background colours, and icons at 192×192
and 512×512 including a maskable one; the service worker precaches the built **app shell** (HTML,
JS, CSS, icons) with a navigation fallback to `index.html`, so a relaunch paints immediately instead
of waiting on the network. Keep the plugin's options in their own exported config module so they can
be asserted in a test.

**The rule that must not be broken: `/api/v1/*` is network-only.** No runtime caching entry, no
stale-while-revalidate, no precache match may ever serve an API response from the service worker,
and the navigation fallback must explicitly exclude `/api/v1/`. The reason is the product's core
promise: a cached "you have an active session" or a cached "payment PENDING" would show a driver a
state that is no longer true — the amount they just got, or the parking they just ended, would be
contradicted by a stale copy. Every API read goes to the server, every time.

**No offline writes, ever.** There is no background sync, no queue, no retry-when-online: a start,
end or pay that cannot reach the server is an **error shown to the user immediately**. The system
never pretends a parking started or a payment succeeded. An offline user may still see the app shell
load — but every action they attempt fails loudly.

**Out of scope:** push notifications, an install-prompt UI or "add to home screen" coaching, offline
screens, and any change to existing screens' behaviour.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Unit test over the exported PWA options: the manifest has the app name and short name,
  `display: "standalone"`, `start_url: "/"`, and both the 192×192 and 512×512 icons.
- Unit test over the same options: **no** runtime-caching rule matches a `/api/v1/...` URL, no
  background-sync/queue plugin is configured, and the navigation fallback denylist excludes
  `/api/v1/`.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: build and run the Compose stack, open the app in Chrome — the service worker registers and
  the app reports as installable (manifest, icons, standalone); install it and relaunch from the
  home screen/app window, and the shell paints without a network round trip. Then, **with the
  network panel open, drive a full parking (list zones, start, active, end, pay)**: every
  `/api/v1/*` entry must come from the network — none shows a service-worker/"(from ServiceWorker)"
  source and none is served from cache. Finally, go offline and tap Start → an error is shown; go
  back online and confirm **nothing was replayed** (no session was created while offline).
- Stress: none — this ticket carries no concurrency invariant.
