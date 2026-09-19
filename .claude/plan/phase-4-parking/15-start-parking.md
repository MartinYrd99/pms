---
exec-order: 15
category: add
depends-on: [5, 7, 9, 11, 12, 13, 14]
status: done
suggested-agents: [backend-developer]
---

# Start a parking session for an own vehicle in an active zone

## Business description

This is the core action of the product: a driver picks one of their vehicles and an active zone, taps
**Start**, and the system records that the car is parked, from this moment, at this zone's current
price. The response is the new session, and from here the app can show an active-session screen with
a running timer.

The rule the business cares most about is **one unsettled session per vehicle — no parking on debt.**
A car cannot be started again while it is still parked, and it also cannot be started again while its
previous stay has ended but has not been paid for. The rejection is explicit (the second start is
*refused*, not quietly turned into "you're already parked"), and the refusal **tells the client which
session is blocking it**, so a double-tapping user is sent straight to the session they already have
instead of being left confused.

**Scope — `POST /api/v1/parking-sessions` with body `{vehicleId, zoneId}` → 201**

The whole thing runs in **one transaction**:

1. Load the vehicle by `vehicleId`. If it does not exist → **404**. If its `user_id` is not the
   authenticated principal → **403** (design N4 — a user acts only on their own data).
2. Load the zone by `zoneId`. Missing → **404**. Present but `active = false` → **409**.
3. Resolve the zone's **current tariff**: the row with **`valid_to IS NULL`** (backed by
   `UNIQUE (zone_id) WHERE valid_to IS NULL`). Store its id as `tariff_id` on the session — this is
   the tariff that prices this session forever, whatever happens to the zone or the price later
   (design N3, auditability).
4. Insert into `parking_sessions`:
   `user_id` = **the vehicle's owner, denormalized** (set once at insert, never updated) ·
   `vehicle_id` · `zone_id` · `tariff_id` · `started_at = clock.now()` (the injectable `Clock` from
   ticket 14) · `ended_at = NULL` · `amount = NULL` · `paid_at = NULL`.
5. Commit → **201** `{id, vehicle, zone, startedAt}`.

**The partial unique index `UNIQUE (vehicle_id) WHERE paid_at IS NULL` (ticket 5) is what makes the
one-unsettled-session rule true** — not a service-layer check. A vehicle that is currently parked
(`ended_at IS NULL`) *or* has an ended-but-unpaid session (`ended_at NOT NULL AND paid_at IS NULL`)
both have `paid_at IS NULL`, so both are refused by the same index. The service **may** query for the
blocking session first to produce a friendly message, but the insert's unique-constraint violation
must **also** be caught and turned into the same response — never a 500, never a duplicate row.

The refusal is **409 whose body carries the blocking session id** on top of the standard
`{code, message}` contract from ticket 7, so the client can navigate to it.

Request validation: both ids required → **400** on a missing one. `userId` is never read from the
body; there is no such field.

**Out of scope:** ending the session (ticket 17), listing active sessions (ticket 16), payment.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): a user with a vehicle starts in the seeded active Blue
  zone → 201; the stored row has `started_at` set from a fixed clock, `ended_at`/`amount`/`paid_at`
  null, `user_id` equal to the vehicle's owner and `tariff_id` equal to the zone's `valid_to IS NULL`
  tariff.
- Integration test — the refusal matrix: another user's `vehicleId` → **403**; an unknown `zoneId` →
  **404**; the seeded inactive zone → **409**; a second start for a vehicle that already has an
  active session → **409 whose body contains the blocking session id**; a second start for a vehicle
  whose previous session is **ended but unpaid** → **409** with that session's id (the no-parking-on-
  debt case).
- Integration test: a direct duplicate insert bypassing the service pre-check (or a forced
  constraint violation) surfaces as **409**, not 500, and leaves exactly one row.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and authorize, `GET /api/v1/vehicles` and
  `GET /api/v1/zones` to pick ids, then `POST /api/v1/parking-sessions {vehicleId, zoneId}` →
  observe **201** with an id and `startedAt`. Repeat the identical call → observe **409** whose body
  carries the first session's id. Repeat with the inactive zone's id → observe **409**; with a
  vehicle belonging to a second account → observe **403**.
- Stress: two (and then N) simultaneous `POST /api/v1/parking-sessions` for the **same vehicle**.
  Invariant: exactly one **201**, all others **409** (none 500), and
  `SELECT count(*) FROM parking_sessions WHERE vehicle_id = ? AND paid_at IS NULL` is exactly 1.
