---
exec-order: 5
category: add
depends-on: [4]
status: done
suggested-agents: [backend-developer]
---

# Add the Flyway migration for parking_sessions and payments with their invariant indexes

## Business description

This is the table pair that holds the money, and the two rules the whole product rests on are written
into it as database constraints rather than as application code:

1. **One unsettled session per vehicle.** A vehicle that is parked — or that has finished parking but
   has not paid — cannot start another session. No parking on debt.
2. **One live payment per session.** Paying twice for the same parking is impossible; a repeated pay
   request can only ever find the payment that already exists.

Both are enforced by PostgreSQL **partial unique indexes**, so two simultaneous requests end with one
committed row and one unique violation — the invariant holds even when the service layer is racing
with itself. The service-layer checks that turn those violations into friendly errors come in later
tickets; this ticket is the schema that makes the rules true.

Schema only: no JPA entities, no services, no endpoints.

**Schema to create (design §5), in one new migration `src/main/resources/db/migration/V3__sessions_and_payments.sql`:**

```
parking_sessions
  id         BIGINT        PK, identity
  user_id    BIGINT        NOT NULL, FK → users(id)     -- denormalized owner; set at insert, never updated
  vehicle_id BIGINT        NOT NULL, FK → vehicles(id)
  zone_id    BIGINT        NOT NULL, FK → zones(id)
  tariff_id  BIGINT        NOT NULL, FK → tariffs(id)   -- the tariff in force at start; prices the session forever
  started_at TIMESTAMPTZ   NOT NULL
  ended_at   TIMESTAMPTZ   NULL
  amount     NUMERIC(10,2) NULL                         -- written exactly once, at end
  paid_at    TIMESTAMPTZ   NULL                         -- written exactly once, by settlement
  CHECK ((ended_at IS NULL) = (amount IS NULL))
  CHECK (ended_at >= started_at)
  CHECK (paid_at IS NULL OR ended_at IS NOT NULL)

payments
  id         BIGINT        PK, identity
  session_id BIGINT        NOT NULL, FK → parking_sessions(id)
  amount     NUMERIC(10,2) NOT NULL                     -- copied from the session
  status     VARCHAR       NOT NULL                     -- PENDING | COMPLETED | FAILED
  attempts   INT           NOT NULL DEFAULT 0
  created_at TIMESTAMPTZ   NOT NULL DEFAULT now()
  settled_at TIMESTAMPTZ   NULL
```

**Indexes — all five, exactly these predicates:**

```
UNIQUE (vehicle_id) WHERE paid_at IS NULL                     on parking_sessions   -- one unsettled session per vehicle
       (user_id)    WHERE ended_at IS NULL                    on parking_sessions   -- the user's active sessions
       (user_id, started_at DESC)                             on parking_sessions   -- history, newest first, paginated
UNIQUE (session_id) WHERE status IN ('PENDING','COMPLETED')    on payments          -- one live payment per session
       (created_at) WHERE status = 'PENDING'                   on payments          -- the settlement job's scan
```

Rules that matter:

- The unsettled index is on **`paid_at IS NULL`**, not `ended_at IS NULL` — that single choice is what
  implements "no parking on debt".
- The payments index counts only `PENDING` and `COMPLETED`, so a `FAILED` payment does **not** block a
  retry: a new `PENDING` row can be inserted while the failed row stays as audit trail.
- `status` is a `VARCHAR` column backed by a Java enum, never a PostgreSQL enum type.
- Money is `NUMERIC(10,2)`; all timestamps are `TIMESTAMPTZ`.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- JDBC/repository test against Testcontainers Postgres: a second `parking_sessions` insert for a
  vehicle that already has a row with `paid_at IS NULL` → unique violation; the same insert succeeds
  once the first row's `paid_at` is set.
- JDBC test: a second `payments` insert with `status='PENDING'` for a session that already has a
  `PENDING` row → unique violation; inserting a new `PENDING` row is allowed once the existing row is
  `FAILED`.
- JDBC test for each of the three `CHECK`s: `ended_at` set with `amount` null is rejected,
  `ended_at < started_at` is rejected, `paid_at` set with `ended_at` null is rejected.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: at SQL level against the running Postgres, fire N concurrent `INSERT INTO parking_sessions`
  statements for the **same `vehicle_id`** from N parallel connections committing at once — exactly
  one row must survive and every other connection must get a unique-violation error, with no case of
  two unsettled rows for one vehicle. Repeat the same shape for `payments`: N concurrent `PENDING`
  inserts for one `session_id` → exactly one row.
