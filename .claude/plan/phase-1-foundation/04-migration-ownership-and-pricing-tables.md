---
exec-order: 4
category: add
depends-on: [3]
status: done
suggested-agents: [backend-developer]
---

# Add the Flyway migration for vehicles, zones and tariffs

## Business description

A user parks a **vehicle** in a **zone**, and the zone's **tariff** says what an hour there costs.
This ticket creates those three tables. The important product idea it encodes is that a zone and its
price are separate things: the zone answers *where* (name, city, whether it is currently offered),
the tariff answers *what it costs and during which period*. Tariff rows are immutable and versioned
— changing a price means closing the old row and inserting a new one, never editing history — which
is what lets any past parking amount stay reproducible forever.

Schema only: no JPA entities, no endpoints, no seed rows (seeding is ticket 6).

**Schema to create (design §5), in one new migration `src/main/resources/db/migration/V2__ownership_and_pricing.sql`:**

```
vehicles
  id         BIGINT      PK, identity
  user_id    BIGINT      NOT NULL, FK → users(id)
  plate      VARCHAR     NOT NULL, UNIQUE          -- unique system-wide, not per user
  brand      VARCHAR     NOT NULL
  model      VARCHAR     NOT NULL
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX (user_id)                                  -- "my vehicles" is a hot read

zones
  id         BIGINT      PK, identity
  name       VARCHAR     NOT NULL
  city       VARCHAR     NOT NULL                  -- plain column; no rule depends on it, no cities table
  active     BOOLEAN     NOT NULL DEFAULT true
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()

tariffs
  id          BIGINT        PK, identity
  zone_id     BIGINT        NOT NULL, FK → zones(id)
  rule_type   VARCHAR       NOT NULL                -- HOURLY is the only value in v1
  hourly_rate NUMERIC(10,2) NOT NULL
  currency    CHAR(3)       NOT NULL DEFAULT 'EUR'
  valid_from  TIMESTAMPTZ   NOT NULL
  valid_to    TIMESTAMPTZ   NULL                    -- current tariff ⇔ valid_to IS NULL
  UNIQUE (zone_id) WHERE valid_to IS NULL           -- partial unique: at most one current tariff per zone
```

Rules that matter:

- `rule_type` and every other enum in this system is a **`VARCHAR` column backed by a Java enum**,
  never a PostgreSQL enum type — adding a value must be a code change, not an `ALTER TYPE` that
  cannot run inside a migration transaction.
- The partial unique index `UNIQUE (zone_id) WHERE valid_to IS NULL` is what guarantees a zone can
  never have two current prices. Write it as
  `CREATE UNIQUE INDEX … ON tariffs (zone_id) WHERE valid_to IS NULL;`
- Money is `NUMERIC(10,2)` — never a floating-point type.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Migration test against Testcontainers Postgres: the three tables and the `vehicles(user_id)` index
  exist after Flyway runs.
- JDBC test: two `vehicles` rows with the same `plate` (different users) → unique violation.
- JDBC test: inserting a second `tariffs` row for the same `zone_id` with `valid_to IS NULL` → unique
  violation; the same insert succeeds once the first row's `valid_to` is set.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — the tariff uniqueness rule is exercised sequentially here; the concurrency-critical
  indexes land in ticket 5.
