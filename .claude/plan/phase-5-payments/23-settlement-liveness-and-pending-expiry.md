---
exec-order: 23
category: add
depends-on: [22]
status: done
suggested-agents: [backend-developer]
---

# Give settlement a liveness signal and expire stale PENDING payments

## Business description

The settlement job is the only moving part that can quietly die — a disabled scheduler, an exhausted
connection pool, a misconfiguration. If it does, every payment stays `PENDING` forever, and because a
vehicle with an unpaid session cannot start a new one, **every paying driver is blocked forever while
the UI cheerfully says "pending…"**. Nothing in the product notices. This ticket adds the two exits
that make that impossible.

**An operator can see the job is alive.** The job publishes the timestamp of its last successful run,
surfaced through the application's health endpoint, so "is settlement running?" is a question with an
answer instead of a guess.

**A payment cannot stay pending forever.** Any payment that has been `PENDING` for more than
**15 minutes** is moved to `FAILED`, regardless of how many attempts it has made. The worst thing a
user can then experience is "payment failed — retry", which is a state they can act on, rather than a
spinner that never resolves.

Be plain about the consequence: **a `FAILED` payment leaves the session unpaid and the vehicle still
blocked** until a retry succeeds. That is intended — the product never marks a session paid without a
completed payment. What the expiry buys is a *visible, actionable* failure instead of an invisible,
indefinite block.

**Scope**

- **(a) Liveness.** The settlement job records `last_successful_run` (an instant, updated whenever a
  run completes without throwing — including a run that found nothing to settle). A Spring Boot
  `HealthIndicator` exposes it through **`/actuator/health`**, reporting the timestamp as a detail and
  going **DOWN** when the last successful run is older than a configurable staleness threshold
  (default: 60 s, i.e. comfortably more than the ~2 s schedule). Expose the health endpoint over HTTP
  and make it publicly readable so a container health check can hit it.
- **(b) Expiry.** A scheduled sweep (it may run inside the settlement job's schedule) executes the
  guarded update
  `UPDATE payments SET status = 'FAILED' WHERE status = 'PENDING' AND created_at < now() - interval '15 minutes'`
  and logs how many rows it expired. It never touches `parking_sessions.paid_at` and never deletes
  rows — expired payments remain as audit trail, and the user can create a fresh `PENDING` payment
  through `POST /api/v1/parking-sessions/{id}/payment` because the partial unique index
  `UNIQUE (session_id) WHERE status IN ('PENDING','COMPLETED')` only counts live payments.
- Both the 15-minute age and the health staleness threshold are configuration properties with those
  defaults, so a test can shrink them.

**Out of scope:** the settlement job itself and the attempts/`FAILED`-after-3 rule (ticket 22),
alerting or metrics beyond the health indicator, and any UI change.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, base class from ticket 2): a payment whose `created_at`
  is older than 15 minutes and is still `PENDING` becomes `FAILED` after the sweep, while its
  session's `paid_at` stays `NULL`; a payment 1 minute old is left `PENDING`.
- Integration test: an already `COMPLETED` payment older than 15 minutes is **not** touched by the
  sweep (status and `settled_at` unchanged).
- Test of the health indicator: with a recent `last_successful_run` the indicator reports **UP** and
  carries the timestamp as a detail; with a `last_successful_run` older than the staleness threshold
  it reports **DOWN**.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/actuator/health` → observe the settlement entry with a
  `lastSuccessfulRun` timestamp that advances on reload while the app runs. Then open
  `/swagger-ui.html`, log in and **Authorize**, create a payment with the settlement job disabled
  (its scheduling property turned off) and, with the expiry age configured down, re-run
  `GET /api/v1/parking-sessions/{id}/payment` → observe the status reach **FAILED**; call
  `POST /api/v1/parking-sessions/{id}/payment` again → observe a **201** with a new `PENDING` payment
  id, proving the failed one does not block a retry.
