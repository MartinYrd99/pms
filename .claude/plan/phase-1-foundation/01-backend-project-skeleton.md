---
exec-order: 1
category: add
depends-on: []
status: done
suggested-agents: [backend-developer]
---

# Create the backend Maven project skeleton that builds green

## Business description

The Parking Management System needs a backend service before any feature can be built. This ticket
creates that empty-but-running service: a single Maven module named `pms-backend` at the repository
root, with the exact technology set the system design fixes, a configuration file that separates
local development from a production-style run, and a health endpoint so anyone (a reviewer, Docker
Compose, a monitoring tool) can ask "is the service up?" and get an answer.

Nothing about parking exists yet — no users, no zones, no sessions, no database schema. The only
observable outcome is that `./mvnw verify` completes successfully and the application starts and
answers on its health endpoint.

**Fixed stack choices (design §6) — use exactly these, do not substitute:**

- Language: **Java 25** (LTS), Maven toolchain / compiler release 25.
- Framework: **Spring Boot 4.x**, single module, Maven build with the **Maven wrapper (`./mvnw`)**
  committed so a reviewer can build without a local Maven install.
- Base package: **`com.pms`** (vertical-slice packages per feature come in later tickets).
- Starters and dependencies on the classpath from this ticket onward:
  - `spring-boot-starter-web` (REST API)
  - `spring-boot-starter-security` (auth, wired in phase 2)
  - `spring-boot-starter-data-jpa` (persistence)
  - `spring-boot-starter-validation` (Bean Validation → 400s)
  - `spring-boot-starter-actuator` (health)
  - `flyway-core` + `flyway-database-postgresql` (SQL migrations)
  - `org.postgresql:postgresql` (JDBC driver, runtime)
  - Lombok (provided/annotation processing)
- Configuration: a **single `application.properties`**, no Spring profiles. Anything that varies between
  machines is a property with a working local default, overridable by environment variable:

  ```yaml
  spring.datasource.url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/pms}
  ```

  A plain `mvnw spring-boot:run` then needs no configuration at all, and anything running elsewhere
  overrides only what it needs. **Do not introduce a `dev` or `prod` profile**.
- Actuator: expose **`health`** over HTTP at `/actuator/health`, returning `UP` when the app is up.
  Do not expose the full actuator surface; `health` (plus `info` if convenient) is enough.
- `spring.jpa.hibernate.ddl-auto` must be **`validate`** — Flyway owns the schema, Hibernate never
  creates or alters it. (With no entities and no schema yet this is a no-op, but the setting is
  part of the skeleton contract every later ticket relies on.)

Explicitly out of scope here: any domain class, any entity, any migration file, any Docker file,
any security configuration, any endpoint other than actuator health.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Context-load test: the Spring application context starts with the default configuration (no profile
  active) and the build's test phase passes (`./mvnw verify` green).
- MockMvc/web test: `GET /actuator/health` returns 200 with a body whose `status` is `UP`.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: none — this ticket carries no concurrency invariant.
