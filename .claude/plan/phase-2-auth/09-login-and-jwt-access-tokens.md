---
exec-order: 9
category: add
depends-on: [8]
status: done
suggested-agents: [backend-developer]
---

# Implement login, JWT access tokens and the authenticated filter chain

## Business description

With accounts in place, a user must be able to log in and then make authenticated calls. Logging in
returns two things: a **short-lived access token** that travels on every request, and a **long-lived
refresh token** that will later renew it. Keeping the token that is sent on every call short-lived
means a stolen one is useful for minutes, not days.

From this ticket on, the API is closed by default: everything under `/api/v1` requires a valid access
token except the three public auth endpoints. And the identity behind a request always comes from the
verified token — **no user id ever appears in a URL or a request body** . Later endpoints
must have no way to act on behalf of another user, because they are never told a user id.

**Scope:**

- Spring Security filter chain:
  - **Public:** `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`.
  - **Authenticated:** everything else under `/api/v1`.
  - Stateless (no HTTP session, no CSRF token flow — the bearer token is the whole auth story).
  - A missing, malformed or expired token → **401** in the `{code, message}` body from ticket 7.
- `POST /api/v1/auth/login` with `{username, password}` → **200** `{accessToken, refreshToken}`.
  Wrong username or wrong password → **401** with the same neutral message for both (never reveal
  which of the two was wrong).
- **Access token:** a JWT signed via **`spring-security-oauth2-jose`** (Nimbus), **TTL exactly 10
  minutes**, carrying the user's identity as subject. The signing key is a **configuration property
  with a development default, overridable by environment variable** (the same pattern as the
  datasource in ticket 1 — this project has no Spring profiles), never hard-coded in a class. Verification uses the same mechanism — no hand-rolled parsing.
- **Refresh token issued at login:** an opaque random token returned to the client, stored in
  `refresh_tokens` **hashed** in `token_hash` (the raw value exists only in the client), with
  `expires_at = now + 48 h`, `revoked_at = NULL`. Consuming, rotating and revoking refresh tokens is
  ticket 10 — this ticket only issues and persists them.
- The authenticated principal must be reachable by later controllers/services as the sole source of
  the current user id.

Out of scope: `/auth/refresh` and `/auth/logout` behaviour (ticket 10), roles/authorities beyond a
single authenticated user, Swagger UI wiring (ticket 11).

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): register, then `POST /auth/login` with correct
  credentials returns 200 with a non-blank `accessToken` and `refreshToken`, and a `refresh_tokens`
  row exists whose `token_hash` is not the returned raw token and whose `expires_at` is ~48 h ahead.
- Integration test: login with a wrong password and login with an unknown username both return 401
  with the same `{code, message}` body.
- Test on the issued access JWT: its expiry is 10 minutes after issue and its signature verifies with
  the configured key.
- Security test: a request to an authenticated `/api/v1` path with no token and with a garbage token
  returns 401, while `POST /api/v1/auth/register` stays reachable without a token.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: a successful login, walked end to end against the running stack — register a fresh user via
  `POST /api/v1/auth/register`, then `POST /api/v1/auth/login` with those same credentials returns
  200 with a non-blank `accessToken` and `refreshToken`; that access token, sent as
  `Authorization: Bearer <token>` against an authenticated `/api/v1` path, is accepted (anything but
  401), while the same path with no token returns 401.
  **Drive this with live HTTP calls against the stack, not a browser** — there is no SPA yet and
  Swagger UI only arrives in ticket 11, so HTTP is the intended mechanism for this line rather than
  a fallback. Record it as SUCCESS/FAILED on that basis; do not record it SKIPPED for want of a
  browser.
- Stress: none — this ticket carries no concurrency invariant.
