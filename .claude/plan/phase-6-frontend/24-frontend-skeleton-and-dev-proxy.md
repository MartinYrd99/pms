---
exec-order: 24
category: add
depends-on: [2]
status: done
suggested-agents: [frontend-developer]
---

# Create the frontend project skeleton, its dev proxy and its Compose service

## Business description

The product needs a mobile-first web app the driver opens on their phone. This ticket builds the
empty house: the project itself, the way it runs on a developer's machine, and the way it runs in
the one-command Compose stack a reviewer uses. No parking feature is delivered here — the payoff is
that every later screen ticket can be written without touching tooling.

Three things ship.

**1 — The project.** A new `pms-frontend/` at the repository root: Node LTS + **Vite**,
**React 19 + TypeScript in strict mode**, **react-router** with a single placeholder route at `/`,
and a **TanStack Query** `QueryClientProvider` mounted at the app root (all server state in this app
goes through it later). An app shell/layout component provides the page frame: a header with the
product name and a `<main>` where routed screens render. Styling is **plain CSS, mobile-first** — a
small baseline stylesheet (viewport meta tag, single-column layout, readable base type, comfortable
tap targets, visible focus outline) and nothing more; the product explicitly calls for no complex
visual design, so no UI framework and no CSS-in-JS library. **Vitest + React Testing Library**
(jsdom environment, a setup file, a `test` npm script) are configured and green.

**2 — The dev proxy.** The app always calls the API at the **relative** path `/api/v1/...`, never at
an absolute host. The Vite dev server proxies `/api/v1` to the backend (default target
`http://localhost:8080`, overridable by an env var) with `changeOrigin` on, so development is
same-origin and **the backend needs no CORS configuration at all**.

**3 — The Compose service.** Add a **`frontend`** service to the existing root `docker-compose.yml`
(which today has `postgres` and `backend`): a multi-stage Dockerfile in `pms-frontend/` that builds
the SPA and serves the static output with **nginx**. The nginx config must do two things — serve the
SPA with a history fallback (`try_files $uri /index.html`, so deep links and reloads work) and
proxy `/api/v1/` to the `backend` service, keeping Compose mode same-origin too. The service is
published on a host port and starts after `backend`.

**Out of scope:** the typed API client and token handling (ticket 25), every real screen (26–31),
and the PWA manifest/service worker (ticket 32) — do not add `vite-plugin-pwa` here.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Component smoke test: rendering the app at `/` inside the real provider stack shows the shell
  header and the placeholder screen.
- Provider test: a component calling `useQuery` against a mocked `fetch` resolves and renders its
  data inside the app tree — proving the `QueryClientProvider` is actually mounted.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: `docker compose up`, open the frontend port in a browser → the shell renders; reload on a
  deep path (e.g. `/anything`) → still the SPA, not an nginx 404; in the network panel request
  `/api/v1/zones` from the same origin → it reaches the backend (a 401, not an nginx 404), proving
  the proxy. Repeat with `npm run dev` → the same relative URL works with no CORS error in console.
- Stress: none — this ticket carries no concurrency invariant.
