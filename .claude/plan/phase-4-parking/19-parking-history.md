---
exec-order: 19
category: add
depends-on: [5, 9, 11, 15, 17, 18]
status: done
suggested-agents: [backend-developer]
---

# Serve the user's parking history, newest first, paginated

## Business description

The last step of the driver's journey is looking back: **"where have I parked, for how long, what did
it cost, and did it go through?"** This ticket delivers that screen's data — the logged-in user's own
parking sessions, most recent first, one page at a time.

Pagination is not a nicety here, it is the design. The sessions table is **the only table in the
system that grows** — roughly **11 million rows a year** — so an unpaginated "give me my history"
query is a defect from day one even though any single user's own history is only hundreds of rows.
The list must be served straight off the index, never by fetching everything and sorting in memory.

**Scope — `GET /api/v1/parking-sessions?page=&size=` → 200**

- Returns the **authenticated principal's own** sessions (filtered on the denormalized `user_id`
  column), ordered **`started_at DESC`** — newest first.
- **Offset pagination** via `page` (0-based) and `size`, with sensible defaults (e.g. `page=0`,
  `size=20`) and a capped maximum `size` so a client cannot ask for the whole table. The response is
  a page envelope: the content array plus at least `page`, `size` and `totalElements`.
- Each row carries: session `id`, vehicle (`id`, `plate`), zone (`id`, `name`, `city`), `startedAt`,
  `endedAt`, `amount` and `paymentStatus` — the same field shapes as the detail endpoint from
  ticket 18, including `paymentStatus` being `null` until the payments phase fills it.
- **The query must be index-backed** by `(user_id, started_at DESC)` — filter on `user_id` and order
  by `started_at DESC` in the query itself. No join through `vehicles` to establish ownership, no
  sorting or slicing in Java, no `findAll()` followed by a filter.
- **Times are returned as instants** (UTC, ISO-8601). The client renders them in Europe/Sofia; the
  server never formats a local time.
- `amount` is read from the stored column, never recomputed.
- A user with no sessions gets an empty page (200), not a 404. A user can never see another user's
  rows — there is no user id in the query string (design N4).

**Out of scope:** filtering by date/zone/status, sorting options, cursor pagination, any write.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): a user with several sessions at distinct start times
  gets them ordered **newest first**; `page=0&size=2` returns the two newest and `page=1&size=2` the
  next two, with `totalElements` matching the user's row count and no overlap between pages.
- Integration test: a second user's sessions never appear in the first user's page, and a user with
  no sessions gets an empty content array with 200.
- Integration test: an ended-and-priced session appears with its stored `amount` (scale 2), its
  `endedAt`, and `paymentStatus` null; an active session appears with `endedAt`/`amount` null.
- Repository/plan test: a `size` above the cap is clamped rather than honoured.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, log in and authorize, create and end two or three
  sessions, then call `GET /api/v1/parking-sessions?page=0&size=2` → observe the two newest sessions
  with amounts and `totalElements`; call `page=1&size=2` → observe the older ones with no repeats.
  Authorize as a second account → observe an empty page.
- Stress: none — this ticket is a read carrying no concurrency invariant. (Its performance concern,
  the growing table, is covered by the index-backed query asserted above, not by a load run.)
