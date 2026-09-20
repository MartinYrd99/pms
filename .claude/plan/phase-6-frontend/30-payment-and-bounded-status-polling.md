---
exec-order: 30
category: add
depends-on: [29]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the Pay action and the bounded payment-status poller

## Business description

Paying is the last step of a parking. A real payment provider never answers instantly, so this
system mirrors that: the app submits the payment, the payment starts as **PENDING**, a background
step settles it a moment later, and the app watches the status until it turns **COMPLETED**. This
ticket delivers that watching — and, just as importantly, delivers it **bounded**.

**Pay** appears on the session screen at `/sessions/{id}` for a session that has ended and is not
yet paid, next to its amount. It posts `POST /api/v1/parking-sessions/{id}/payment` → **201**
`{status: "PENDING"}`. The endpoint is **idempotent**: a repeat call while a payment is in flight
returns the existing payment with **200** instead of creating a second one, so a double tap is
harmless and the app treats 201 and 200 identically. A **409** means the session is still active.

**Watching** then reads `GET /api/v1/parking-sessions/{id}/payment` on a TanStack Query
`refetchInterval` with a **fixed backoff: 2 s for the first three polls, then 5 s for the next
three, then 10 s** — and **stops completely once ~2 minutes have passed since polling began**, at
which point the screen says **"still processing — check history"** and offers a manual "check
again" (a re-read, never a re-submit). **The poller may never loop indefinitely**: a stalled
settlement job must degrade the app to *slow*, not turn thousands of phones into a self-inflicted
flood.

**The three outcomes.** `COMPLETED` → polling stops and the screen says "Paid" (refresh the active
and history queries so the rest of the app agrees). `FAILED` → the screen says the payment failed
and offers **Retry**, which posts the payment endpoint again — a failed payment does not block a new
attempt. **404** (never paid) → the Pay action is shown.

**Out of scope:** any client-side amount calculation (the amount comes from the ended session),
receipts, and payment methods — there is no real provider.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API, fake timers):

- Tapping Pay posts the payment once, shows the pending state, and a subsequent mocked `COMPLETED`
  response shows "Paid" and makes no further status requests.
- With the status stuck on `PENDING`, the observed request gaps follow 2 s → 5 s → 10 s and polling
  **stops** once ~2 minutes have elapsed, showing "still processing — check history"; advancing the
  clock further produces no more requests.
- A mocked `FAILED` status offers Retry, and tapping it posts the payment endpoint again and returns
  the screen to the pending state.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running stack — end a parking, tap Pay → "pending", and within a
  few seconds the status flips to "Paid" on its own; the network panel shows the polling requests
  ceasing after `COMPLETED`.
- Stress: with settlement prevented from completing (payments left `PENDING`), leave the payment
  screen open for more than two minutes and watch the network panel. Invariant: the requests follow
  the 2 / 5 / 10 s spacing, the total stops at ~2 minutes with the "still processing — check
  history" message, and **not one further request is issued** while the tab stays open. Separately,
  double-tap Pay → the network shows two POSTs but the server holds exactly one payment for the
  session.
