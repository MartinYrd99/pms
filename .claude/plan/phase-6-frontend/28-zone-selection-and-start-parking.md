---
exec-order: 28
category: add
depends-on: [27]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the start-parking screen with zone rates and the 409 "go to the blocking session" rule

## Business description

This is the core action of the product: the driver picks their car, picks a parking zone, **sees
what it will cost per hour before committing**, and taps Start.

**The screen** lives at `/park`. It reads `GET /api/v1/vehicles` for the vehicle choice and
`GET /api/v1/zones` for the zone choice. The zones endpoint returns **active zones only**, each
already carrying its current tariff — `{id, name, city, hourlyRate, currency, ruleType}` — so each
option reads like "Blue Zone, Sofia — 2.00 EUR/h" and the rate is visible **before** Start is
tapped, never after. Loading, error and empty states are all rendered (no vehicles → point the user
at the vehicles screen).

**Start** submits `POST /api/v1/parking-sessions {vehicleId, zoneId}` → **201** with the created
session, and the user is taken to that session at `/sessions/{id}`. This ticket also introduces that
route as a **minimal session detail screen** reading `GET /api/v1/parking-sessions/{id}` and showing
the vehicle, zone, start time, end time, amount and payment status as returned; ticket 29 turns the
active-session experience (elapsed timer, End) into its own screen and ticket 30 adds paying.

**The 409 is the point of this ticket.** A vehicle may have only one unsettled session: starting is
refused while that vehicle has an active session **or an ended session that has not been paid** (no
parking on debt). The refusal is a **409 whose body carries the blocking session**, and the app must
use it — **navigate straight to `/sessions/{blockingId}`** with a short line explaining why ("this
vehicle already has an unsettled parking"). The user must never land on a dead-end error page for a
state the server just handed them. The other refusals are messages on the screen: **403** the
vehicle is not yours, **404/409** the zone is missing or no longer active (re-read the zone list).

The Start button is disabled while the request is in flight, but that is courtesy only — the server
is what guarantees one session. A second tap that still gets through ends on the same session via
the 409 rule above.

**Out of scope:** the elapsed timer and ending a parking (ticket 29), paying (ticket 30), history
(ticket 31).

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API):

- The zone options render name, city and the rate from the mocked `GET /zones` response (e.g. "Blue
  Zone, Sofia — 2.00 EUR/h") and the vehicle options come from the mocked `GET /vehicles`.
- A successful Start posts the selected `{vehicleId, zoneId}` and the user ends up on
  `/sessions/{id}` of the returned session.
- A mocked 409 carrying a blocking session id navigates to `/sessions/{blockingId}` and shows the
  reason — not a generic error screen.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running stack — sign in, open `/park`, confirm the Blue and Green
  rates are shown and the inactive zone is absent, pick a vehicle and a zone, tap Start → land on
  the session with its start time; open `/park` again in a second tab, pick the same vehicle, tap
  Start → land on the same session id with the "already has an unsettled parking" line.
- Stress: double-tap Start (two clicks ~50 ms apart, or two tabs firing simultaneously) for the same
  vehicle and zone. Invariant: **exactly one** session exists server-side — `GET
  /parking-sessions/active` returns one session for that vehicle and the history count grows by one
  — and the losing tap lands the user on that very same session id, never a 500 and never a
  dead-end error.
