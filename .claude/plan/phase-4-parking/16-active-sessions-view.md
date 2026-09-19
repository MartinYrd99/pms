---
exec-order: 16
category: add
depends-on: [5, 9, 11, 15]
status: done
suggested-agents: [backend-developer]
---

# List the user's currently active parking sessions

## Business description

Once a driver has started parking, the app's home screen has to answer one question every time it is
opened: **"what is currently parked, where, and since when?"** This ticket delivers that read. It
returns the logged-in user's active sessions — the cars that are parked right now — each with the
vehicle, the zone and the start time.

A user may have **several cars parked at once** (the product allows one active session per *vehicle*,
not per user), so the answer is a list of **0..n** sessions — an empty list when nothing is parked,
not a 404.

The response carries **`startedAt` as an instant, and no elapsed time**: the client computes and
renders the running timer itself, so the number on screen ticks without polling the server and the
server never has to be the source of "how long has it been".

**Scope — `GET /api/v1/parking-sessions/active` → 200**

- Returns a JSON array of the **principal's** sessions where **`ended_at IS NULL`** (this is the
  definition of "active" in the data model — there is no status column).
- Each element carries: session `id`, the vehicle (`id`, `plate`, and brand/model as the list DTO
  needs), the zone (`id`, `name`, `city`) and `startedAt`.
- The query filters on `user_id` (the denormalized column set at start) so it is served directly by
  the partial index **`(user_id) WHERE ended_at IS NULL`** — do not join through `vehicles` to derive
  ownership, and do not scan and filter in Java.
- Ownership comes from the authenticated principal only; there is no user id in the path or query
  (design N4). A user can never see another user's active sessions.
- Ended sessions — paid or unpaid — are **not** in this list; they belong to history (ticket 19).

**Out of scope:** elapsed-time calculation on the server, ending a session (ticket 17), any writes.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): a user with **two** vehicles starts both → the endpoint
  returns **two** entries, each with the right vehicle, zone and `startedAt`; a second user calling
  the same endpoint gets `[]`.
- Integration test: after a session is ended (`ended_at` set directly in the fixture) it disappears
  from the active list, while a still-active session of the same user remains.
- Web test: the call without a bearer token returns **401**.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and authorize, start a session via
  `POST /api/v1/parking-sessions`, then call `GET /api/v1/parking-sessions/active` → observe a
  one-element array with the vehicle, the zone and a `startedAt` matching what start returned. Start
  a second vehicle → observe two elements. Authorize as a second account → observe `[]`.
- Stress: none — this ticket is a read carrying no concurrency invariant.
