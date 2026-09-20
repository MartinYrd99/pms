# Parking Management System — System Design

## 1. Functional requirements

1. **Auth:** username/password accounts, local Spring Security. Every call except login/register is authenticated; a user sees and acts only on their own data.
2. **Vehicles:** user registers vehicles (plate number, brand, model); one or more per user; a vehicle belongs to exactly one user.
3. **Zones:** pre-seeded, possibly across several cities; each has an `active` flag and its own tariff. Only active zones are offered.
4. **Start parking:** own vehicle + active zone → session with start time (UTC) and zone recorded. **One unsettled session per vehicle** — a second start is rejected while the vehicle has an active session *or an ended session that is not yet paid* (no parking on debt). A user with several vehicles may have several active sessions.
5. **Active session view:** vehicle, zone, start time, elapsed time.
6. **End parking:** records end time, computes the amount by the zone's tariff, persists both. Ending twice is rejected. Once ended, the session is immutable.
7. **Pricing:** per-zone hourly rate (Blue 2.00 EUR/h, Green 1.00 EUR/h). **Every started hour is a full hour**, minimum one hour: 35 min → 1 h, 60 min → 1 h, 60 min 01 s → 2 h. EUR only.
8. **Payment:** pay the amount of an ended session; exactly one payment per session; payment has a visible status (states decided in the data model).
9. **History:** the user's own sessions, newest first, with vehicle, zone, start/end, amount, payment status. Times shown in Europe/Sofia.

**Out of scope:** zone/tariff administration UI, real payment provider, deployment, polished visual design, production hardening.

> **Pricing extensibility note:** the task says zones "may have different prices *and rules*". MVP implements only the hourly rule, but the tariff is modelled per zone so a new rule type (daily cap, free first minutes) is an addition, not a rewrite.

## 2. Non-functional requirements

| # | Requirement | Statement |
|---|---|---|
| N1 | Integrity | **One unsettled session per vehicle** (active or unpaid) and **one payment per session** hold under concurrent requests — not just under sequential UI clicks. Amounts are exact to the cent; no charge is ever lost, duplicated, or recomputed. |
| N2 | Consistency | **Strong, read-your-writes.** When "end parking" returns, the amount is final and visible in history on the next read. No eventual consistency anywhere in the system. |
| N3 | Auditability | An ended session is immutable. Every amount is reproducible from (tariff in force at the time, start, end); a later tariff change never alters past amounts. |
| N4 | Security | Every call authenticated; a user can only read or act on **their own** vehicles, sessions, payments. Identity comes from the authenticated principal, never from a request field. Errors never leak internals. |
| N5 | Durability | Once "start", "end" or "pay" is acknowledged to the client, that record is never lost — there is no write we are allowed to drop. **Fail closed:** if the database is unavailable, every operation returns an error; the system never pretends a session started or a payment succeeded. |

## 3. Capacity estimates

**Assumptions:** 15–20k DAU (use 20k). A user parks **~1.5×/day**, average stay **~2 h**. One parking ≈ **12 API calls**: list vehicles, list zones, start, ~3 active-session views (app reopened during the stay), end, pay, ~2 payment-status checks, history. Peak = **10× daily average** (rush-hour + event concentration).

| Quantity | Average | Peak (10×) |
|---|---|---|
| Parkings / day | ~30,000 | — |
| API requests / day | ~360,000 | — |
| API RPS | ~4 | **~40** |
| Write TPS (start, end, pay = 3 per parking) | ~1 | **~10** |
| Concurrent active sessions (20k × 1.5 × 2 h / 24 h) | ~2,500 | ~10,000+ |
| Concurrent DB queries in flight (RPS × ~5 ms) | < 1 | < 1 |
| Storage growth (~0.5 KB / parking incl. payment + indexes) | ~15 MB/day, **~5.5 GB/year** | — |
| Sessions table after 1 year | **~11 M rows** | — |
| Money flowing through (× ~2 EUR avg) | ~60k EUR/day | — |

Sanity check on the peak: even if "10×" is applied to the busy hour instead of the daily average (~250 RPS, ~60 write TPS), the conclusions below don't change.

**What the numbers decide:**

- **One Spring Boot instance + one Postgres is oversized.** 40 RPS is ~1% of what either can do; nothing summons a cache, a queue, a read replica, or a second service. A second app instance would be for deploys, not load.
- **The only thing that grows is the sessions table.** 11 M rows/year means the history query must be index-backed (`user_id, started_at DESC`) and paginated from day one; no other table grows meaningfully (vehicles and users are bounded, payments track sessions 1:1).
- **The unsettled-session invariant stays cheap forever.** Only ~2.5–10k sessions are active at any moment, and unpaid ones are bounded by the number of vehicles (a vehicle with debt cannot start another), so a *partial* unique index on `vehicle_id WHERE paid_at IS NULL` stays tiny regardless of history size — it's the right tool for N1, not a lock or a service-layer check.
- **Concurrency is the real problem, not scale.** 10 write TPS makes a double-start race look unlikely, but a double-tap on a phone puts two identical requests ~50 ms apart in front of the same row — the DB constraint is what makes N1 true, not the low RPS.
- **60k EUR/day is why N1/N3/N5 are strict.** Compute is trivial; the data is not.

## 4. API design

All endpoints under `/api/v1`, JSON, authenticated with a **JWT bearer access token** (10 min) unless noted; a **refresh token** (48 h, rotated on use) renews it. No user id ever appears in a URL or body — the user is always the principal (N4).

| # | Endpoint | Task step | Notes |
|---|---|---|---|
| 1 | `POST /auth/register` | — | `{username, password}` → 201. Public. |
| 2 | `POST /auth/login` | — | `{username, password}` → `{accessToken}`, plus the refresh token as an `HttpOnly` cookie (`Set-Cookie`, `Secure`, `SameSite=Strict`, `Path=/api/v1/auth`, 48 h `Max-Age`). Public. |
| 3 | `POST /auth/refresh` | — | No body; reads the refresh cookie. → `{accessToken}` and a rotated refresh cookie; the old refresh token is revoked (rotation). 401 if the cookie is missing, unknown, expired or already used — also clears the cookie. Public. |
| 4 | `POST /auth/logout` | — | No body; reads the refresh cookie. → 204; revokes it and clears the cookie. The access token dies on its own within 10 min. |
| 5 | `GET /vehicles` | 1 | Own vehicles. |
| 6 | `POST /vehicles` | — | `{plate, brand, model}` → 201. Plate unique system-wide → 409 on duplicate. |
| 7 | `GET /zones` | 2 | **Active zones only**, with tariff so the UI can show the rate before starting. |
| 8 | `POST /parking-sessions` | 3 | `{vehicleId, zoneId}` → 201 session. 403 not own vehicle · 404/409 zone missing or inactive · **409 vehicle already parked or has an unpaid session** (body carries the blocking session id so the UI can send the user to it). |
| 9 | `GET /parking-sessions/active` | 4 | The user's active sessions (0..n, one per vehicle), each with `startedAt` so the UI computes elapsed time. |
| 10 | `POST /parking-sessions/{id}/end` | 5, 6 | State transition, so POST not PATCH. Returns the ended session **with `amount`**. **409 if already ended — body carries the ended session** (same rule as 8: a rejected transition tells the client the state it lost to). |
| 11 | `GET /parking-sessions/{id}` | 6 | Detail: times, amount, payment status. |
| 12 | `POST /parking-sessions/{id}/payment` | 7 | Creates the payment for an ended session → 201 `{status: PENDING}`. **Idempotent:** a repeat call returns the existing payment (200), never a second one — enforced by the 1:1 session→payment relation, not by client discipline. 409 if the session is still active. |
| 13 | `GET /parking-sessions/{id}/payment` | 8 | Payment status. Returns **the live payment (`PENDING`/`COMPLETED`) if one exists, otherwise the most recent one** (a `FAILED`, so the UI can offer retry). 404 if the session was never paid. The client polls this while `PENDING` — bounded, see client contracts. |
| 14 | `GET /parking-sessions?page=&size=` | 9 | History, ended sessions newest first, offset-paginated, each with amount + payment status. |

**Client contracts** (the SPA's side of the API, so the failure cases in §9 heal): on **409 from start** → navigate to the blocking session in the body; on **409 from end** → show the ended session in the body (the amount is there); on **401** → call refresh once and retry the request; if refresh also fails → login screen; **polling `PENDING` is bounded** — 2 s → 5 s → 10 s backoff, stop after ~2 min with "still processing — check history", never an unbounded loop (§10). The server-side state is never lost by any of these, so the UI only ever needs to *re-read*, never to *redo*.

**Errors:** one body everywhere — `{code, message}` — with `400` validation, `401` unauthenticated, `403` not owner, `404` unknown, `409` business-rule violation. Business rules are 409s, not 400s: the request was well-formed, the *state* refused it.

**Why `end` and `payment` are POSTs on the session, not a `PATCH status`:** each is a guarded state transition (`active → ended`, `ended → paid`) that must be checked and committed atomically; exposing "status" as a writable field would let the client pick invalid transitions. Guards live in one place, in the transaction.

**Why 409 rather than "return the existing session" on a double start:** the task says a second start is *rejected*; returning 200 would hide the rule. The 409 body includes the existing session id so a double-tapping client can still navigate to it.

**Why payment is asynchronous:** a real provider never answers synchronously — the client submits, then observes the status. The prototype mirrors that shape: `POST` creates the payment as `PENDING` and returns immediately; a background step settles it to `COMPLETED` (or `FAILED`) shortly after; the UI polls endpoint 11. This keeps step 8 of the task ("view payment status") a real step, and swapping the simulator for a provider later changes the settlement step, not the API. Idempotent `POST` is what makes client retries safe while a payment is in flight.

**Why an in-memory access token plus an `HttpOnly` refresh cookie:** the access token is never persisted — it lives in a module variable in the SPA and dies on reload — while the 48 h refresh token is a server-set `HttpOnly` cookie JavaScript can neither read nor write. This buys real protection against **exfiltration**: script injected into the page cannot copy the refresh token out to be replayed elsewhere, and a page reload leaves nothing credential-shaped in JavaScript-readable storage. It does *not* stop a script from *using* the session while it runs — the cookie still rides along on same-origin requests the page itself makes, so an XSS bug can still act as the user, just not walk away with a 48 h credential. Vite (dev) and nginx (Compose) both proxy `/api/v1` to the backend, so the browser sees one origin and the cookie needs no cross-site handling. A **double-submit CSRF token was deliberately not added**: `SameSite=Strict` plus scoping the cookie's `Path` to `/api/v1/auth` mean it is never attached to a cross-site request, and it is only ever sent by same-origin `fetch` calls, never a top-level navigation — so the residual worst case of a forced cross-site `POST` to `/auth/refresh` is a forced logout (blocked anyway by `SameSite=Strict`), not account access. The other twelve endpoints never see this cookie at all (`Path` scoping) and stay bearer-token-only, unaffected by cookie CSRF concerns.

**Why access + refresh rather than one long-lived JWT:** a parking session outlives any sane access-token TTL (§9, time attack), so *something* must last 48 h. Making that the refresh token — stored hashed, rotated on use, revocable — keeps the bearer token that travels on every request short-lived (10 min blast radius if stolen) and gives logout real meaning. Cost: one small table and two endpoints.

**Why offset pagination:** the history is per user and small (hundreds to low thousands of rows); offset is the simplest correct choice and the index `(user_id, started_at DESC)` serves it directly.

## 5. Data model

```
users     id BIGINT PK · username UNIQUE · password_hash VARCHAR(60) · created_at
          -- BCrypt; salt is embedded in the hash, no separate column

refresh_tokens
          id BIGINT PK · user_id FK · token_hash UNIQUE · expires_at · revoked_at NULL · created_at
          -- the raw token exists only in the client; a DB leak yields nothing usable
          -- valid ⇔ revoked_at IS NULL AND expires_at > now(); rotation = revoke old + insert new

vehicles  id BIGINT PK · user_id FK · plate UNIQUE · brand · model · created_at

zones     id BIGINT PK · name · city · active BOOL · created_at
          -- the place; stable identity, no price on it

tariffs   id BIGINT PK · zone_id FK · rule_type VARCHAR · hourly_rate NUMERIC(10,2)
          currency CHAR(3)='EUR' · valid_from · valid_to NULL     -- rows are immutable
          -- the price rule for a zone during a period; current = valid_to IS NULL
          -- rule_type is a Java enum (@Enumerated STRING); HOURLY is the only value in v1

parking_sessions
          id BIGINT PK · user_id FK (denormalized) · vehicle_id FK · zone_id FK
          tariff_id FK                              -- tariff in force at start (N3)
          started_at TIMESTAMPTZ · ended_at TIMESTAMPTZ NULL
          amount NUMERIC(10,2) NULL                 -- set exactly once, at end
          paid_at TIMESTAMPTZ NULL                  -- set exactly once, by settlement
          CHECK ((ended_at IS NULL) = (amount IS NULL))
          CHECK (ended_at >= started_at)
          CHECK (paid_at IS NULL OR ended_at IS NOT NULL)
          -- active ⇔ ended_at IS NULL · unpaid ⇔ ended_at NOT NULL AND paid_at IS NULL
          -- unsettled ⇔ paid_at IS NULL (either of the above); no separate status column

payments  id BIGINT PK · session_id FK · amount NUMERIC(10,2)   -- copied from session (N3)
          status VARCHAR (PENDING | COMPLETED | FAILED) · attempts INT DEFAULT 0
          created_at · settled_at NULL
          -- PENDING has a maximum age (15 min) and a maximum attempt count (3); either → FAILED
```

Enums (`rule_type`, `status`) are `VARCHAR` columns backed by Java enums with `@Enumerated(EnumType.STRING)`, not Postgres enum types — adding a value is a code change, not an `ALTER TYPE` that can't run inside a migration transaction.

**Indexes ⇄ access patterns:**

| Hot query / invariant | Index |
|---|---|
| **One unsettled session per vehicle (N1)** + "is this vehicle parked or in debt?" | `UNIQUE (vehicle_id) WHERE paid_at IS NULL` — partial, ≤ one row per vehicle forever |
| User's active sessions (step 4) | `(user_id) WHERE ended_at IS NULL` — partial, tiny |
| History, newest first, paginated (step 9) | `(user_id, started_at DESC)` — the only index that grows |
| **One live payment per session (N1)** + idempotent POST | `UNIQUE (session_id) WHERE status IN ('PENDING','COMPLETED')` |
| Settlement job: pending payments | `(created_at) WHERE status = 'PENDING'` — stays tiny |
| Zone's current tariff | `UNIQUE (zone_id) WHERE valid_to IS NULL` |
| Own vehicles / plate uniqueness | `(user_id)` / `UNIQUE (plate)` |
| Refresh: look up by presented token | `UNIQUE (token_hash)`; expired/revoked rows swept by a daily job or left — bounded by logins |

**Positions taken:**

- **Invariants are constraints, not code.** Both "one unsettled session per vehicle" and "one live payment per session" are partial unique indexes: two concurrent inserts → one commits, one gets a unique violation → mapped to 409 / idempotent-return. The service layer *also* checks first for a friendly error, but the index is what makes N1 true. `CHECK` constraints keep `ended_at`/`amount`/`paid_at` from drifting apart.
- **No parking on debt.** The unique index is on `paid_at IS NULL`, not `ended_at IS NULL`: a vehicle whose last session is ended but unpaid is still "unsettled" and cannot start a new one. `paid_at` is stamped on the session by the settlement transaction when its payment goes `COMPLETED` — the one write an ended session ever receives; `started_at`, `ended_at`, `amount`, `tariff_id` stay immutable (N3).
- **Zone ≠ tariff.** The zone answers *where*; the tariff answers *what it costs, during which period*. Tariffs are immutable, versioned rows; the session references the one in force at start. Changing a price = close the old row (`valid_to`), insert a new one. Amount is reproducible from `(tariff_id, started_at, ended_at)` forever (N3) — like an invoice line storing the unit price at time of sale — and `rule_type` is where a future rule kind plugs in (Functional §1 note). Alternative rejected: snapshot `hourly_rate` on the session — enough for audit, but leaves no place for rule types.
- **`amount` is still stored on the session** even though it's derivable — the task says "persist it", and history reads must not re-run pricing (N2, and 11 M rows/year).
- **Payment status is a state machine:** `PENDING → COMPLETED | FAILED`. `FAILED` is reached by the provider saying no, by `attempts` reaching 3, **or by `PENDING` exceeding 15 min** — the state has a time-based exit so a dead settlement job can never block a vehicle indefinitely (§10). Settlement **claims a row before doing anything external** (`SELECT … FOR UPDATE SKIP LOCKED`), handles **one payment per transaction**, and writes `COMPLETED` and `parking_sessions.paid_at` in that same transaction, so a session is never "paid" without a completed payment or vice versa, one poison payment cannot roll back others, and two instances can never both charge the same payment. A `FAILED` payment does **not** block re-paying: the partial unique index only counts live payments, so a new `PENDING` row can be inserted; failed rows remain as audit trail. "At most one COMPLETED per session" holds by the same index — and until one exists the vehicle stays blocked.
- **`user_id` denormalized on the session.** Ownership is derivable via `vehicle`, but history and active-session queries are per user; without it every hot read joins `vehicles`. Set once at insert from the vehicle's owner, never updated.
- **`BIGINT` identity ids over ULID/UUID.** Offset pagination means no time-sortable id is needed; enumeration exposes nothing because every read is ownership-scoped (N4).
- **`city` is a plain column on `zones`.** No rule depends on it; a `cities` table would be a box nothing summoned.
- **Postgres.** Partial unique indexes carry the two core invariants natively; MySQL has no partial indexes (would need a nullable "active marker" column trick). Single instance; §3 rules out anything more.

## 6. Stack selection

**Backend**

| Component | Choice | Why |
|---|---|---|
| Language | **Java 25** (LTS) | current LTS. Virtual threads and records are stable; nothing here needs preview features. |
| Framework | **Spring Boot 4.x** | Spring Security (auth, N4), Spring Data JPA (persistence), Bean Validation (400s), `@Scheduled` (payment settlement) — every box in §7 is a starter, no extra framework. |
| Database | **PostgreSQL 17** | Partial unique indexes carry both N1 invariants natively (§5); `NUMERIC` for money; `TIMESTAMPTZ`. Justified in §5, chosen here. |
| Migrations | **Flyway** | Plain SQL migrations — the schema in §5 is readable as-is by a reviewer; Liquibase's XML/YAML buys portability we don't need. Zones and tariffs are seeded by a repeatable migration that runs in **every** run mode: they are reference data with no admin UI (§1), so a database without them has no zone to select and no session that can start. |
| Build | **Maven** | Convention over choice; every reviewer can run `./mvnw verify` without knowing the project. |
| Auth | Spring Security + JWT (`spring-security-oauth2-jose`) | Short-lived access JWT + DB-backed refresh token per §4; BCrypt for passwords (§5). |
| API docs | **springdoc-openapi** | Swagger UI lets the reviewer drive the 14 endpoints without the SPA. |
| Tests | **JUnit 5 + Testcontainers (Postgres)** | Partial indexes and `CHECK`s *are* the design — testing against H2 would test a different schema. Requires Docker on the machine running the tests. |

**Frontend**

| Component | Choice | Why |
|---|---|---|
| Runtime / tooling | **Node.js** (current LTS) + **Vite** | Task allows React; Vite is the standard React toolchain, instant dev server, proxies `/api` to the backend in dev. |
| Framework | **React 19 + TypeScript** | Task allows React. TypeScript so the API DTOs are typed end to end — the 14 endpoints become one `api.ts` with typed request/response shapes and a single 401 → refresh → retry interceptor. |
| Routing / state | **react-router**; server state via **TanStack Query** | The UI is nine screens over fourteen endpoints; polling payment status and refreshing the active session is exactly what TanStack Query's `refetchInterval` does. No Redux — nothing summons global client state. |
| PWA | **vite-plugin-pwa** (Workbox) — installable, mobile-first | The task's "mobile application" without a native app: home-screen install, standalone window, app shell cached for instant launch. **Service worker caches the app shell only; `/api/v1/*` is network-only** — a cached "active" or "PENDING" response would violate N2. No offline writes: a start/end/pay that cannot reach the server is an error, never queued (N5, fail closed). |
| Styling | plain CSS / minimal component lib | Task: "no complex visual design". |
| Tests | **Vitest + React Testing Library** | Component/smoke tests against a mocked API. |

**End-to-end and load testing (UI → server → database)**

| Component | Choice | Why |
|---|---|---|
| E2E | **Playwright** driving the real stack (Compose: SPA + backend + Postgres), plus the **Playwright MCP server** | The 9-step flow is verified through the browser against the real schema, not mocks. The MCP server lets an AI agent drive and inspect the same flow interactively during development — the same browser automation, exposed as tools. |
| Load / stress | **Gatling** (Java DSL, Maven plugin) | Replays the §3 scenario at 10× (40–250 RPS) against the API to confirm the single-instance claim and, more importantly, to hammer the double-start / double-pay races with concurrent virtual users (N1). Java DSL keeps it in the same build. |

**Runtime / packaging**

| Component | Choice | Why |
|---|---|---|
| Local run | **Docker Compose**: `postgres`, `backend`, `frontend` (nginx serving the built SPA, proxying `/api/v1` → backend) | One command; same origin in Compose mode, so no CORS there. |
| Dev mode | backend via `mvnw spring-boot:run`, frontend via `vite` with proxy | Hot reload on both sides. |

## 7. High-level architecture

```
Browser — React PWA (TypeScript, TanStack Query)
  │  JSON over HTTP · Authorization: Bearer <access JWT, 10 min> · refresh token rotated via /auth/refresh
  ▼
Spring Boot API — single stateless instance
  ├─ request path   use cases, one transaction each; every state transition a guarded update
  ├─ settlement     @Scheduled DB scan · claim (FOR UPDATE SKIP LOCKED) · one payment per tx ──▶ ┌ payment provider ┐
  │                 attempts ≤ 3 · PENDING ≤ 15 min → FAILED                                    └ (simulated)      ┘
  │  JDBC · pool ≤ 10 · sole database client
  ▼
PostgreSQL 17
```

## 8. Happy paths

Two paths are chosen by **blast radius × novelty**: both touch money, and between them they exercise every invariant in §5 (one active session per vehicle, one live payment per session, immutable ended session, guarded state transitions) and the only asynchronous piece (settlement). Not chosen: register/login (standard Spring Security, no novelty), vehicle registration (one insert, one unique index), and the read endpoints (principal-scoped selects with no state change — nothing to attack beyond §5's indexes).

### 8.1 Path A — start parking → end parking

User U, own vehicle V, active zone Z with current tariff T (HOURLY, 2.00 EUR/h).

| # | Where | Action | State change |
|---|---|---|---|
| 1 | SPA | U selects V and Z, taps **Start** → `POST /parking-sessions {vehicleId: V, zoneId: Z}` with `Authorization: Bearer <JWT>` | — |
| 2 | API · security | JWT signature + expiry verified → principal = U | — |
| 3 | API · web | Bean Validation: both ids present | — |
| 4 | API · `StartParking` | **Transaction begins.** Load V; assert `V.user_id = U` (else 403) | — |
| 5 | | Load Z; assert `Z.active` (else 409) | — |
| 6 | | Load T = tariff of Z with `valid_to IS NULL` | — |
| 7 | DB | `INSERT parking_sessions (user_id=U, vehicle_id=V, zone_id=Z, tariff_id=T, started_at=clock.now(), ended_at=NULL, amount=NULL, paid_at=NULL)` — partial unique index `(vehicle_id) WHERE paid_at IS NULL` is checked here: V must have no active *and no unpaid* session | **S1: session S exists, active** |
| 8 | DB | Commit → 201 `{id: S, vehicle, zone, startedAt}` | S1 durable |
| 9 | SPA | Navigates to active view; `GET /parking-sessions/active` returns S; elapsed timer runs locally from `startedAt` | — |
| 10 | SPA | ~2 h later U taps **End** → `POST /parking-sessions/S/end` | — |
| 11 | API · security | JWT → principal = U (the SPA has refreshed the 10-min access token in the meantime — §4 client contracts) | — |
| 12 | API · `EndParking` | **Transaction begins.** Load S **and its tariff T via `S.tariff_id`** — nothing else: end never consults `zones.active` or `tariffs.valid_to`. Assert `S.user_id = U` (403); assert `S.ended_at IS NULL` (409, body = S) | — |
| 13 | API · domain | `endedAt = clock.now()`; `hours = max(1, ceilDiv(millis(endedAt − startedAt), 3 600 000))` — integer math, any positive remainder rolls up; `amount = BigDecimal(hours) × T.hourly_rate` (scale 2) → e.g. 2 h 05 min → 3 × 2.00 = **6.00 EUR** | — |
| 14 | DB | **Guarded update:** `UPDATE parking_sessions SET ended_at, amount WHERE id = S AND ended_at IS NULL` — 0 rows ⇒ someone ended it first ⇒ 409 with the current S. `CHECK ((ended_at IS NULL) = (amount IS NULL))` and `CHECK (ended_at >= started_at)` evaluated | **S2: S active → ended, amount fixed** |
| 15 | DB | Commit → 200 `{…, endedAt, amount: 6.00}` | S2 durable |
| 16 | SPA | Shows amount and a **Pay** button (→ Path B) | — |

State changes: **two** — S1 (row created, active) and S2 (row ended, amount written). Each is one transaction. After S2 the pricing fields are frozen; the only write S will ever receive again is `paid_at`, stamped by settlement in Path B. Until then V cannot start another session.

### 8.2 Path B — pay → settle → see status

Continues from Path A: session S is ended with `amount = 6.00`.

| # | Where | Action | State change |
|---|---|---|---|
| 1 | SPA | U taps **Pay** → `POST /parking-sessions/S/payment` | — |
| 2 | API · security | JWT → principal = U | — |
| 3 | API · `Pay` | **Transaction begins.** Load S; assert `S.user_id = U` (403); assert `S.ended_at IS NOT NULL` (409) | — |
| 4 | DB | `INSERT payments (session_id=S, amount=S.amount, status='PENDING', created_at=now())` — partial unique index `(session_id) WHERE status IN ('PENDING','COMPLETED')` checked here | **P1: payment P exists, PENDING** |
| 5 | DB | Commit → 201 `{id: P, status: PENDING, amount: 6.00}` | P1 durable |
| 6 | SPA | Shows "Payment pending…"; TanStack Query polls `GET /parking-sessions/S/payment` while `status = PENDING` — **bounded:** 2 s → 5 s → 10 s backoff, gives up after ~2 min with "still processing — check history" | — |
| 7 | API · jobs | `@Scheduled` settlement fires (every ~2 s). It is a **DB scan, never an in-memory timer** — restarts lose nothing. For each due row, **its own transaction:** `SELECT … FROM payments WHERE status='PENDING' AND created_at < now() − 3 s ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED` — the row is *claimed* before anything external happens, so a second instance skips it. Then the provider call (simulator: succeed; dev-profile hook: fail). Then `UPDATE payments SET status='COMPLETED', settled_at=now(), attempts=attempts+1 WHERE id=P` and `UPDATE parking_sessions SET paid_at=now() WHERE id=S AND paid_at IS NULL`. On provider failure: `attempts+1`, and `status='FAILED'` if `attempts ≥ 3`. Rows `PENDING` for > 15 min are set `FAILED` regardless | **P2: P PENDING → COMPLETED; S unpaid → paid** |
| 8 | DB | Commit — both rows or neither. The job records `last_successful_run`, exposed through `/actuator/health` | P2 durable; V may start a new session again |
| 9 | SPA | Next poll returns `COMPLETED`; polling stops; shows "Paid" | — |
| 10 | SPA | U opens **History** → `GET /parking-sessions?page=0&size=20` → S listed with `amount: 6.00`, `paymentStatus: COMPLETED` | — |

State changes: **two** — P1 (payment created) and P2 (payment settled *and* session stamped paid, atomically). P1 is the user's transaction; P2 belongs to the job. The `PENDING` window (steps 5–8) is the only moment in the whole system where a client can observe "in progress" state, and it is also the window in which the vehicle is still blocked from parking again — it is where the concurrency and failure attacks concentrate.

## 9. Attacks on Path A (start → end)

Five attacks — scale, failure, concurrency, time, abuse — were run over the 16 steps of §8.1. Only findings that broke or changed the design are recorded; everything that came back "trivial" or "accept" is omitted. **Scale produced nothing:** every row is a PK lookup or a partial index bounded by the vehicle count, and 10× changes no answer (§3 already said so). The path fails on *transitions*, not on load.

| # | Attack | Kill point / race | What happened as designed | Fix (applied) |
|---|---|---|---|---|
| A1 | **Concurrency** | Double-tap **End**: two requests ~50 ms apart | Both loaded S at step 12 under READ COMMITTED, both saw `ended_at IS NULL`, both computed, both updated at 14. Second overwrote `ended_at`/`amount`; **both returned 200, possibly with different amounts**. The two money invariants lived in the DB, but the `active → ended` transition was an unguarded read-then-write | Step 14 is a **guarded update** `… WHERE id = S AND ended_at IS NULL`; 0 rows ⇒ 409 with the current S. Rule, now universal: *every state transition (end, pay, settle) is a guarded update; zero rows updated means someone else got there first.* |
| A2 | **Failure** | Crash **after** commit at 15, response lost | Client retried End → 409 "already ended" **with no body** — the amount the user was waiting for was committed and unreachable without a second, unspecified call. Start had the symmetric case healed (409 carries the blocking id); End did not | End's 409 **carries the ended session** (§4 #10), same rule as Start's 409. Added the **client contracts** to §4: 409-on-start → go to blocking session; 409-on-end → show the body; 401 → refresh once and retry. The UI only ever *re-reads*, never *redoes*. |
| A3 | **Time** | Token expiry between step 8 and step 10 (a parking lasts hours; a JWT should not) | With one long-lived JWT, the **End** click would 401 mid-parking unless the TTL exceeded any parking (≥ 48 h) — a 48 h bearer token on every request. With a short TTL, the user is logged out at the moment they most need to act | **Access JWT 10 min + refresh token 48 h**, hashed in DB, rotated on use, revocable (§4 #3–4, §5 `refresh_tokens`). Nothing that lasts hours is a bearer token; logout becomes real. |
| A4 | **Time** | Hour-boundary arithmetic at step 13 | `ceil((endedAt − startedAt) / 1 h)` was a description, not a formula. Two natural implementations get it wrong: floating-point division (money in doubles), or minute-granularity first (60 min 30 s → 60 min → **1 h**, but the 61st minute has *started*) | Pinned: `hours = max(1, ceilDiv(millis, 3 600 000))`, `amount = BigDecimal(hours) × rate`. Integer math; any positive remainder rolls up. The boundary set **is** the unit test: `0 → 1 · 3 600 000 → 1 · 3 600 001 → 2 · 7 200 000 → 2 · 7 200 001 → 3`. |
| A5 | **Failure** | Zone deactivated or tariff closed (`valid_to` set) while S is active | If `EndParking` re-ran Start's checks (the obvious copy-paste), a city closing the Blue Zone at 18:00 would leave every car in it **unable to end**, still accruing, and — under the debt rule — unable to park anywhere else. A closed tariff would either block End or price with the *new* rate, violating N3 | Step 12 now says it: **End reads only S and `S.tariff_id`; it never consults `zones.active` or `tariffs.valid_to`.** The tariff frozen at start is the one that prices the session, whatever happened to the zone since. |

## 10. Attacks on Path B (pay → settle → status)

| # | Attack | Kill point / race | What happened as designed | Fix (applied) |
|---|---|---|---|---|
| B1 | **Concurrency** | Two instances settle the same payment — **with a real provider** | The guarded `UPDATE … WHERE status='PENDING'` protects the *write*, but a provider call sits *between* reading `PENDING` and writing `COMPLETED`. Both instances read P, **both call the provider → double charge**, then one loses the update — after the money moved. With the simulator this is invisible, which is why the "swap the simulator, keep the settlement step" claim was false in the one way that costs money | Settlement **claims before it calls**: `SELECT … FOR UPDATE SKIP LOCKED`, **one payment per transaction** (a poison payment can't roll back a batch), `attempts` counter → `FAILED` after 3 (§5, §8.2 step 7). The simulator uses the same shape at zero cost. |
| B2 | **Failure / Time** | Settlement job dead (profile, pool exhausted, misconfig) | Every payment stays `PENDING` forever; under the debt rule every paying vehicle is **blocked forever**; the UI says "pending…" forever; **nothing notices** — the job had no liveness signal and `PENDING` had no maximum age | Two exits: the job publishes `last_successful_run` as a health indicator (§8.2 step 8); **`PENDING` older than 15 min → `FAILED`** (§5), so the worst case is "payment failed — retry", never an indefinite block. |
| B3 | **Scale** | Settlement stalls while clients poll | Each pending client polled every 2 s indefinitely. 10k pending × 0.5 RPS = **5,000 RPS** from our own UI — 100× the peak the system was sized for, manufactured by a stalled job | **Bounded polling** in the client contract (§4, §8.2 step 6): backoff 2 → 5 → 10 s, stop after ~2 min. A stalled job degrades to *slow*, not to self-DDoS. |
| B4 | **Failure** | Payment `FAILED`, user retries → a `FAILED` and a `PENDING` row for the same session | `GET …/payment` was undefined for that state — "the payment" was ambiguous, so the UI could show the stale `FAILED` and offer retry against a payment already in flight | §4 #13: **returns the live payment if one exists, otherwise the most recent**; 404 if never paid. |
| B5 | **Failure** | App restart with rows in `PENDING` | Heals *only* if settlement is driven by scanning the table. The equally natural implementation — an in-memory timer scheduled at Pay time — silently drops every in-flight payment on restart, and each dropped one is a blocked vehicle | Stated in §8.2 step 7: **settlement is a DB scan, never an in-memory timer.** Restart loses nothing because the queue *is* the `payments` table. |
