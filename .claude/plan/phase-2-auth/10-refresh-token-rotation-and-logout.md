---
exec-order: 10
category: add
depends-on: [9]
status: done
suggested-agents: [backend-developer]
---

# Implement refresh token rotation and logout

## Business description

A parking session lasts hours; the access token lasts ten minutes. So the app must be able to renew
its access token silently while the user is parked, and the user must be able to log out for real.
Both are this ticket.

Renewal is **rotation**: presenting a refresh token returns a brand-new access **and** refresh token,
and the presented one is revoked in the same breath. A refresh token is therefore single-use — if the
same one is presented twice, the second attempt fails. Logout revokes the refresh token outright; the
access token simply dies on its own within ten minutes.

**Scope and exact rules:**

- `POST /api/v1/auth/refresh` with `{refreshToken}` → **200** `{accessToken, refreshToken}` (a new
  pair). The presented token is marked revoked in the same transaction as the new one is issued.
  - **401** if the token is unknown, expired, or already used/revoked — one and the same neutral
    `{code, message}` response for all three; never reveal which.
  - Public endpoint (it is used precisely when the access token is dead).
- `POST /api/v1/auth/logout` with `{refreshToken}` → **204 No Content**, revoking that token.
  Logging out with a token that is already invalid must not leak that fact.
- **Storage rules (table from ticket 3):** tokens are stored **hashed** in `refresh_tokens.token_hash`
  (`UNIQUE`) — the raw token exists only in the client; lookup is by hash of the presented value.
  A token is **valid ⇔ `revoked_at IS NULL AND expires_at > now()`**. Revoking = stamping
  `revoked_at`. Rows are never deleted on use.
- **TTL: new refresh tokens expire 48 h after issue.** The rotated token gets a fresh 48 h window; the
  access token in the returned pair keeps the 10-minute TTL from ticket 9.
- **Rotation must be safe under concurrency.** Two requests arriving with the same refresh token at
  the same moment must not both succeed. Make the revocation a **guarded update** — e.g.
  `UPDATE refresh_tokens SET revoked_at = now() WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > now()`
  — and treat **zero rows updated as "someone else got there first" → 401**, issuing the new pair only
  when the update claimed the row.

Out of scope: sweeping expired rows (they are bounded by the number of logins and may be left),
device/session listing, refresh-token reuse detection beyond the 401.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

- Integration test (Testcontainers Postgres): login, then `POST /auth/refresh` returns a new pair,
  the new access token differs from the old one, the presented token's row has `revoked_at` set, and
  a new `refresh_tokens` row exists with `expires_at` ~48 h ahead.
- Integration test: presenting the same refresh token a second time returns 401; an unknown token and
  an expired token (row seeded with a past `expires_at`) also return 401 with the same body.
- Integration test: `POST /auth/logout` returns 204 and stamps `revoked_at`; a subsequent refresh with
  that token returns 401.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: none — no user-facing surface yet.
- Stress: fire two (and then N) concurrent `POST /api/v1/auth/refresh` calls carrying the **same**
  refresh token — exactly one must return 200 with a new pair, all others must return 401, and the
  table must show exactly one new token row issued from that parent, with the parent revoked once.
