---
exec-order: 29
category: add
depends-on: [28]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the active-session view with a local elapsed timer and the End-parking action

## Business description

While the car is parked the driver wants one thing on screen: what is parked where, since when, and
how long it has been running — then a way to stop it and see the bill.

**The active view** lives at `/active` (the app's home destination after signing in) and reads
`GET /api/v1/parking-sessions/active`, which returns **0..n** sessions — a user with several
vehicles can have several parkings running at once, so the screen is a list, not a single card, and
it renders the empty case ("nothing parked right now") with a link to `/park` as a first-class
state alongside loading and error.

Each card shows the vehicle, the zone, the start time and a **running elapsed time**. The elapsed
time is computed **locally in the browser** from the session's `startedAt` and ticks every second;
it is never polled from the server — the server is asked for the session list, not for a clock. All
timestamps the user sees are rendered in **Europe/Sofia** local time (one shared formatter built on
`Intl.DateTimeFormat` with `timeZone: "Europe/Sofia"`, reused by later screens), while the values on
the wire stay UTC instants.

**End parking** posts `POST /api/v1/parking-sessions/{id}/end` → **200** with the ended session
**including its `amount`** — the price is computed by the server from the zone's tariff (every
started hour is a full hour), so the app only displays it. On success the ended session is shown
with its end time and amount.

**Ending twice must not cost the user their bill.** If the session was already ended — a double tap,
a retry after a lost response — the server answers **409 and the body is the ended session, amount
included**. The app renders that body exactly as it renders a success, with a quiet note that the
parking had already been ended. There is nothing to redo; the app only ever re-reads.

Paying the shown amount is **ticket 30** — this ticket stops at displaying the ended session and its
amount.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API, fake timers):

- A mocked response with two active sessions renders both with vehicle, zone and a start time
  formatted in Europe/Sofia; advancing the fake clock by one minute advances the displayed elapsed
  time.
- A mocked empty active list renders the empty state with the link to start a parking.
- Tapping End with a mocked 200 renders the end time and the returned amount.
- Tapping End with a mocked **409 whose body is the ended session** renders that same ended session
  and its amount, not a generic failure.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running stack — start a parking, watch the elapsed timer tick and
  the start time show Sofia local time; start a second parking with a second vehicle and see both
  cards; tap End on one → the amount appears (a short parking bills one full hour) and that card
  leaves the active list on reload while the other stays.
- Stress: double-tap End (two clicks ~50 ms apart, or the same session ended from two tabs).
  Invariant: exactly **one** amount exists for the session, both taps end on the same ended session
  showing that identical amount, and the session's end time and amount do not change between the two
  responses.
