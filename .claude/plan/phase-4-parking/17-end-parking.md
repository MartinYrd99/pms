---
exec-order: 17
category: add
depends-on: [5, 7, 9, 11, 14, 15]
status: done
suggested-agents: [backend-developer]
---

# End a parking session and fix its amount

## Business description

The driver comes back to the car and taps **End**. The system stamps the end time, works out what the
stay cost, stores both, and returns the finished session **with the amount** — that number is what
the user is waiting for and what they will be asked to pay next.

Two product guarantees make this ticket more than an update statement.

**A session can only be ended once, and once ended it never changes.** The amount is money: it must
not be recomputed, overwritten, or produced twice with two different values because the user
double-tapped. A second end attempt is **refused**, and the refusal **hands back the already-ended
session** — including its amount — so a client whose first response was lost simply reads the answer
out of the error instead of retrying blindly.

**What happened to the zone since the parking started is irrelevant.** If the city deactivated the
zone at 18:00, or closed the old tariff and opened a cheaper one, every car parked there must still
be able to end, and must be charged at **the price that was in force when it started**. Otherwise a
zone being switched off would trap drivers mid-parking — still accruing, and, under the no-parking-
on-debt rule, unable to park anywhere else.

**Scope — `POST /api/v1/parking-sessions/{id}/end` → 200**

POST, not PATCH: this is a guarded state transition (`active → ended`), not a field the client may
set. Everything happens in **one transaction**:

1. Load the session by `{id}`; unknown → **404**. Assert `session.user_id` is the authenticated
   principal → otherwise **403**.
2. Load the tariff **via `session.tariff_id` only**. End **never** consults `zones.active` and
   **never** consults `tariffs.valid_to` — do not copy the checks from start parking (design N3, A5).
3. `endedAt = clock.now()` (the injectable `Clock` from ticket 14); compute `amount` with the pricing
   component from ticket 14 — `hours = max(1, ceilDiv(millis(endedAt − startedAt), 3_600_000))`,
   `amount = BigDecimal(hours) × tariff.hourlyRate`, scale 2, EUR. Do not re-derive the formula here.
4. **Guarded update:**
   `UPDATE parking_sessions SET ended_at = ?, amount = ? WHERE id = ? AND ended_at IS NULL`.
   - 1 row updated → commit → **200** with the ended session including `endedAt` and `amount`.
   - **0 rows updated ⇒ somebody ended it first ⇒ 409**, and the **409 body carries the already-ended
     session** (with its stored `amount`) alongside the standard `{code, message}` fields from
     ticket 7.

   A read-then-write (`if (ended_at == null) { ... save(); }`) is **not acceptable** — under READ
   COMMITTED two concurrent requests both see null, both compute, both write, and the second
   overwrites the first with a possibly different amount. The `WHERE ended_at IS NULL` clause is the
   guard; the rows-affected count is the decision.

**After a successful end the session is immutable.** `started_at`, `ended_at`, `amount` and
`tariff_id` are never written again; the only write the row will ever receive afterwards is `paid_at`,
stamped later by settlement. The database `CHECK ((ended_at IS NULL) = (amount IS NULL))` and
`CHECK (ended_at >= started_at)` from ticket 5 backstop this.

**Out of scope:** payment (later phase), session detail and history reads (tickets 18, 19).

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, fixed adjustable clock): start a session in the Blue
  zone (2.00 EUR/h), advance the clock by 2 h 05 min, end it → **200** with `amount = 6.00` and
  `endedAt` set; the stored row matches and `paid_at` is still null.
- Integration test: ending an already-ended session returns **409** whose body contains the ended
  session **with its original amount**, and the stored `ended_at`/`amount` are unchanged.
- Integration test — the isolation-from-zone-changes rule: after the session started, deactivate the
  zone **and** close its tariff (`valid_to` set) and insert a new tariff at a different rate; ending
  still returns **200** priced at the **original** tariff's rate.
- Integration test: ending another user's session returns **403**; an unknown id returns **404**.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and authorize, start a session, then call
  `POST /api/v1/parking-sessions/{id}/end` → observe **200** with `endedAt` and a non-null `amount`.
  Call the same endpoint again → observe **409** whose body still shows that ended session and the
  **same** amount. Authorize as a second account and end that session's id → observe **403**.
- Stress: double-tap end — two requests for the same session id ~50 ms apart, then N in parallel.
  Invariant: exactly one **200**, all others **409** (never 500, never two 200s), and the stored
  session has exactly one `ended_at` and one `amount` — every 409 body reports that same amount.
