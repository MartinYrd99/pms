---
exec-order: 12
category: add
depends-on: [4, 7, 9, 11]
status: done
suggested-agents: [backend-developer]
---

# Let a user register and list their own vehicles

## Business description

A driver cannot park until the system knows their car. This ticket delivers the first feature
endpoints of the product: a user registers a vehicle by its plate number, brand and model, and can
list the vehicles they have registered. Everything later in the flow (start parking, history) hangs
off a vehicle, so this is the entry point into the parking journey.

Two product rules matter here. First, **a user only ever sees their own vehicles** — the list is
scoped to the logged-in person and there is no way to ask for anybody else's. Second, **a plate
belongs to exactly one account across the whole system**: a plate is a real-world unique thing, so a
second person trying to register a plate that already exists is refused rather than silently given a
duplicate.

**Scope**

- JPA entity + Spring Data repository for the existing `vehicles` table (created in ticket 4):
  `id BIGINT PK · user_id FK · plate UNIQUE · brand · model · created_at`.
- `GET /api/v1/vehicles` → 200, a JSON array of the **principal's own** vehicles only
  (`id, plate, brand, model`). An empty array when the user has none — not a 404.
- `POST /api/v1/vehicles` with body `{plate, brand, model}` → **201** with the created vehicle.
  - `plate`, `brand` and `model` are required; a missing/blank field is a **400** through the global
    error contract from ticket 7.
  - A plate that already exists anywhere in the system → **409** `{code, message}`.

**The owner is never taken from the request body.** There is no `userId` field on the request and
none is accepted; the owner is the authenticated principal (design N4: "Identity comes from the
authenticated principal, never from a request field"). Same for the read: the repository query is
filtered by the principal's user id, served by the index `(user_id)`.

**The database is what makes plate uniqueness true, not the service.** The table has
`UNIQUE (plate)`. The service may do a friendly pre-check ("does this plate exist?") to produce a
nice message, but a unique-constraint violation coming back from the insert must also be caught and
mapped to the same 409 — two people registering the same plate at the same instant must end with
exactly one row, never two, and never a 500.

**Out of scope:** editing or deleting a vehicle, transferring a vehicle between users, any zone or
parking behaviour.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres, base class from ticket 2): user A registers a vehicle →
  201 with the returned owner being A; `GET /api/v1/vehicles` as A returns exactly that vehicle,
  while the same call as user B returns an empty array.
- Integration test: registering a plate that another user already registered returns **409** with
  the `{code, message}` error body and leaves the row count unchanged.
- Web/validation test: `POST /api/v1/vehicles` with a blank `plate` returns **400** in the global
  error contract; a request with no bearer token returns **401**.
- Repository test: the "find by owner" query returns only rows whose `user_id` matches.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser open `/swagger-ui.html`, call `POST /api/v1/auth/login` for a seeded user,
  paste the access token into **Authorize**, then `POST /api/v1/vehicles` with
  `{plate: "CA1234XX", brand: "VW", model: "Golf"}` → observe 201 and an id in the response; run
  `GET /api/v1/vehicles` → the new vehicle is listed. Re-run the same POST → observe 409 with
  `{code, message}`. Log in as a second user, authorize with their token, `GET /api/v1/vehicles` →
  observe `[]`.
- Stress: fire N concurrent `POST /api/v1/vehicles` requests carrying the **same plate** (mixed
  across two different authenticated users). Invariant: exactly one returns 201, every other returns
  409 (never 500), and `SELECT count(*) FROM vehicles WHERE plate = ?` is exactly 1.
