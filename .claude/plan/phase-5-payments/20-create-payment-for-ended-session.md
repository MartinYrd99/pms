---
exec-order: 20
category: add
depends-on: [5, 7, 11, 17]
status: done
suggested-agents: [backend-developer]
---

# Create the payment for an ended parking session

## Business description

The driver has ended their parking and sees the amount they owe. This ticket delivers the **Pay**
button's endpoint: it records that the user asked to pay, and hands back a payment that is still
`PENDING`. No money moves here — a separate background step (a later ticket) settles the payment and
tells the system it is `COMPLETED`. That asynchronous shape is deliberate: a real payment provider
never answers instantly, so the product submits a payment and then observes its status.

Two product rules make this ticket worth its own slice.

**You can only pay for a session that has ended.** A session still running has no final amount yet,
so paying for it is meaningless and is refused. And you can only pay for **your own** session — the
owner is the logged-in person, never something the client sends.

**Tapping Pay twice must never charge twice.** A phone double-tap puts two identical requests in
front of the same session milliseconds apart. Whatever happens, the user ends up with exactly **one**
payment. The second call is not an error — it simply returns the payment that already exists, so a
retrying client always converges on the same payment instead of creating a parallel one.

**Scope**

- JPA entity + Spring Data repository for the existing `payments` table (created in ticket 5):
  `id BIGINT PK · session_id FK · amount NUMERIC(10,2) · status VARCHAR (PENDING|COMPLETED|FAILED) ·
  attempts INT DEFAULT 0 · created_at · settled_at NULL`. `status` is a Java enum mapped with
  `@Enumerated(EnumType.STRING)` — not a Postgres enum type.
- `POST /api/v1/parking-sessions/{id}/payment`, one transaction:
  1. Load the session; **403** if `session.user_id` is not the principal; **404** if no such session.
  2. **409** (global `{code, message}` contract from ticket 7) if `ended_at IS NULL` — the session is
     still active.
  3. `INSERT INTO payments (session_id, amount, status, created_at)
     VALUES (:sessionId, :session.amount, 'PENDING', now())` — the amount is **copied from the
     session**, never recomputed, so the payment carries the charge as it stood (design N3,
     auditability).
  4. **201** with `{id, sessionId, amount, status: PENDING, createdAt}`.
- **Idempotency:** if a live payment (`PENDING` or `COMPLETED`) already exists for that session, the
  call returns **that existing payment with 200** — same id, same amount — and inserts nothing.

**The database is what makes "one payment per session" true, not the service.** The table already
carries the partial unique index `UNIQUE (session_id) WHERE status IN ('PENDING','COMPLETED')`. A
friendly pre-check ("does a live payment already exist?") produces the fast 200 path, but the
unique-constraint violation coming back from a racing insert **must also be caught** and resolved by
re-reading the winning row and returning it as 200. Two simultaneous Pay taps must end with exactly
one row and two identical successful responses — never two rows, never a 500.

**A previously FAILED payment does not block a retry.** The index only counts *live* payments, so a
session whose earlier payment ended `FAILED` accepts a fresh `PENDING` insert; the failed rows stay
in the table as an audit trail and are never updated or deleted.

**Out of scope:** reading the payment status (ticket 21), the background settlement that moves the
payment to `COMPLETED` and stamps `parking_sessions.paid_at` (ticket 22), and any expiry of stale
`PENDING` rows (ticket 23). Nothing in this ticket talks to a payment provider.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, base class from ticket 2): pay an ended session → 201
  with `status = PENDING` and `amount` equal to the session's stored amount; calling the same
  endpoint again returns **200** with the **same payment id** and `SELECT count(*) FROM payments
  WHERE session_id = ?` is still 1.
- Integration test: paying a session that is still active (`ended_at IS NULL`) returns **409** in the
  `{code, message}` contract and writes no payment row; paying another user's session returns
  **403**; an unknown session id returns **404**.
- Integration test: a session whose only payment row is `FAILED` accepts a new payment → 201 with a
  new `PENDING` row, and the `FAILED` row is still present untouched.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in through `POST /api/v1/auth/login`, paste the
  access token into **Authorize**. Start a session, end it (note the `amount`), then call
  `POST /api/v1/parking-sessions/{id}/payment` → observe **201** with `status: PENDING` and the same
  amount the end call returned. Execute the identical request again → observe **200** with the
  **same payment id**. Then call it for a session that is still active → observe **409** with a
  `{code, message}` body.
- Stress: fire N concurrent `POST /api/v1/parking-sessions/{id}/payment` requests for the **same
  ended session** with the same bearer token. Invariants: `SELECT count(*) FROM payments WHERE
  session_id = ?` is exactly **1**; every caller receives a 2xx (one 201, the rest 200) carrying the
  **same payment id**; no response is a 500.
