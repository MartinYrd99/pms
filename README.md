# PMS

Parking Management System — a Spring Boot API, a React PWA and PostgreSQL, brought up either as a
single Docker Compose stack or as separate dev processes (frontend hot-reloads, backend does not).

## Prerequisites

- **Docker** with Compose v2 (`docker compose`, not the standalone `docker-compose`) — for Compose
  mode, and for the backend's Testcontainers-backed tests.
- **JDK 25** — the backend (`pms-backend/pom.xml`) targets Java 25; only needed for dev mode, since
  Compose builds the backend in its own container (`eclipse-temurin:25-jdk`).
- **Node 22** — the frontend Dockerfile builds on `node:22-alpine`; only needed for dev mode.

## Compose mode

From the repository root:

```
docker compose up --build
```

This starts three services — `postgres` (PostgreSQL 17), `backend` (the Spring Boot API) and
`frontend` (nginx serving the built SPA) — in dependency order, each gated on the previous one's
healthcheck, so `backend` only starts migrating once Postgres accepts connections and `frontend`
only starts once the backend's `/actuator/health` reports `UP`.

Open **http://localhost:8080** — this is the SPA, served by nginx, which also proxies everything
under `/api/v1` to the backend so the browser only ever talks to one origin (no CORS involved).
Swagger UI is reachable at the same origin, `http://localhost:8080/swagger-ui.html`, and directly
against the backend container at `http://localhost:18080/swagger-ui.html` (the backend's own port
is published on the host as `18080` so it doesn't clash with the frontend's `8080`).

On first boot, Flyway runs the migrations under
`pms-backend/src/main/resources/db/migration/`, including the repeatable
`R__seed_zones_and_tariffs.sql`, which seeds three zones with an hourly tariff each — Blue Zone
and Green Zone in Sofia (both active, 2.00 EUR/h and 1.00 EUR/h), and Grey Zone in Plovdiv
(inactive, 2.50 EUR/h). **No user accounts are seeded** — register a new account in the app to log
in.

To reset to a clean database, tear the stack down including its volume:

```
docker compose down -v
```

## Dev mode

Backend and frontend run as separate processes, talking to a Postgres container — the frontend
hot-reloads on source changes, the backend does not.

Start the database only:

```
docker compose up postgres
```

Backend (from `pms-backend/`), which picks up the `postgres` container's defaults
(`localhost:5432`, db/user/password `pms`) without extra configuration — there is no
`spring-boot-devtools`, so it compiles once at startup and does **not** hot-reload; restart the
process to pick up code changes:

```
./mvnw spring-boot:run
```

Frontend (from `pms-frontend/`), which hot-module-reloads on source changes and proxies
`/api/v1/*` to `http://localhost:8080` by default — override the target with
`VITE_API_PROXY_TARGET` if the backend runs elsewhere:

```
npm install
npm run dev
```

## Tests

Backend (Testcontainers spins up a real PostgreSQL 17 container per run, so **Docker must be
running**):

```
cd pms-backend
./mvnw test
```

Frontend (Vitest + React Testing Library):

```
cd pms-frontend
npm test
```
