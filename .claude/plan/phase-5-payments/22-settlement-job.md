---
exec-order: 22
category: add
depends-on: [20]
status: done
suggested-agents: [backend-developer]
---

# Settle pending payments with a scheduled claim-then-charge job

## Business description

A payment created by the **Pay** button sits at `PENDING` until something settles it. This ticket
delivers that something: a background job that picks up pending payments a couple of seconds after
they were created, asks the payment provider to charge them, and — on success — marks the payment
`COMPLETED` **and** stamps the parking session as paid in the same breath. That stamp is what frees
the vehicle: under the product's "no parking on debt" rule a vehicle with an ended-but-unpaid session
cannot start a new one, so settlement is literally what lets the driver park again.

There is **no real payment provider** in this product — it is explicitly out of scope. The job calls a
small provider interface whose only implementation is a **simulator** that succeeds, plus a
configuration property that can make it fail so the failure path is demonstrable in either run mode.
Swapping in a real provider later must change only that implementation, never the job.

Two things about this job are non-negotiable, and they are the reason it is its own ticket.

**The queue is the `payments` table, not memory.** The job scans the database on a timer; it is never
a timer scheduled per payment at Pay time. If the application restarts with rows in `PENDING`, the
next scan picks them up and nothing is lost — every dropped payment would otherwise be a vehicle
blocked forever.

**The row is claimed before anything external is called.** The provider call sits between reading a
payment and writing its result; if two runners both read the same `PENDING` row, both would call the
provider and the user would be charged twice. So the job locks the row first, and a second runner
skips it entirely.

**Scope**

- A `@Scheduled` task firing roughly **every 2 s** (fixed delay, configurable). Each run handles
  payments **one per transaction** — a poison payment can never roll back another's settlement.
- Claim query, inside the transaction, before any provider call:
  `SELECT * FROM payments WHERE status = 'PENDING' AND created_at < now() - interval '3 seconds'
   ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED`
  (served by the existing index `(created_at) WHERE status = 'PENDING'`). `SKIP LOCKED` is what makes
  a concurrent runner move on instead of waiting.
- A `PaymentProvider` interface with a single `charge(payment)` operation; the only implementation is
  a simulator that returns success. A **configuration property** (e.g. `pms.payment.simulator.fail`,
  defaulting to off and overridable by environment variable — there are no Spring profiles in this
  project, see ticket 1) forces failure so the retry/`FAILED` path can be exercised in any run mode.
- **On provider success**, in that same transaction, both writes or neither:
  - `UPDATE payments SET status = 'COMPLETED', settled_at = now(), attempts = attempts + 1
     WHERE id = :paymentId`
  - `UPDATE parking_sessions SET paid_at = now() WHERE id = :sessionId AND paid_at IS NULL`
    (guarded — 0 rows means it was already paid, which must not blow up the transaction).
- **On provider failure:** `attempts = attempts + 1`, and `status = 'FAILED'` once `attempts >= 3`;
  the payment otherwise stays `PENDING` and is picked up by a later run. A `FAILED` payment leaves
  the session unpaid; the user retries through `POST /api/v1/parking-sessions/{id}/payment`, which
  inserts a fresh `PENDING` row (the partial unique index only counts live payments).

**Out of scope:** the liveness health indicator and the 15-minute `PENDING` expiry (ticket 23), any
real provider integration, and any change to the payment API surface.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, base class from ticket 2): a `PENDING` payment older
  than the 3 s delay, with the simulator succeeding → after one job run the payment is `COMPLETED`
  with a non-null `settled_at` and `attempts = 1`, **and** its session's `paid_at` is non-null; a
  payment created just now (younger than 3 s) is left untouched.
- Integration test with the provider stubbed to fail: `attempts` increments on each run and the
  payment stays `PENDING` at 1 and 2, reaching `status = 'FAILED'` on the run where `attempts` hits
  **3**; the session's `paid_at` stays `NULL` throughout.
- Integration test: when the provider throws, the transaction leaves **no** half-applied pair —
  the payment is not `COMPLETED` and `parking_sessions.paid_at` is still `NULL`.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and **Authorize**. End a session, call
  `POST /api/v1/parking-sessions/{id}/payment` → observe `status: PENDING`. Re-execute
  `GET /api/v1/parking-sessions/{id}/payment` every few seconds → observe the status flip to
  `COMPLETED` with a `settledAt` within ~10 s. Then call `GET /api/v1/parking-sessions/{id}` → the
  session shows as paid, and starting a new session for the **same vehicle** through
  `POST /api/v1/parking-sessions` now succeeds with **201** where it previously returned 409.
- Stress: seed many `PENDING` payments (each on its own ended session) and run **two concurrent
  settlement runners** against the same database. Invariants: every payment ends `COMPLETED`
  **exactly once** with `attempts = 1` (no double provider call — assert via the simulator's
  per-payment call count), every corresponding session has exactly one `paid_at` stamp, and there is
  no row where the payment is `COMPLETED` while `paid_at IS NULL` or vice versa.
