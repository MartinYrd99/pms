---
exec-order: 2
category: add
depends-on: [1]
status: done
suggested-agents: [backend-developer]
---

# Add the Docker Compose dev stack, datasource wiring and the Testcontainers test base

## Business description

A reviewer must be able to bring the whole backend up with one command, and every test we write from
here on must run against the **real PostgreSQL schema** — because the system's two money invariants
are enforced by PostgreSQL partial unique indexes and `CHECK` constraints that no in-memory database
reproduces. Testing against H2 would test a different schema than the one that ships.

This ticket delivers three things: a Compose file that starts the database and the backend, the
datasource configuration that connects the app to it, and a reusable JUnit 5 test base class backed
by Testcontainers so every later ticket inherits "real Postgres, real migrations" for free.

**What to build:**

- `docker-compose.yml` at the repository root with:
  - a **`postgres`** service on **PostgreSQL 17** (official image), with a named volume for data, a
    database/user/password for the app, and a healthcheck so the backend waits for it. It
    **publishes `5432` to the host**, so a developer can run just this service and point an IDE run
    of the backend at it — that is the local run mode, and it is why the datasource default below is
    `localhost`;
  - a **`backend`** service built from `pms-backend` (Dockerfile in this ticket), depending on
    `postgres` being healthy, exposing the API port. It runs with **no Spring profile** — it is the
    same application as a local run, pointed at a different host purely through environment:
    `SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/<db>` plus the username and password.
- Datasource wiring in the backend: JDBC URL / user / password read from environment variables with
  defaults pointing at **`localhost:5432`**, so an IDE run against the Compose Postgres needs no
  configuration and the Compose backend only overrides the host. A **connection pool with a maximum
  size of 10** (design §7: "JDBC ·
  pool ≤ 10 · sole database client"). Flyway is enabled and points at
  `classpath:db/migration`; there are no migration files yet — ticket 3 adds the first one.
- A test harness: an abstract base class (e.g. `AbstractPostgresIT`) using **Testcontainers with the
  PostgreSQL 17 image**, a single container reused across the test classes that extend it, and
  Spring properties bound to the container so `@SpringBootTest` / `@DataJpaTest` style tests run
  against it with Flyway applied. Tests require **Docker** on the machine running them — state that
  in the project README section you touch.
- **No H2.** Do not add an H2 dependency, not even test-scoped.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test extending the Testcontainers base: the context starts against the container and a
  trivial SQL query (`SELECT 1`) through the configured `DataSource` succeeds.
- Test asserting the resolved connection pool maximum size is 10.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — this ticket carries no concurrency invariant.
