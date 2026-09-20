---
exec-order: 33
category: change
depends-on: [1, 2, 6, 23, 32]
status: done
suggested-agents: [backend-developer, frontend-developer]
---

# Bring the whole system up with one Docker Compose command and document both run modes

## Business description

Everything the product promises now exists — accounts, vehicles, zones, parking, pricing, payment and
history — but a reviewer still has to start three things by hand to see it. This ticket is the last in
the plan: after it, **one command brings up the entire system** and a person who has never seen the
repository can register, park, pay and read their history in a browser.

Compose mode serves the built single-page app and the API from the **same origin**: the web container
answers the browser, hands back the app's files, and forwards anything under `/api/v1` to the backend.
Same origin means there is no cross-origin configuration to get wrong in the packaged mode — the
browser only ever talks to one address.

The second audience is whoever works on the code next, so the ticket also documents **dev mode**: the
backend from Maven and the frontend from Vite with its dev proxy, both hot-reloading, which is how you
actually build features. The README is part of the deliverable, not an afterthought — a system nobody
can start is a system nobody can review.

**Scope**

- **One `docker-compose.yml` at the repository root with three services** (it supersedes the
  Postgres-only compose file from ticket 2; the test harness must keep working, so keep the same
  database name/credentials/port mapping or update that config alongside):
  - `postgres` — PostgreSQL 17, named volume for data, a `pg_isready` **healthcheck**.
  - `backend` — built from a Dockerfile in the backend module, `depends_on: postgres: condition:
    service_healthy`, database URL/credentials from environment, and its own healthcheck hitting
    `/actuator/health` (added in ticket 23). **Flyway migrations run on boot**, so a first start on an
    empty volume creates the full schema and the seeded zones/tariffs from ticket 6 are present.
  - `frontend` — a multi-stage Dockerfile that builds the SPA and serves the static output with
    **nginx**, `depends_on: backend: condition: service_healthy`, published on a host port (e.g. 8080).
- **nginx config** shipped with the frontend image: serve the built assets, `try_files … /index.html`
  so client-side routes deep-link correctly, and `location /api/v1 { proxy_pass http://backend:8080; }`
  forwarding the `Authorization` header and the original host. The SPA's API base URL in the built
  image is the **relative** `/api/v1`, so no origin is baked into the bundle.
- **`README.md` at the repository root** covering, in this order: prerequisites (Docker, JDK, Node);
  **Compose mode** — the single `docker compose up` command, the URL to open, where Swagger UI lives,
  and the seeded data a reviewer can log in with or register instead; **dev mode** — `mvnw
  spring-boot:run` for the backend plus `npm run dev` for the frontend with its `/api/v1` proxy, and
  what hot-reloads on each side; and how to run the test suites.

**Out of scope:** any production deployment concern (TLS, secrets management, image registries,
scaling) — the design rules deployment out. No new application behaviour: if the flow does not work in
Compose, the fix belongs in the ticket that owns that behaviour, not here.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- None — no runtime behaviour changes. The deliverable is packaging and documentation; the existing
  backend and frontend suites must still pass unchanged after the compose/Dockerfile work.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: from a clean state (`docker compose down -v`) run `docker compose up`, wait for all three
  services to report healthy, and drive the **full 9-step flow in a real browser** against the
  composed stack on the published port: register a new account and log in → add a vehicle (plate,
  brand, model) → open the zone list and pick an **active** zone, seeing its hourly rate → start
  parking → see the active session with vehicle, zone, start time and an elapsed timer that advances
  → end parking and see the computed amount → tap Pay and see the status `PENDING` → watch the status
  reach **COMPLETED** without reloading the page → open History and find that session listed newest
  first with its amount and `COMPLETED` payment status, times shown in Europe/Sofia. Confirm along the
  way that the browser makes **no cross-origin requests** (every API call goes to the same origin
  under `/api/v1`) and that reloading the page on a deep route (e.g. the history route) still loads
  the app. Finally open `/swagger-ui.html` on the same origin and confirm it responds.
