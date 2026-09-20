---
exec-order: 27
category: add
depends-on: [26]
status: done
suggested-agents: [frontend-developer]
---

# Deliver the vehicles screen: list own vehicles and register a new one

## Business description

A driver cannot park until the app knows their car, so this is the first thing a newly registered
user does. The screen shows the vehicles this user has registered and lets them add another by plate
number, brand and model. A user only ever sees their own vehicles — the backend scopes the list to
the signed-in person and no user id is ever sent.

**The screen** lives at `/vehicles`, reachable from the home screen. It reads
`GET /api/v1/vehicles` → an array of `{id, plate, brand, model}` and renders each vehicle as a row
with plate, brand and model. It renders all three of its states: loading, a readable error (never a
blank screen or a console log), and **empty** — "no vehicles yet, add your first one" — because an
empty list is the normal state for a new account, not a failure.

**Adding a vehicle** is a form with `plate`, `brand` and `model` that submits
`POST /api/v1/vehicles {plate, brand, model}` → **201** with the created vehicle. On success the
list refreshes (invalidate the vehicles query — do not hand-patch a local copy) and the form clears.

**The duplicate plate is the case that matters.** A plate belongs to exactly one account across the
whole system, so registering a plate that already exists anywhere returns **409** with
`{code, message}`. That must appear as a **field-level message next to the plate input** — "this
plate is already registered" — with the values the user typed still in the form, not as a generic
"something went wrong". A **400** validation response likewise lands on the field it concerns.

**Out of scope:** editing or deleting a vehicle, any parking action (ticket 28), and searching or
sorting the list.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket (mocked API):

- A mocked two-vehicle response renders both plates; a mocked empty response renders the empty state.
- Filling and submitting the form posts the entered plate/brand/model and the new vehicle appears in
  the refreshed list.
- A mocked 409 on submit renders the duplicate-plate message against the plate field, leaves the
  typed values in place and leaves the list unchanged.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in the browser against the running stack — sign in as a fresh user, open vehicles, see the
  empty state, add `CA1234XX / VW / Golf` → it appears in the list and survives a page reload;
  submit the same plate again → a readable message next to the plate field and still one row.
- Stress: none — the plate-uniqueness invariant is enforced and stress-tested on the server side.
