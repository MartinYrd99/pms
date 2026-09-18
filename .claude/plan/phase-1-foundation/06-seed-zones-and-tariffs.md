---
exec-order: 6
category: add
depends-on: [5]
status: done
suggested-agents: [backend-developer]
---

# Seed the parking zones and their current tariffs

## Business description

Parking zones are pre-seeded data, not something a user creates — there is no zone or tariff
administration UI in this product. For the application to be demonstrable at all, the database must
arrive with a realistic set of zones spread over more than one city, each with a current price, and
at least one zone switched off so that the "only active zones are offered" rule is actually
observable rather than merely claimed.

This ticket adds that seed as a Flyway **repeatable** migration that runs in **every run mode** — a
local IDE run and `docker compose up` alike. Zones are not demo data: they are reference data the
product has no UI to create, so a database without them has no zone to select, no session that can be
started, and no working application. Gating the seed behind a profile would leave one of the two run
modes broken.

**What to seed:**

- Zones in **at least two different cities**, including:
  - **Blue Zone** — current tariff `rule_type = 'HOURLY'`, `hourly_rate = 2.00`, `currency = 'EUR'`
  - **Green Zone** — current tariff `rule_type = 'HOURLY'`, `hourly_rate = 1.00`, `currency = 'EUR'`
  - **at least one zone with `active = false`** (it still gets a tariff row; it is simply not offered)
- Every seeded zone gets exactly **one current tariff**: `valid_from` set to a fixed past timestamp
  and `valid_to = NULL`. The partial unique index `UNIQUE (zone_id) WHERE valid_to IS NULL` from
  ticket 4 means a second current tariff per zone is impossible — the seed must respect that.
- The migration must be **idempotent / re-runnable**: it is a repeatable migration, so re-applying it
  must not create duplicate zones or duplicate current tariffs (match on a natural key such as
  `(name, city)` and insert only when missing).
- No users, no vehicles, no sessions, no payments are seeded — this ticket is zones and tariffs only.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test against Testcontainers Postgres: after startup the seeded
  zones exist, Blue Zone's current tariff is `2.00 EUR` and Green Zone's is `1.00 EUR`, and at least
  one zone has `active = false`.
- Test that every seeded zone has exactly one tariff row with `valid_to IS NULL`.
- Test that applying the repeatable migration a second time leaves the zone and tariff row counts
  unchanged.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — this ticket carries no concurrency invariant.
