---
exec-order: 21
category: add
depends-on: [18, 19, 20]
status: done
suggested-agents: [backend-developer]
---

# Expose payment status on its own endpoint and in session payloads

## Business description

After tapping **Pay**, the driver watches a status: "pending…" turning into "paid". This ticket
delivers what they watch. It adds a read endpoint for a session's payment and makes the payment
status visible in the two places the user already looks — the session detail screen (ticket 18) and
the history list (ticket 19) — so "payment status" is never something the UI has to guess or
assemble from a second call per row.

One product rule shapes the endpoint. A payment can fail, and the user must then be able to retry —
which means a session can end up with an old `FAILED` payment **and** a new live one. The screen must
never show the stale failure while a fresh payment is in flight. So the rule is: **show the live
payment (`PENDING` or `COMPLETED`) if there is one; otherwise show the most recent payment**, which
is how a `FAILED` becomes visible and the UI can offer "retry". A session nobody ever tried to pay
has no payment at all, and that is a **404** — not an invented empty payment.

**Scope**

- `GET /api/v1/parking-sessions/{id}/payment` → **200**
  `{id, sessionId, amount, status, createdAt, settledAt}`.
  - Selection rule: the row with `status IN ('PENDING','COMPLETED')` for that session if one exists
    (the partial unique index guarantees at most one); otherwise the row with the greatest
    `created_at` for that session.
  - **403** if the session does not belong to the principal, **404** if the session does not exist
    **or** has no payment rows at all. Ownership comes from the authenticated principal, never from a
    request field.
- Payment status added to the existing payloads, using the **same selection rule**:
  - `GET /api/v1/parking-sessions/{id}` (ticket 18) gains `paymentStatus` — `PENDING`, `COMPLETED`,
    `FAILED`, or `null` when the session was never paid.
  - `GET /api/v1/parking-sessions?page=&size=` (ticket 19) gains `paymentStatus` on every row, with
    the same `null` for never-paid.
- The history list must **not** issue one payment query per row: load the payments for the page's
  session ids in a single query (`WHERE session_id IN (:ids)`) and map them in memory, or use a join
  fetch — no N+1.

**Out of scope:** creating a payment (ticket 20), settling it (ticket 22), and expiring stale
`PENDING` rows (ticket 23). This ticket writes nothing — it is read-only.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, base class from ticket 2): a session with one `PENDING`
  payment returns **200** with that payment; after the row is set to `COMPLETED` the same call
  returns `COMPLETED` with a non-null `settledAt`; a session with no payment rows returns **404** and
  another user's session returns **403**.
- Integration test: a session carrying a `FAILED` row **and** a newer `PENDING` row returns the
  **`PENDING`** one; a session whose only rows are `FAILED` returns the **most recent** `FAILED` one.
- Integration test: `GET /api/v1/parking-sessions/{id}` and a page of
  `GET /api/v1/parking-sessions` expose `paymentStatus` matching the same rule, with `null` for a
  session that was never paid.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and **Authorize**. Call
  `GET /api/v1/parking-sessions/{id}/payment` for an ended-but-never-paid session → observe **404**
  with `{code, message}`. Create the payment via `POST /api/v1/parking-sessions/{id}/payment`, then
  re-run the GET → observe **200** with `status: PENDING`. Then run
  `GET /api/v1/parking-sessions/{id}` and `GET /api/v1/parking-sessions?page=0&size=20` → observe the
  same `paymentStatus` in both payloads, and `null` on a session that was never paid.
