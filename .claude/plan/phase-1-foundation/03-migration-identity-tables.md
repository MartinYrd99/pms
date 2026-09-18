---
exec-order: 3
category: add
depends-on: [2]
status: done
suggested-agents: [backend-developer]
---

# Add the Flyway migration for the identity tables (users, refresh_tokens)

## Business description

Accounts and login sessions are the foundation everything else hangs off: a vehicle belongs to a
user, a parking session belongs to a user, and every call but register/login is authenticated. This
ticket creates the two database tables that hold that identity — the accounts themselves and the
long-lived refresh tokens that keep a user logged in for 48 hours — as plain SQL under Flyway.

Only the schema is delivered. No JPA entities, no repositories, no endpoints, no password hashing
code — those arrive in phase 2. A reviewer's checkpoint here is: start the app, the migration
applies cleanly, the two tables exist with the stated columns and constraints.

**Schema to create (design §5), in one new migration file `src/main/resources/db/migration/V1__identity.sql`:**

```
users
  id            BIGINT      primary key, database-generated identity
  username      VARCHAR     NOT NULL, UNIQUE
  password_hash VARCHAR(60) NOT NULL          -- BCrypt; the salt is inside the hash, no separate column
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()

refresh_tokens
  id         BIGINT      primary key, database-generated identity
  user_id    BIGINT      NOT NULL, FK → users(id)
  token_hash VARCHAR     NOT NULL, UNIQUE     -- only the hash is stored; the raw token lives in the client
  expires_at TIMESTAMPTZ NOT NULL
  revoked_at TIMESTAMPTZ NULL
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

Notes that matter:

- `UNIQUE (token_hash)` is the index the refresh endpoint looks a presented token up by — it is
  required, not optional.
- A refresh token is valid **⇔ `revoked_at IS NULL AND expires_at > now()`**; the schema carries no
  status column, that predicate is the whole state machine.
- All timestamps are `TIMESTAMPTZ` (the system stores UTC; Europe/Sofia is a presentation concern).
- Never edit an applied migration — later schema changes get their own numbered file.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Migration test against Testcontainers Postgres: after Flyway runs, both tables exist with the
  listed columns and types.
- Repository/JDBC test: inserting two `users` rows with the same `username` fails with a unique
  violation; the same for two `refresh_tokens` rows with the same `token_hash`.
- JDBC test: inserting a `refresh_tokens` row whose `user_id` does not exist fails on the foreign key.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — this ticket carries no concurrency invariant.
