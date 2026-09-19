---
exec-order: 18
category: add
depends-on: [5, 7, 9, 11, 15, 17]
status: done
suggested-agents: [backend-developer]
---

# Show the detail of a single parking session

## Business description

Whenever the app sends the user to one specific parking — from the active screen, from history, or
from a rejected start that pointed at a blocking session — it needs one endpoint that answers
"everything about this parking": which car, which zone, when it started, when it ended, what it cost,
and whether it has been paid.

This is the **only** place a user can address a session by id, so it is also where the ownership rule
is most visible: **a session id belonging to someone else is refused**, and an id that does not exist
is simply unknown. Ids are sequential numbers, which is safe precisely because every read is scoped
to the owner.

**Scope — `GET /api/v1/parking-sessions/{id}` → 200**

- Returns: session `id`, the vehicle (`id`, `plate`, brand, model), the zone (`id`, `name`, `city`),
  `startedAt`, `endedAt` (null while active), `amount` (null while active), and **`paymentStatus`**.
- Ownership: the session's `user_id` must equal the authenticated principal.
  - Not the principal's session → **403**.
  - No such session id → **404**.
  - No user id ever appears in the path or body (design N4).
- Times are returned as **instants** (UTC, ISO-8601); the client renders them in Europe/Sofia. The
  server does no timezone conversion.
- `amount` is a `BigDecimal` with scale 2 in EUR, read from the stored column — **never recomputed**
  on read (the amount was fixed once, at end).

**`paymentStatus` is modelled now and filled later.** The payments feature does not exist yet at this
point in the plan, so the field must be part of the response contract from day one and simply be
`null` (meaning "never paid / no payment yet"). Its type is the payment status enum (`PENDING`,
`COMPLETED`, `FAILED`) so that when the payments phase lands it populates the same field with no
change to this endpoint's shape and no change to any client. Do not omit the field, do not invent a
placeholder string, and do not model it as a boolean.

**Out of scope:** creating or reading payments, the paginated history list (ticket 19), any write.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): fetching an **active** session returns 200 with
  `endedAt`, `amount` and `paymentStatus` all null and the vehicle/zone/`startedAt` populated;
  fetching an **ended** session returns its `endedAt` and stored `amount` (scale 2), with
  `paymentStatus` still null.
- Integration test: another user's session id returns **403** with the `{code, message}` body and
  leaks nothing about the session; an id that does not exist returns **404**.
- Web test: the call without a bearer token returns **401**.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and authorize, start a session and call
  `GET /api/v1/parking-sessions/{id}` → observe 200 with null `endedAt`/`amount`/`paymentStatus`;
  end it and call again → observe the end time and the amount, `paymentStatus` still null. Authorize
  as a second account and request the same id → observe **403**; request id `99999999` → observe
  **404**.
- Stress: none — this ticket is a read carrying no concurrency invariant.
