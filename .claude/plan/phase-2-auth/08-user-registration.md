---
exec-order: 8
category: add
depends-on: [7]
status: done
suggested-agents: [backend-developer]
---

# Implement user registration with BCrypt password hashing

## Business description

A person must be able to create an account before they can register a vehicle or park. This ticket
delivers that first public endpoint: pick a username and a password, get an account. The password is
never stored as typed — only a BCrypt hash goes into the database, so a database leak yields nothing
usable.

Usernames are unique across the system. Asking for one that is already taken is not a malformed
request, it is a state conflict, so it returns **409** through the error contract from ticket 7 —
never a 400.

**Scope:**

- JPA entity + Spring Data repository for the existing `users` table
- A `PasswordEncoder` bean using **BCrypt**; the salt is embedded in the hash, there is no separate
  salt column. Hashes must fit `VARCHAR(60)`.
- `POST /api/v1/auth/register` with body `{username, password}` → **201**. Bean Validation on both
  fields (non-blank, sane length bounds) → 400 on violation.
- Duplicate username → **409**. Check first for a friendly error, but
  the `UNIQUE (username)` constraint is what makes it true — a unique violation from a concurrent
  insert must surface as the same 409, not a 500.
- The response body must **never** contain the password or the hash.
- The endpoint is **public**; it is one of the three paths that stay unauthenticated
  (`/auth/register`, `/auth/login`, `/auth/refresh`). The security filter chain itself is ticket 9 —
  if no chain exists yet, this endpoint must simply remain reachable after ticket 9 lands.

Out of scope: login, tokens, any notion of roles, email or profile fields.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): `POST /api/v1/auth/register` with a fresh username
  returns 201, persists one `users` row, and the stored `password_hash` is a BCrypt hash that
  verifies against the submitted password and is not the plaintext.
- Integration test: registering an already-taken username returns 409 with the `{code, message}` body
  and creates no second row.
- Web/slice test: a blank or missing `username`/`password` returns 400.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E (HTTP against the running Docker Compose stack — there is no UI yet, so this is driven with
  HTTP calls, not a browser): the happy-path registration flow. `POST /api/v1/auth/register` with a
  fresh username returns **201**, the response body carries neither the password nor the hash, and
  the `users` table holds exactly one row for that username whose `password_hash` is a BCrypt hash
  that is not the plaintext. Re-sending the same request returns **409** with the `{code, message}`
  body.
- Stress: fire N concurrent `POST /api/v1/auth/register` requests with the **same username** — exactly
  one must return 201, every other must return 409 (never 500), and the `users` table must end with
  exactly one row for that username.
