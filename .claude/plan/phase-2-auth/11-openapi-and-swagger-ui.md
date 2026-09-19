---
exec-order: 11
category: add
depends-on: [10]
status: open
suggested-agents: [backend-developer]
---

# Expose OpenAPI and Swagger UI with bearer-token authorization

## Business description

There is no web UI for this product until a later phase, but the API must be demonstrable now. This
ticket gives a reviewer a browser surface: a Swagger UI page that lists the endpoints, lets them log
in, paste the returned access token once, and then call authenticated endpoints directly — driving
the whole API without the SPA.

It is also the documentation surface every later endpoint inherits: once this is wired, each new
endpoint appears in Swagger UI automatically with its request/response shapes.

**Scope:**

- Add **springdoc-openapi** (the Spring Boot 3+/4 WebMVC starter) and expose:
  - the generated OpenAPI document (`/v3/api-docs`),
  - **Swagger UI** at a fixed, documented path (e.g. `/swagger-ui.html` → `/swagger-ui/index.html`).
- Configure a **bearer / JWT security scheme** in the OpenAPI document so the Swagger UI
  **Authorize** button accepts an access token and sends it as `Authorization: Bearer <token>` on
  subsequent calls. The three public endpoints (`/auth/register`, `/auth/login`, `/auth/refresh`)
  must be callable in Swagger UI **without** authorizing.
- Permit the docs paths in the Spring Security filter chain from ticket 9 (`/v3/api-docs/**`,
  `/swagger-ui/**`, `/swagger-ui.html`) so the page itself loads unauthenticated. Everything else
  under `/api/v1` stays authenticated — do not widen the chain beyond the docs paths.
- Basic API metadata: title, version, short description. No per-endpoint annotation sweep is required
  here; each later ticket documents its own endpoint.

Out of scope: publishing the spec anywhere, client code generation, customising the UI's appearance.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test: `GET /v3/api-docs` returns 200 without a token and the document lists the
  `/api/v1/auth/login` path.
- Integration test: the OpenAPI document declares an HTTP bearer security scheme with JWT format.
- Security test: the Swagger UI entry path returns a successful/redirect response without a token,
  while an authenticated `/api/v1` path still returns 401 without one.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: in a browser, open Swagger UI on the running stack, call `POST /api/v1/auth/register` with a
  fresh username, then `POST /api/v1/auth/login` with those credentials, copy the returned
  `accessToken`, press **Authorize** and paste it as the bearer token, then call an authenticated
  `/api/v1` endpoint and observe it succeed (not 401); pressing **Logout** in the Authorize dialog and
  repeating the same call returns 401.
- Stress: none — this ticket carries no concurrency invariant.
