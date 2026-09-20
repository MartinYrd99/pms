# Implementation plan — Parking Management System

33 tickets derived solely from `doc/system_design.md`. Execute in numeric order; a ticket may only
depend on lower-numbered ones. After ticket 33 the whole system design is implemented and runnable.

Ticket 34 onward is hardening: work that **changes** a decision the design doc records rather than
implementing one, and so updates `doc/system_design.md` as part of its scope.

Run one with the `/task` skill:

```
/task @.claude/plan/phase-1-foundation/01-backend-project-skeleton.md
```

Close a finished ticket by editing its frontmatter `status: open` → `status: fixed`.

## Phase 1 — Foundation (`phase-1-foundation/`)

| # | Ticket | Delivers |
|---|---|---|
| 01 | [Backend project skeleton](phase-1-foundation/01-backend-project-skeleton.md) | Maven + Java 25 + Spring Boot 4, `mvnw verify` green, actuator |
| 02 | [Local dev stack and test harness](phase-1-foundation/02-local-dev-stack-and-test-harness.md) | Compose Postgres 17, datasource, Testcontainers base test |
| 03 | [Migration — identity tables](phase-1-foundation/03-migration-identity-tables.md) | `users`, `refresh_tokens` |
| 04 | [Migration — ownership and pricing tables](phase-1-foundation/04-migration-ownership-and-pricing-tables.md) | `vehicles`, `zones`, `tariffs` |
| 05 | [Migration — sessions and payments](phase-1-foundation/05-migration-parking-sessions-and-payments.md) | `parking_sessions`, `payments` + the CHECKs and partial unique indexes that carry N1 |
| 06 | [Seed zones and tariffs](phase-1-foundation/06-seed-zones-and-tariffs.md) | Blue 2.00 / Green 1.00 EUR/h, plus an inactive zone |

## Phase 2 — Auth and API conventions (`phase-2-auth/`)

| # | Ticket | Delivers |
|---|---|---|
| 07 | [Error response contract](phase-2-auth/07-error-response-contract.md) | `{code, message}`, 400/401/403/404/409 mapping |
| 08 | [User registration](phase-2-auth/08-user-registration.md) | `POST /auth/register`, BCrypt |
| 09 | [Login and JWT access tokens](phase-2-auth/09-login-and-jwt-access-tokens.md) | `POST /auth/login`, 10-min access JWT, filter chain |
| 10 | [Refresh rotation and logout](phase-2-auth/10-refresh-token-rotation-and-logout.md) | `POST /auth/refresh`, `POST /auth/logout`, 48 h hashed tokens |
| 11 | [OpenAPI and Swagger UI](phase-2-auth/11-openapi-and-swagger-ui.md) | The browser surface every backend ticket is verified through |

## Phase 3 — Vehicles and zones (`phase-3-vehicles-zones/`)

| # | Ticket | Delivers |
|---|---|---|
| 12 | [Vehicles API](phase-3-vehicles-zones/12-vehicles-api.md) | `GET`/`POST /vehicles`, plate unique → 409 |
| 13 | [Zones and tariffs API](phase-3-vehicles-zones/13-zones-and-tariffs-api.md) | `GET /zones` — active only, with current tariff |

## Phase 4 — Parking (`phase-4-parking/`)

| # | Ticket | Delivers |
|---|---|---|
| 14 | [Pricing domain and clock](phase-4-parking/14-pricing-domain-and-clock.md) | `max(1, ceilDiv(millis, 3_600_000))` × rate, injectable `Clock` |
| 15 | [Start parking](phase-4-parking/15-start-parking.md) | `POST /parking-sessions`, 409 carries the blocking session |
| 16 | [Active sessions view](phase-4-parking/16-active-sessions-view.md) | `GET /parking-sessions/active`, 0..n |
| 17 | [End parking](phase-4-parking/17-end-parking.md) | Guarded update, amount frozen, 409 carries the ended session |
| 18 | [Session detail](phase-4-parking/18-session-detail.md) | `GET /parking-sessions/{id}` |
| 19 | [Parking history](phase-4-parking/19-parking-history.md) | Paginated, newest first, index-backed |

## Phase 5 — Payments (`phase-5-payments/`)

| # | Ticket | Delivers |
|---|---|---|
| 20 | [Create payment](phase-5-payments/20-create-payment-for-ended-session.md) | Idempotent `POST .../payment` → PENDING |
| 21 | [Payment status read](phase-5-payments/21-payment-status-read.md) | Live payment else most recent; fills `paymentStatus` in 18/19 |
| 22 | [Settlement job](phase-5-payments/22-settlement-job.md) | `@Scheduled` DB scan, claim-before-charge, one payment per tx |
| 23 | [Settlement liveness and PENDING expiry](phase-5-payments/23-settlement-liveness-and-pending-expiry.md) | Health indicator + 15-min PENDING → FAILED |

## Phase 6 — Frontend (`phase-6-frontend/`)

| # | Ticket | Delivers |
|---|---|---|
| 24 | [Frontend skeleton and dev proxy](phase-6-frontend/24-frontend-skeleton-and-dev-proxy.md) | Vite + React 19 + TS, router, TanStack Query, compose service |
| 25 | [Typed API client](phase-6-frontend/25-typed-api-client.md) | All 14 endpoints typed, 401 → refresh once → retry |
| 26 | [Auth screens and protected routing](phase-6-frontend/26-auth-screens-and-protected-routing.md) | Register, login, logout, route guard |
| 27 | [Vehicles screens](phase-6-frontend/27-vehicles-screens.md) | List + add, duplicate plate surfaced |
| 28 | [Zone selection and start parking](phase-6-frontend/28-zone-selection-and-start-parking.md) | Rate shown before start; 409 → go to blocking session |
| 29 | [Active session and end parking](phase-6-frontend/29-active-session-and-end-parking.md) | Local elapsed timer, End, 409 → show ended session |
| 30 | [Payment and bounded polling](phase-6-frontend/30-payment-and-bounded-status-polling.md) | Pay + 2/5/10 s backoff, hard stop at ~2 min |
| 31 | [Parking history](phase-6-frontend/31-parking-history.md) | Paginated, Europe/Sofia |
| 32 | [PWA packaging](phase-6-frontend/32-pwa-packaging.md) | Installable; shell-only SW, `/api/v1/*` network-only |

## Phase 7 — Packaging (`phase-7-packaging/`)

| # | Ticket | Delivers |
|---|---|---|
| 33 | [Full-stack compose and run instructions](phase-7-packaging/33-full-stack-compose-and-run-instructions.md) | postgres + backend + nginx SPA, one command, README |

## Phase 8 — Hardening (`phase-8-hardening/`)

| # | Ticket | Delivers |
|---|---|---|
| 34 | [HttpOnly refresh cookie](phase-8-hardening/34-httponly-refresh-cookie.md) | Refresh token out of `localStorage` into an `HttpOnly` cookie; access token in memory only |

## Conventions these tickets follow

- **Implemented** tests (unit / integration / repository / component) are written by the developer agents.
- **Verified at review time** (E2E, stress) is executed live by `/task` against the running stack —
  no Playwright specs, Gatling simulations or load scripts land in the repo. Backend tickets are
  verified through Swagger UI (ticket 11); the SPA gets real browser E2E from ticket 26 onward.
- Stress lines appear only where a concurrency invariant exists: 05, 08, 10, 12, 15, 17, 20, 22, 28, 29, 30, 34.
