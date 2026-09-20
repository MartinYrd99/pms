---
exec-order: 34
category: change
depends-on: [10, 25, 26]
status: done
suggested-agents: [backend-developer, frontend-developer]
---

# Move the refresh token into an HttpOnly cookie and keep the access token in memory only

## Business description

Today both tokens live in `localStorage` under one key, which means any script that runs on the page
can read a **48-hour refresh token** and walk away with it. That is the difference between "an
attacker can act while the tab is open" and "an attacker owns the account from their own machine
until the token expires". This ticket removes the durable credential from JavaScript's reach
entirely.

The fix is a split by lifetime, not a blanket move to cookies:

- **The refresh token becomes an `HttpOnly` cookie** issued by the server. JavaScript can neither
  read it nor write it; the browser attaches it automatically, and only to the two endpoints that
  need it.
- **The access token stops being persisted at all.** It lives in a module variable inside the API
  client, dies on reload, and is re-minted from the cookie when the app boots.

After this ticket **nothing credential-shaped survives a page reload in JavaScript-readable storage**,
and `localStorage` is empty. Note what this does and does not buy: script injected into the page can
still make authenticated calls, because the cookie rides along on requests the page itself makes.
What it stops is **exfiltration** — copying the long-lived token out to be replayed elsewhere.

### Backend

**Cookie attributes** — the refresh token is set as `HttpOnly`, `Secure`, `SameSite=Strict`,
`Path=/api/v1/auth`, `Max-Age` equal to the 48-hour token TTL from ticket 10. `Path` scoping matters:
the cookie must not ride along on the twelve endpoints that have no use for it. `SameSite=Strict` is
what defends the two endpoints that do — see the CSRF note below. `Secure` must be configurable
(`pms.auth.refresh-cookie.secure`, default `true`) so a plain-HTTP Compose run can still be exercised;
browsers already treat `http://localhost` as a secure context, so local dev needs no exception.

**Endpoint changes** (the paths themselves do not move):

- `POST /auth/login` → **200**, body is now `{accessToken}` **only**, plus a `Set-Cookie` carrying the
  refresh token. The response record no longer describes a pair, so rename it accordingly
  (`LoginResponse` → `AccessTokenResponse`).
- `POST /auth/refresh` → **takes no request body at all**; it reads the cookie. Returns `{accessToken}`
  and a `Set-Cookie` with the **rotated** refresh token. Rotation, the guarded update and the neutral
  401 from ticket 10 are unchanged — this ticket changes only how the token reaches the server and how
  the new one gets back. A 401 must **also** send a cookie-clearing `Set-Cookie` (`Max-Age=0`,
  identical `Path`), so a browser holding a dead token stops presenting it.
- `POST /auth/logout` → takes no request body; reads the cookie, revokes it, returns **204**, and
  clears the cookie. Logging out with an already-invalid token must still not leak that fact.
- A missing cookie on refresh or logout is treated exactly like an invalid one — the same neutral
  `{code, message}` 401 (refresh) / 204 (logout).
- `RefreshTokenRequest` is retired.

**CSRF.** `SecurityConfig` currently disables CSRF, which is correct *today* only because auth
travels in a header that nothing attaches automatically. A cookie that the browser sends on its own
reopens that door for the two `/auth` endpoints. The defence here is `SameSite=Strict` plus the `Path`
scope: a cross-site `POST` to `/api/v1/auth/refresh` does not carry the cookie in any current browser,
and an attacker cannot read the response across origins anyway, so the residual worst case is a forced
logout, not account access. Strict is safe for this cookie specifically because it is only ever used
by same-origin `fetch` calls, never by a top-level navigation into the app. **A double-submit CSRF
token is deliberately out of scope** — record that decision and its rationale in the design doc rather
than leaving it unstated.

**Deployment shape makes this work:** Vite proxies `/api/v1` to the backend in dev and nginx proxies
it in Compose, so the browser sees one origin in both modes and the cookie needs no cross-site
handling. This is also what retires the design's original argument for header-only auth.

### Frontend

- `tokenStorage.ts` stops using `localStorage` — the access token becomes a module-scoped variable.
  The `pms.auth.tokens` key and `hasStoredSession()` both disappear. Nothing in `src/` may read or
  write `localStorage` for auth afterwards.
- Every request sends `credentials: "same-origin"` so the cookie is attached. No request ever carries
  a refresh token in its body again.
- The **interceptor's guarantees are unchanged and must stay proven**: one refresh per 401, one retry,
  never a loop, and concurrent 401s sharing a single in-flight refresh promise. Only the mechanics
  change — refresh sends no body and reads no stored token.
- `logout()` calls the endpoint and clears the in-memory token, still clearing locally even if the
  call fails.
- **New: `restoreSession()`** — called once at app start. It attempts a single refresh; success puts a
  fresh access token in memory, a 401 means "not signed in". It must **not** fire the session-expired
  callback, because a first-time visitor with no cookie is not an expired session — that callback
  stays reserved for a session dying while the app is open.
- Ticket 26's route guard swaps `hasStoredSession()` for `restoreSession()`. The auth context built in
  26 already models "still checking / signed in / signed out", so the guard renders its checking state
  while the boot refresh is in flight; no component restructuring should be needed.

### Spec

Update `doc/system_design.md` §4: rows 2, 3 and 4 of the endpoint table (bodies and the cookie), and
rewrite the **"Why JWT over a session cookie"** rationale paragraph — its claim that a cookie-free
design avoids CSRF surface and cross-origin cookie setup no longer describes the system. Record the
split (in-memory bearer access token + `HttpOnly` refresh cookie), what it buys against XSS
exfiltration, what it does not buy, and why double-submit CSRF was not added. Do not rewrite unrelated
sections.

**Out of scope:**

- **Double-submit CSRF tokens** — see the rationale above.
- **Rotation grace for concurrent cold boots.** Two tabs booting at the same moment present the same
  cookie; one wins the guarded update and the other gets a 401 and lands on login. This is latent
  today and this ticket does not make it worse; fixing it means either relaxing ticket 10's
  single-use guarantee or holding a cross-tab lock (Web Locks / `BroadcastChannel`). Leave it, and do
  not weaken the single-use rule to paper over it.
- Migrating anyone's existing stored tokens. This is a prototype; sessions from before the change
  simply end at the next reload and the user signs in again.
- Any change to zones, vehicles, parking or payments.

## Testing

**Implemented** — written by the developer agent(s) as part of this ticket:

Backend (integration, Testcontainers Postgres):

- `POST /auth/login` returns `{accessToken}` with **no** refresh token anywhere in the body, and a
  `Set-Cookie` whose attributes include `HttpOnly`, `SameSite=Strict`, `Path=/api/v1/auth` and a
  `Max-Age` of ~48 h.
- `POST /auth/refresh` with the cookie and **no request body** returns a new access token plus a
  rotated `Set-Cookie`; the presented token's row has `revoked_at` stamped and a new row exists.
- `POST /auth/refresh` with a missing, unknown, expired or already-used cookie returns 401 with the
  same neutral `{code, message}` in every case, together with a cookie-clearing `Set-Cookie`.
- `POST /auth/logout` with the cookie and no body returns 204, stamps `revoked_at`, and clears the
  cookie; a refresh with that token afterwards is 401.

Frontend (Vitest, mocked `fetch`) — the four tests from ticket 25 adapted to the new mechanics, plus
one new one:

- A `GET /vehicles` answering 401 triggers exactly one `POST /auth/refresh` (sent with no body), and
  the single retry carries the new access token and returns the retried response.
- Two requests that both receive 401 at the same time cause **one** refresh call, and both are retried
  and resolve.
- A failed refresh clears the in-memory access token and fires the "session expired" callback once.
- A 409 from `POST /parking-sessions` surfaces as a typed error whose `status`, `code`, `message` and
  parsed body (the blocking session id) are all readable by the caller.
- `restoreSession()` populates the access token from a single refresh call; on a 401 it resolves as
  "not signed in" **without** firing the session-expired callback. The suite also asserts that no
  module in `src/` touches `localStorage` and that every non-auth request carries
  `credentials: "same-origin"`.

**Verified at review time** — nobody writes code for these; the `/task` review step drives them:

- E2E: against the running Compose stack, sign in through the real login screen, then in devtools
  confirm the refresh cookie is present with `HttpOnly`, `SameSite=Strict` and `Path=/api/v1/auth`,
  that `localStorage` is **empty**, and that `document.cookie` does **not** contain the refresh token.
  Hard-reload the page and confirm the user is still signed in and a running parking session still
  renders. Then log out and confirm the cookie is gone and a guarded URL bounces to login.
- Stress: fire N concurrent `POST /api/v1/auth/refresh` calls carrying the **same** cookie — exactly
  one returns 200 with a rotated cookie, every other returns 401, and the table shows exactly one new
  token row with the parent revoked once (ticket 10's invariant, reconfirmed through the cookie path).
  Separately, from one page, fire N parallel API calls that all meet an expired access token and
  confirm the client made exactly **one** refresh call.
