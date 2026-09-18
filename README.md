# PMS

Parking Management System.

## Running the stack

One-command run (Postgres + backend):

```
docker compose up --build
```

The backend is then reachable at `http://localhost:8080`, and Postgres at `localhost:5432`
(db `pms`, user/password `pms`/`pms`).

### Local / IDE run

You can also run just the database via Compose and start the backend from your IDE or with
`./mvnw spring-boot:run` (from `pms-backend/`):

```
docker compose up postgres
```

The backend's default datasource (`localhost:5432`, db/user/password `pms`) already points at
this container, so no extra configuration is needed for a local run. The `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` environment variables override these
defaults, which is how the Compose `backend` service points at the `postgres` service instead.

## Tests

Backend tests run against a real PostgreSQL 17 instance via Testcontainers (see
`pms-backend/src/test/java/com/pms/AbstractPostgresIT.java`) — there is no H2 fallback, because the
schema's partial unique indexes and `CHECK` constraints only exist in Postgres. **Docker must be
running on the machine that runs the tests.**

```
cd pms-backend
./mvnw test
```
