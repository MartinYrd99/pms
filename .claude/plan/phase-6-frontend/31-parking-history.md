---
exec-order: 31
category: add
depends-on: [29]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the paginated parking history screen

## Business description

The last screen of the flow answers "what have I parked, and what did it cost me?". It lists **the
signed-in user's own parkings, newest first**, and nobody else's — the backend scopes the list to
the principal and no user id is ever sent.

**The screen** lives at `/history`, reachable from the home screen, and reads
`GET /api/v1/parking-sessions?page=<n>&size=20`. Each row shows the **vehicle**, the **zone**, the
**start and end times**, the **amount** and the **payment status**. All times are rendered in
**Europe/Sofia** using the shared formatter introduced with the active-session screen; amounts are
shown exactly as the server returned them (two decimals, EUR) and are never recomputed in the
browser — the amount was frozen when the parking ended.

**Pagination is offset-based**, straight over the endpoint's `page` and `size` parameters: Previous
and Next buttons, Previous disabled on the first page and Next disabled on the last, with the
current page kept in the URL query string so a reload or the browser Back button keeps the user's
place. Mirror the backend's paging envelope exactly as it comes off the wire (check
`/swagger-ui.html`) rather than assuming one. The order comes from the server — **never re-sort the
rows client-side**.

The screen renders its three states: loading, a readable error, and **empty** — a user with no
finished parkings sees "no parkings yet", not a blank table.

**Out of scope:** filtering, searching, date ranges, CSV export, and any action on a history row
beyond opening the session it points to.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API):

- A mocked first page renders each row's vehicle, zone, amount and payment status, and a known UTC
  timestamp is displayed as its Europe/Sofia wall-clock time.
- Tapping Next requests `page=1` and renders that page; Previous is disabled on page 0.
- A mocked empty first page renders the empty state, and a mocked error response renders a readable
  error rather than a blank screen.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running stack — complete a parking end to end (start, end, pay),
  open History → the parking is the **top** row with its amount and its payment status, times in
  Sofia local time; with more parkings than a page, Next and Previous move through them and a reload
  keeps the current page.
- Stress: none — this ticket is a read-only list with no concurrency invariant.
