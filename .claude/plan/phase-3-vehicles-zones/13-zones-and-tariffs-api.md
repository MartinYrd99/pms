---
exec-order: 13
category: add
depends-on: [4, 6, 7, 9, 11]
status: done
suggested-agents: [backend-developer]
---

# Expose the active parking zones with their current rate

## Business description

Before a driver starts parking they must be able to pick a zone and **see what it costs** — "Blue
Zone, Sofia, 2.00 EUR/h". This ticket delivers that catalogue: the list of parking zones the user is
allowed to park in, each already carrying its current price so the app never has to make a second
call to find the rate.

Two product rules shape it. **Only active zones are offered** — a city can switch a zone off, and
from that moment it disappears from the list and nobody can newly start parking there. And **prices
are versioned history, not an editable field**: a zone's price lives in a separate tariff record with
a validity period, so changing a price means closing the old record and opening a new one, and past
parkings keep the price they were charged at. The zone answers *where*; the tariff answers *what it
costs, during which period*.

**Scope**

- JPA entities + Spring Data repositories for the existing tables (created in ticket 4, seeded in
  ticket 6):
  - `zones`: `id BIGINT PK · name · city · active BOOL · created_at`
  - `tariffs`: `id BIGINT PK · zone_id FK · rule_type VARCHAR · hourly_rate NUMERIC(10,2) ·
    currency CHAR(3) = 'EUR' · valid_from · valid_to NULL`
- `rule_type` (and every other enum in the model) is a **Java enum mapped with
  `@Enumerated(EnumType.STRING)` over a `VARCHAR` column** — never `EnumType.ORDINAL`, never a
  Postgres enum type. `HOURLY` is the only value in v1; the enum exists so a future rule kind
  (daily cap, free first minutes) is an addition rather than a rewrite.
- `GET /api/v1/zones` → 200, a JSON array of **active zones only** (`active = true`), each with its
  **current tariff**: the single tariff row for that zone where **`valid_to IS NULL`**, exposed as
  `hourlyRate`, `currency` and `ruleType`. The seed data from ticket 6 must come back as Blue
  2.00 EUR/h and Green 1.00 EUR/h, and the seeded inactive zone must **not** appear.
- The "current tariff" lookup is `valid_to IS NULL` and nothing else — it is backed by the partial
  index `UNIQUE (zone_id) WHERE valid_to IS NULL`, which guarantees at most one current tariff per
  zone. Do not order by `valid_from` and take the first, and do not compare `valid_from`/`valid_to`
  against the current time.
- The endpoint is **authenticated** like every non-auth endpoint, but it is not user-scoped: every
  logged-in user sees the same catalogue.

**Tariff rows are immutable and nothing in this ticket writes them.** There is no create/update/close
tariff endpoint, no zone administration, and no repository method that mutates either table — zone
and tariff administration is explicitly out of scope for the whole product. Money amounts are
`BigDecimal` mapped to `NUMERIC(10,2)`; never `double` or `float`.

**Out of scope:** starting a parking session (ticket 15), any writes to `zones` or `tariffs`, any
per-city filtering or search.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): `GET /api/v1/zones` as an authenticated user returns
  the seeded Blue zone with `hourlyRate = 2.00`, `currency = "EUR"`, `ruleType = "HOURLY"` and the
  Green zone with `hourlyRate = 1.00`; the seeded inactive zone is absent from the response.
- Repository test: with two tariff rows for one zone — a closed one (`valid_to` set) and a current
  one (`valid_to IS NULL`) — the current-tariff lookup returns the `valid_to IS NULL` row.
- Persistence test: `rule_type` round-trips as the string `HOURLY` in the column (assert the stored
  value, so an accidental `EnumType.ORDINAL` mapping fails the test).
- Web test: `GET /api/v1/zones` without a bearer token returns **401**.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in via `POST /api/v1/auth/login`, authorize with
  the access token, then call `GET /api/v1/zones` → observe a 200 listing Blue (2.00 EUR/h) and
  Green (1.00 EUR/h) and **not** the inactive zone; clear the authorization and repeat → observe 401.
- Stress: none — this ticket is a read-only catalogue with no concurrency invariant.
